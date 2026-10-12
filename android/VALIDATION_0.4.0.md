# EyeCare 0.4.0-alpha MVP verification

This is a research debug build, not a clinically validated fatigue measure or a store release.

## Implemented

- S01–S17 screen RGB feature family: luminance, percentiles and contrast, RGB/blue share and estimated CCT, saturation, dark UI, frame and area change, slow brightness jumps, scene changes, estimated vertical scroll and reading pattern, display state/settings, emission proxy, and quality/exclusion accounting. Night Light and Extra Dim are explicitly unavailable through supported public settings APIs on this build. Screen pixels are analyzed in memory and discarded.
- Available E/M/U signals: ambient light, proximity, time, battery/thermal state, active playback count and volume, gravity tilt and accelerometer motion, step events with runtime permission, screen rotation, usage time/switches/unlocks where Usage Access is granted, rest patterns and report responses. Missing permissions, absent hardware and unsupported notification/accessibility/media-session signals are recorded with reasons.
- F signals: face/eye observation coverage, candidate blink events and intervals/duration/incomplete closures, openness and closure ratio, squint, candidate prolonged jaw opening (yawn), relative head motion, relative iris movement, asymmetry, face brightness proxy, and available MediaPipe blendshapes. Yawn alone cannot create an eye-fatigue domain. Absolute screen gaze and distance are unavailable without calibration; rPPG/redness are not in this build. No source face video is saved.
- Five-domain research heuristic EFS v0.4 uses available screen, environment, posture, usage and eye measurements with quality weights. Missing values are null with reasons, not zero. Derived quantities carry an `estimated` flag. The report retains each value, quality, reason, and whether it was estimated. Local personal aggregates for eye openness, blink rate, usage and score inform later reports; the UI shows how many baseline reports exist. Per-minute scores and inputs are stored as local events.
- Start requires an explanatory consent screen followed by Android activity, camera and screen capture permission flows. Denied optional permissions leave explicit missing signals. The foreground notification offers Stop. Local data can be deleted from the app, including the baseline. Existing explicit export remains user initiated.

## Checks performed

- `testDebugUnitTest`: 29 tests, 0 failures, 0 errors. New cases cover missing/low-quality scores, yawn limits, baseline, blink/yawn gaps, RGB features and short screen-off rest accounting. Existing exposure and scheduler tests cover visibility reset, missing time, duplicate/backward timestamps and long rest.
- `lintDebug` in `D:\Neurolens2`: 0 errors, 16 warnings (dependency/version recommendations, Kotlin extension suggestions, one SDK check). `assembleDebug` and `assembleDebugAndroidTest`: succeeded for application ID `com.neurolens.eyecare`, version code 6, version name `0.4.0-alpha`, debug variant. The instrumentation APK compiled but its tests were not executed.
- `adb devices -l`: no attached devices. Installation, camera and sensor behavior, lock/recovery, 30-minute operation, thermal/battery cost and hardware-specific Android permission behavior remain **unverified**.

## Known research limits

- Screen scrolling, reading likelihood, emitted brightness, posture, walking and face brightness are proxies with an `estimated` flag. A dark protected capture is retained at low quality rather than claimed as a normal scene.
- Foreground app identity/categories are not retained; app switch counts require Usage Access. Notification interruptions and media-session detail require additional privileged/opt-in access and remain missing. Distance requires camera calibration. Detecting blinking, incomplete blinks and yawns needs manual labels and device testing.
- Baselines need repeated local reports and are not population norms. The EFS anchors and weights have not been validated against NRS or clinical outcomes. Low coverage lowers weight and confidence. The score must be shown as a research estimate.
