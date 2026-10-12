# EyeCare 0.4.1-alpha validation

This is a research debug APK, not a clinical fatigue measurement or store release.

## Corrections and scope

- Personal baseline schema 2 keeps an independent valid count, quality sum, and quality-weighted mean for openness, blink rate, continuous use, recent app switches, recent unlocks, interval micro-breaks, and score. A missing or non-finite observation contributes neither zero nor a sample. A comparison needs at least 3 valid observations and quality sum at least 2.4. Version 1 aggregates used a shared report denominator and cannot be repaired reliably; they are ignored and replaced with fresh local aggregates when the first valid 0.4.1 report arrives. Old reports remain local and are not deleted.
- H01-H06 expose differences from established personal averages and their valid sample count and mean quality. Usage time, recent app switches, recent unlocks, and interval micro-breaks are recorded and compared. H01 and H04-H06 enter the usage domain as explicitly estimated rule-based features only when their own baseline is ready. Blink and openness baselines influence only their eye features. No learned habit model or clinical efficacy is claimed.
- S01-S13 and S17 screen RGB metrics have defined availability in reports, including no capture, no frame pair, locked screen, and stopped capture. A reset clears buffered frames so the previous screen cannot masquerade as a current observation. One frame cannot create a zero-change, zero-flash, or zero-scene result. S14-S15 are OS context; Night Light and Extra Dim remain explicitly unavailable. S16 remains an estimated emission proxy.
- F metrics also report a missing reason when camera permission is denied, the screen is off, the session stops, or the camera is unavailable. Cancelling mandatory screen-capture authorization creates a local availability report with no score. Stopping a running session creates a final report with explicit stopped-state availability and no baseline update. Reports with fewer than two contributing domains or three inputs mark the score unavailable; no numeric score is shown.
- High confidence needs multiple domains and inputs plus adequate eye and screen observation. All comparisons carry estimation and quality information. No raw screen or camera images are stored or uploaded.

## Checks

- `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` succeeded offline in the isolated source copy. Unit tests: 38 passed, 0 failures/errors. Lint: 0 errors, 8 warnings. The instrumentation APK compiled; instrumentation tests were not run.
- Real-device installation, permission UI behavior, sensor availability, lock/recovery, camera/face detector quality, 30-minute endurance, thermal and battery cost remain unverified without an attached device.

## Remaining research limits

- Usage Access is required for app switches and unlock events. Optional camera/motion permissions and absent hardware remain missing. Switches/unlocks are sampled from recent usage events; micro-break counts are per report interval. These are coarse patterns, not a learned behavioral model.
- Screen scroll/reading, scene changes, emission, posture/walking and face illumination are proxies. S03 CCT is estimated; S15 Night Light and Extra Dim are unavailable via the supported public settings APIs used here. Absolute gaze/distance, rPPG and redness remain unimplemented. No population or medical validation was performed.
- Rejected screen-capture authorization creates a no-score availability report rather than running a collection session. This UI path compiled and has a unit test for its report model, but Android permission behavior was not exercised on hardware.
