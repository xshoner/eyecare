# EyeCare 0.5.0-alpha validation and limits

This is a debug APK for an unvalidated research hypothesis, not a clinical eye-fatigue measurement. The algorithm and exact 100-point formula are in `ALGORITHM_0.5.md`.

## Implemented

- Five domain risks use nominal shares of 20/10/15/20/35, with observation quality and missingness in the effective denominator. Reports retain base score, per-domain contributions, available evidence weight, correction evidence and algorithm version. Historical 0.4.1 reports keep their old version and the prior baseline/missingness corrections remain in the source.
- A consecutive dark/bright/changing-screen tracker can add 4 points after 5 observed minutes or 8 after 10. It clears at rest, screen-off, missing input, time gaps or day rollover. Quality-gated daily usage can add 3/6/8 points. The combined adjustment is capped at 12. A calibrated near-distance rule exists but remains inactive because the app cannot currently measure calibrated centimetres.
- The app shows four score-band sentences, representative domain contributions and evidence quality, a taller trend graph and a highlighted peak interval. An unavailable score stays unavailable.
- A separate, user-initiated research JSON export allows only named aggregate fields. The export dialog describes those fields and lets the user choose the destination. A Supabase HTTPS insert transport is implemented but not connected to the UI or configured with a project URL, key or Auth session in this build. No automatic research upload occurs.
- The public research-site source is a synthetic-data simulator. The proposed schema is kept under `docs/` in the GitHub repository so no migration runner applies it automatically. Auth, RLS, researcher access, retention and withdrawal behavior need review before activation.

## Verification

- Offline `testDebugUnitTest`, `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest` succeeded in both the isolated source copy and `D:\Neurolens2`. JVM tests: **45 passed, 0 failed**. Lint: **0 errors, 9 warnings** in the isolated copy and **0 errors, 17 warnings** in the original path (8 additional dependency/tool-version notices). The Android instrumentation APK compiled; instrumentation tests were not run.
- `aapt` reports package `com.neurolens.eyecare`, versionCode **8**, versionName **0.5.0-alpha**, minSdk **29**, targetSdk **36**. `apksigner verify` accepts the APK using v2 signing.
- Final debug APK from the `D:\Neurolens2` build: `artifacts/eyecare-0.5.0-alpha-debug.apk`, **99,071,584 bytes**, SHA-256 `445a6a98d3f84f51cfcd22033e634cf6b5a1310207fc5fef583bc17cd4802474`. Its signing-certificate SHA-256 matches the 0.4.1 APK. An isolated-path build had different ZIP padding and `classes4.dex` bytes; the source trees matched, and the original-path build is the release candidate.
- Site JavaScript passed `node --check`; local HTTP preview returned 200. Browser-based visual inspection was unavailable in this execution environment.

## Remaining limits

- No attached test device was available for a real installation or permission/sensor/endurance test. The build itself does not establish accuracy, efficacy, clinical calibration or battery cost.
- Brightness/emission, screen changes, posture and several eye features are estimates. Distance in centimetres is explicitly unavailable. Missing sensors and denied permissions must not be treated as zero risk.
- The research export is a local, manually shared file. Do not enable Auth, apply the proposed RLS schema, grant researcher read rights or send real observations until the access model, consent text, deletion/retention policy and synthetic security tests are approved.
