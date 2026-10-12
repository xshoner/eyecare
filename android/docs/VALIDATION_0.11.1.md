# 0.11.1-alpha Android input-validity patch

## Scope and algorithm identity

This patch follows the already-delivered 0.11.0-alpha APK. App versionCode 24 and versionName 0.11.1-alpha prevent overwriting that APK. The weighted formula and research `algorithm_version` remain `efs-exposure-0.11`; this patch changes which live inputs can support it. No Supabase schema or web files were changed.

## Validity rules

- Android sensor reporting mode is read from the successfully registered sensor. An on-change light or proximity value is current after its first valid event in that registration epoch, even if unchanged for minutes. Screen off, unregister, a failed registration, unreliable accuracy, an invalid sample, or an event timestamp from before the current registration removes it. A new epoch needs a new valid event. Continuous gravity and acceleration still expire after 10 seconds. Sensor timestamps are compared with `elapsedRealtimeNanos`; callback arrival does not freshen queued samples. Zero lux remains valid; nonfinite and negative lux do not.
- A successful screen `ImageReader` callback alone no longer refreshes the screen sample. The timestamp advances only after pixels decode and `ScreenSignalEngine.frame` accepts them. Null images, stale readers, invalid dimensions, exceptions, and old generations do not. Duplicate visibility callbacks no longer reset valid frame pairs. A static screen with no new frame is explicitly missing rather than claimed to be a measured zero-change screen.
- A fresh camera callback without fresh eye geometry cannot revive older eye measurements. A frame with no eye geometry is distinct from a stale valid eye sample. Eye and distance rolling state clears when camera observation stops or thermal/screen pauses occur. Distance freshness uses the latest **valid** distance, not the latest failed attempt.
- Stopping the service now snapshots the final report before setting the stop gate that suppresses screen and eye signals. Flush still happens before the report. A genuinely empty, sparse, protected, or thermally paused window remains explicitly missing.
- The current weighted contribution can fall when a domain disappears; the UI calls it a partial estimate and says this is not recovery. Today's separately stored observed burden never decreases within a local day. Partial reports are excluded from trend/personal calibration; score baseline learning also uses the comparison gate.

## Limits

Android's on-change semantics support holding a stable value while its listener is active, but a sensor that silently fails without reporting an accuracy change cannot be distinguished from an unchanged environment in software. Re-registration after screen off forces a new initial event. MediaProjection does not guarantee a callback on every static interval, so absent recent successful frames remain unknown. No physical phone was available to measure actual sensor cadence or exercise an end-to-end in-app upload; unit tests and APK build do not replace this check. Existing 0.11.0 and older APKs and records remain preserved.

## Local validation (2026-10-12)

- `testDebugUnitTest`: 105 tests in 27 suites; 0 failures, 0 errors, 0 skipped. Includes registration epoch, stable on-change, continuous expiry, screen-frame acceptance, eye geometry, and partial score regression checks.
- `assembleRelease` and `assembleDebugAndroidTest`: successful. `lintDebug`: successful, 0 errors, 27 warnings, 1 hint. The warnings concern dependency versions, API/KT extensions, and existing static-analysis suggestions; no fatal issue was reported.
- Release APK package `com.neurolens.eyecare`, versionCode 24, versionName `0.11.1-alpha`, arm64-v8a and armeabi-v7a. APK signature verification succeeded with the same Android debug signing certificate used by prior alpha builds (SHA-256 certificate digest `095f122ea84fab93bf0d37780b822e3d437b4cbe44b5721a78460398d01791f0`).
- This was a local JVM/build validation. No device or emulator was available for an instrumented test run, sensor cadence measurement, or the in-app Supabase upload path. No real health data was sent.
