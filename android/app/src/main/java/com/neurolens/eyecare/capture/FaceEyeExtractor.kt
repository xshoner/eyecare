package com.neurolens.eyecare.capture

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.neurolens.eyecare.engine.EyeGeometry
import com.neurolens.eyecare.engine.EyeGeometryExtractor
import com.neurolens.eyecare.engine.FacePoint
import com.neurolens.eyecare.engine.GazeStabilityTracker
import com.neurolens.eyecare.engine.GazeWindow
import com.neurolens.eyecare.engine.DistanceEstimate
import com.neurolens.eyecare.engine.IrisDistance

data class EyeObservation(
    val capturedAtMs: Long, val wallAt: Long, val cameraTimestampNs: Long,
    val status: String, val geometry: EyeGeometry?, val gaze: GazeWindow?,
    val landmarks: List<Pair<Int, FacePoint>> = emptyList(),
    val blendshapes: Map<String, Float> = emptyMap(),
    val headMatrix: List<Float> = emptyList(),
    val faceIllumination: Double? = null,
    val distance: DistanceEstimate = DistanceEstimate(reason = "camera_geometry_unavailable"),
    val cameraGeometryId: String = "unavailable",
    val processingMs: Double = 0.0, val frameGapMs: Long? = null,
    /** Digital Y gain applied before landmarks (1 = none); see [LowLightPolicy.enhancementFor]. */
    val lowLightGain: Double = 1.0,
    /** Raw (pre-enhancement) median Y of the frame, 0–1. */
    val rawLumaP50: Double? = null,
    /** Camera scene brightness from exposure metadata; see [LowLightPolicy.ev100]. */
    val cameraEv100: Double? = null,
    val lowLight: Boolean = false,
    /** Lower bound of the AE target fps range requested for this stream. */
    val aeFpsMin: Int? = null
)

/** MediaPipe VIDEO mode runs on the camera worker, preserving ordered timestamps and backpressure. */
class FaceEyeExtractor(context: Context) : AutoCloseable {
    private val landmarker = FaceLandmarker.createFromOptions(context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            // Dark, noisy faces lose detector confidence first. A slightly lower detection and
            // presence floor keeps them; tracking stays at the default, and the eye-geometry
            // validity checks plus enhancement-scaled quality still reject implausible landmarks.
            .setMinFaceDetectionConfidence(.4f)
            .setMinFacePresenceConfidence(.4f)
            .setMinTrackingConfidence(.5f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build())
    private val gazeTracker = GazeStabilityTracker()

    fun analyze(bitmap: Bitmap, capturedAtMs: Long, wallAt: Long, cameraTimestampNs: Long,
        focalPx: Double?, focalSource: String, cameraGeometryId: String,
        enhancement: Enhancement = Enhancement(), lowLight: Boolean = false): EyeObservation {
        val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), capturedAtMs)
        val face = result.faceLandmarks().firstOrNull()
            ?: return EyeObservation(capturedAtMs, wallAt, cameraTimestampNs,
                if (lowLight) LOW_LIGHT_NO_FACE else "no_face", null, null,
                cameraGeometryId = cameraGeometryId, lowLightGain = enhancement.gain, lowLight = lowLight)
        val points = face.map { FacePoint(it.x(), it.y(), it.z()) }
        val geometry = EyeGeometryExtractor.extract(points)
        val gaze = geometry?.let { gazeTracker.add(capturedAtMs, it).takeIf { _ -> geometry.meanU != null } }
        val shape = result.faceBlendshapes().orElse(emptyList()).firstOrNull()
            ?.associate { it.categoryName() to it.score() } ?: emptyMap()
        val selected = EYE_INDICES.mapNotNull { index -> points.getOrNull(index)?.let { index to it } }
        val headMatrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
            ?.toList() ?: emptyList()
        val faceIllumination = runCatching {
            val minX = (points.minOf { it.x } * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val maxX = (points.maxOf { it.x } * bitmap.width).toInt().coerceIn(minX, bitmap.width - 1)
            val minY = (points.minOf { it.y } * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val maxY = (points.maxOf { it.y } * bitmap.height).toInt().coerceIn(minY, bitmap.height - 1)
            var sum = 0.0; var n = 0
            for (y in minY..maxY step 12) for (x in minX..maxX step 12) {
                val pixel = bitmap.getPixel(x, y)
                sum += ((pixel shr 16 and 255) * .2126 + (pixel shr 8 and 255) * .7152 +
                    (pixel and 255) * .0722) / 255.0
                n++
            }
            if (n > 0) sum / n else null
        }.getOrNull()
        val rawDistance = IrisDistance.estimate(points, bitmap.width, bitmap.height,
            focalPx, focalSource, geometry)
        val distance = if (faceIllumination != null && faceIllumination !in .08..0.95)
            rawDistance.copy(cm = null, quality = 0.0, reason = "face_exposure_unusable")
        // Enhanced pixels carry amplified noise at the iris edge; trust the estimate less.
        else rawDistance.copy(quality = rawDistance.quality * LowLightPolicy.enhancementQuality(enhancement.gain))
        return EyeObservation(capturedAtMs, wallAt, cameraTimestampNs,
            if (geometry == null) "eye_geometry_invalid" else if (geometry.meanU == null) "eye_closed_or_iris_missing" else "observed",
            geometry, gaze, selected, shape, headMatrix, faceIllumination, distance, cameraGeometryId,
            lowLightGain = enhancement.gain, lowLight = lowLight)
    }

    override fun close() { landmarker.close(); gazeTracker.reset() }

    companion object {
        const val LOW_LIGHT_NO_FACE = "low_light_no_face"
        val EYE_INDICES = listOf(33, 133, 160, 159, 158, 144, 145, 153,
            362, 263, 385, 386, 387, 380, 374, 373,
            468, 469, 470, 471, 472, 473, 474, 475, 476, 477,
            70, 63, 105, 66, 107, 336, 296, 334, 293, 300)
        val EYE_SHAPES = setOf("eyeBlinkLeft", "eyeBlinkRight", "eyeSquintLeft", "eyeSquintRight",
            "eyeLookUpLeft", "eyeLookUpRight", "eyeLookDownLeft", "eyeLookDownRight",
            "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft", "eyeLookOutRight",
            "browDownLeft", "browDownRight", "jawOpen")
    }
}
