package com.neurolens.eyecare.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Display
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** Headless front camera source for a camera foreground service. No preview surface is needed. */
class FrontCameraSource(
    private val context: Context,
    private val onObservation: (EyeObservation) -> Boolean,
    private val onState: (String) -> Unit
) {
    private val thread = HandlerThread("eyecare-front-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(CameraManager::class.java)
    private var requested = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var extractor: FaceEyeExtractor? = null
    private var rotationDegrees = 0
    private var sensorOrientation = 0
    private var cameraGeometryBaseId = "unavailable"
    private var focalPixels: Double? = null
    private var focalSource = "unavailable"
    private var cameraGeometryId = "unavailable"
    private var lastFrameAt = -1L
    private var pixelBuffer = IntArray(0)
    private var reusableBitmap: Bitmap? = null
    private var generation = 0L
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)
    private var lutFor: Enhancement? = null
    private var lut = IntArray(0)
    private var fpsRange: Range<Int>? = null
    private var compensator: ExposureCompensator? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var realtimeTimestamps = false
    private var staticAperture: Double? = null
    @Volatile private var latestEv100: Double? = null
    private var lastInferenceAt = -1L

    fun start() { handler.post { if (!requested) { requested = true; open() } } }
    fun stop() { handler.post { requested = false; release(); onState("camera_paused") } }
    fun close() { handler.post { requested = false; release(); thread.quitSafely() } }

    private fun open() {
        if (!requested || camera != null) return
        val openingGeneration = ++generation
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onState("permission_missing"); return
        }
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: run { onState("front_camera_missing"); return }
            val c = manager.getCameraCharacteristics(id)
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val size = sizes.minByOrNull { abs(it.width - 640) + abs(it.height - 480) }
                ?: run { onState("camera_format_missing"); return }
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            // Camera2 intrinsics are in active-sensor pixels. The stream is center-cropped and
            // resized to the selected size; use the same scale for an iris diameter in the frame.
            val scale = active?.let { maxOf(size.width.toDouble() / it.width(),
                size.height.toDouble() / it.height()) }
            val intrinsic = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                ?.takeIf { it.size >= 2 && it[0] > 0 && it[1] > 0 }
            focalPixels = if (intrinsic != null && scale != null)
                (intrinsic[0] + intrinsic[1]) / 2.0 * scale else if (physical != null &&
                physical.width > 0 && physical.height > 0 && focalMm != null && focalMm > 0)
                focalMm.toDouble() * (size.width / physical.width + size.height / physical.height) / 2.0 else null
            focalSource = if (intrinsic != null && scale != null) "camera2_intrinsic"
                else if (focalPixels != null) "camera2_physical" else "unavailable"
            cameraGeometryBaseId = "$id:${size.width}x${size.height}:${active?.width()}x${active?.height()}:$focalSource:${focalPixels?.toInt()}"
            val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            sensorOrientation = sensor
            val displayRotation = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
            rotationDegrees = (sensor - displayRotation * 90 + 360) % 360
            cameraGeometryId = "$cameraGeometryBaseId:rotation=$rotationDegrees"
            fpsRange = LowLightPolicy.chooseFpsRange(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty().map { it.lower to it.upper })?.let { Range(it.first, it.second) }
            val compensationMax = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0
            val compensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val stepsPerEv = compensationStep?.takeIf { it.numerator > 0 }
                ?.let { (it.denominator.toDouble() / it.numerator).toInt() } ?: 1
            compensator = ExposureCompensator(compensationMax, stepsPerEv)
            realtimeTimestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            staticAperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.toDouble()
            extractor = FaceEyeExtractor(context)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ source -> handleImage(source) }, handler)
            }
            onState("opening")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!requested || openingGeneration != generation) { device.close(); return }
                    camera = device
                    configure(device, openingGeneration)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); if (requested && openingGeneration == generation) failure("camera_disconnected", openingGeneration)
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); if (requested && openingGeneration == generation) failure("camera_error_$error", openingGeneration)
                }
            }, handler)
        } catch (_: Exception) { failure("camera_open_failed", openingGeneration) }
    }

    private fun configure(device: CameraDevice, openingGeneration: Long) {
        val surface = reader?.surface ?: return
        try {
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!requested || camera !== device || openingGeneration != generation) { configured.close(); return }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            // Keep >= ~15 fps for blinks while letting AE lengthen exposure in the dark.
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                        }
                        requestBuilder = builder
                        compensator?.reset()
                        configured.setRepeatingRequest(builder.build(), exposureCallback, handler)
                        onState("observing")
                    } catch (_: Exception) { failure("camera_stream_failed", openingGeneration) }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close(); failure("camera_config_failed", openingGeneration)
                }
            }, handler)
        } catch (_: Exception) { failure("camera_config_failed", openingGeneration) }
    }

    private val exposureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
            result: TotalCaptureResult) {
            latestEv100 = LowLightPolicy.ev100(
                result.get(CaptureResult.LENS_APERTURE)?.toDouble() ?: staticAperture,
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(CaptureResult.SENSOR_SENSITIVITY),
                result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST))
        }
    }

    /** Re-issues the repeating request only when the compensation index actually changes. */
    private fun applyCompensation(index: Int) {
        val builder = requestBuilder ?: return
        val active = session ?: return
        runCatching {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
            active.setRepeatingRequest(builder.build(), exposureCallback, handler)
        }
    }

    private fun copyPlane(buffer: java.nio.ByteBuffer, target: ByteArray): ByteArray {
        val view = buffer.duplicate().apply { rewind() }
        val out = if (target.size == view.remaining()) target else ByteArray(view.remaining())
        view.get(out, 0, out.size)
        return out
    }

    private fun handleImage(source: ImageReader) {
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        image.use {
            if (!requested) return@use
            val callbackAt = SystemClock.elapsedRealtime()
            val capturedAt = CameraClock.frameTimeMs(it.timestamp, realtimeTimestamps, callbackAt)
            if (capturedAt <= lastFrameAt) return@use
            val gap = (capturedAt - lastFrameAt).takeIf { lastFrameAt >= 0 }
            val wallAt = System.currentTimeMillis()
            val startedNs = SystemClock.elapsedRealtimeNanos()
            try {
                val currentRotation = context.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
                val uprightRotation = (sensorOrientation - currentRotation * 90 + 360) % 360
                if (uprightRotation != rotationDegrees) {
                    rotationDegrees = uprightRotation
                    cameraGeometryId = "$cameraGeometryBaseId:rotation=$rotationDegrees"
                }
                // A single camera worker owns these buffers; VIDEO inference is synchronous.
                val planes = it.planes
                yBytes = copyPlane(planes[0].buffer, yBytes)
                uBytes = copyPlane(planes[1].buffer, uBytes)
                vBytes = copyPlane(planes[2].buffer, vBytes)
                val stats = LowLightPolicy.lumaStats(yBytes, it.width, it.height,
                    planes[0].rowStride, planes[0].pixelStride)
                compensator?.update(callbackAt, stats?.p50)?.let(::applyCompensation)
                val enhancement = LowLightPolicy.enhancementFor(stats)
                val lowLight = stats != null && stats.p50 < LowLightPolicy.LOW_LIGHT_P50
                val upright = toBitmap(it, rotationDegrees, enhancement)
                // MediaPipe VIDEO mode needs strictly increasing timestamps across clock fallbacks.
                val inferenceAt = if (capturedAt > lastInferenceAt) capturedAt else lastInferenceAt + 1
                lastInferenceAt = inferenceAt
                extractor?.analyze(upright, inferenceAt, wallAt, it.timestamp,
                    focalPixels, focalSource, cameraGeometryId, enhancement, lowLight)?.let { observation ->
                    if (onObservation(observation.copy(
                        processingMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1e6,
                        frameGapMs = gap, rawLumaP50 = stats?.p50?.div(255.0),
                        cameraEv100 = latestEv100, aeFpsMin = fpsRange?.lower))) lastFrameAt = capturedAt
                }
            } catch (_: Exception) { onState("camera_frame_failed") }
        }
    }

    private fun toBitmap(image: Image, rotation: Int, enhancement: Enhancement): Bitmap {
        val width = image.width; val height = image.height
        val (outWidth, outHeight) = FrameRotation.dimensions(width, height, rotation)
        val planes = image.planes
        val yRow = planes[0].rowStride; val yPixel = planes[0].pixelStride
        val uRow = planes[1].rowStride; val uPixel = planes[1].pixelStride
        val vRow = planes[2].rowStride; val vPixel = planes[2].pixelStride
        if (lutFor != enhancement) { lut = LowLightPolicy.lut(enhancement); lutFor = enhancement }
        val table = lut; val gray = enhancement.grayscale
        val ys = yBytes; val us = uBytes; val vs = vBytes
        if (pixelBuffer.size != width * height) pixelBuffer = IntArray(width * height)
        val pixels = pixelBuffer
        for (row in 0 until height) {
            val yBase = row * yRow; val uBase = (row / 2) * uRow; val vBase = (row / 2) * vRow
            for (col in 0 until width) {
                val yy = table[ys[yBase + col * yPixel].toInt() and 255] - 16
                val c = 298 * yy.coerceAtLeast(0)
                val color = if (gray) {
                    val l = ((c + 128) shr 8).coerceIn(0, 255)
                    (l shl 16) or (l shl 8) or l
                } else {
                    val half = col / 2
                    val uu = (us[uBase + half * uPixel].toInt() and 255) - 128
                    val vv = (vs[vBase + half * vPixel].toInt() and 255) - 128
                    val red = ((c + 409 * vv + 128) shr 8).coerceIn(0, 255)
                    val green = ((c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                    val blue = ((c + 516 * uu + 128) shr 8).coerceIn(0, 255)
                    (red shl 16) or (green shl 8) or blue
                }
                pixels[FrameRotation.destination(row, col, width, height, rotation, outWidth)] = -0x1000000 or color
            }
        }
        val bitmap = reusableBitmap?.takeIf { it.width == outWidth && it.height == outHeight }
            ?: Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also {
                reusableBitmap?.recycle(); reusableBitmap = it
            }
        bitmap.setPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
        return bitmap
    }

    private fun failure(reason: String, openingGeneration: Long) {
        if (openingGeneration != generation) return
        release()
        onState(reason)
        if (requested) handler.postDelayed({ open() }, 5_000)
    }

    private fun release() {
        generation++
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
        runCatching { extractor?.close() }; extractor = null
        reusableBitmap?.recycle(); reusableBitmap = null
        pixelBuffer = IntArray(0)
        yBytes = ByteArray(0); uBytes = ByteArray(0); vBytes = ByteArray(0)
        requestBuilder = null; compensator?.reset(); latestEv100 = null
        lastFrameAt = -1; lastInferenceAt = -1
    }
}
