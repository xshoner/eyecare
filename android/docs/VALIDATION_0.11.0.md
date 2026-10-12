# 0.11.0-alpha: partial evidence and observed burden

## Reason for the revision

The 0.10 gate withheld the entire score unless fresh capture covered at least 30% of a window, screen and usage domains were both present, another domain was present, and available weight was at least 66. A parent-verified report with current environment, posture and usage evidence was therefore marked unavailable. The original report and 0.10 algorithm version remain unchanged.

## 0.11 calculation

- Fixed nominal domain weights remain screen 26, environment 22, posture 8, usage 18, eyes 26. A current domain contributes `weight * domainRisk / 100` points. Missing domains have no term and the available weights are **never normalized to 100**. A number with one valid domain is explicitly a partial estimate, not a complete exposure estimate or reassurance.
- Current report `score` is the observed domain contribution plus the bounded dark-screen interaction. It is **not** the daily cumulative burden. The latter remains in the local `observedBurden` field and on the phone UI. A 0.10 score has different semantics and must not be directly compared with 0.11.
- Ambient lux alone can contribute its conservative 0–50 environment cue when relative brightness is unavailable. It is superseded by the jointly observed lux/brightness cue, never added again. Usage time alone contributes 60/100 within the 18-point usage domain; a valid near-distance cue supplies up to the remaining 40/100. One valid blink or aperture cue can contribute an eye estimate. A yawn alone cannot.
- The daily local ledger integrates fresh, valid, screen-on input for observed intervals only. It pauses for screen off, thermal pause, missing input, and stalled worker gaps over 5 seconds. It is persisted across service restarts and never decreases within a local day. A local day change starts a new display period, not physiological recovery.
- Live scoring refreshes every 10 seconds. Screen frames, light/gravity/acceleration, eye frames and distance samples expire after 10 seconds. Stale signals are missing, not current.
- High confidence and comparative trend eligibility still require strong multi-domain evidence; partial scores remain visible but do not enter trend, personal calibration, or NRS paired validation.

## Validation

Android unit tests cover all 31 nonempty combinations of five domains, empty and stale inputs, fixed weights, no double counting of correlated cues, monotone ledger behavior, capture loss, restarts, and time integration. Web tests check that one-domain partial rows are retained as data but excluded from paired trend analysis. The coefficients and bands remain product hypotheses, not medical thresholds.

There was no physical phone available for device sensor, thermal, or in-app download testing. Existing user records were not modified. Supabase schema and permissions were not changed for this release.

### Release checks (2026-10-12 UTC)

- Android `testDebugUnitTest`: 99 passed, 0 failed. `assembleRelease`, `assembleDebugAndroidTest`, and `lintDebug` succeeded. Lint reported 0 errors and 27 warnings.
- APK: `eyecare-0.11.0-alpha-phone.apk`, 56,649,051 bytes, versionCode 23, versionName `0.11.0-alpha`, arm64-v8a and armeabi-v7a. `apksigner verify` returned 0 and the signing certificate SHA-256 matched the preceding release (`095f122ea84fab93bf0d37780b822e3d437b4cbe44b5721a78460398d01791f0`). File SHA-256: `8a89badcd41e7d74aa565832e92d5c7dafe3ba4a1fd1f9751398801c330f4e77`.
- Drive upload: `https://drive.google.com/file/d/1gaaGo1MUDj_SgY9fU80B3y-XAGni5-77/view?usp=drivesdk`. Metadata readback confirmed name, size, and parent folder `1H9Vz7eixy2wz855WxwOwVfBQpJ7avwNQ`. Older APKs remained listed.
- Web syntax check and 23 tests passed locally. GitHub publishing was blocked by automatic approval review pending explicit approval of external source/manifest egress. Local commit: `6d8c824`.
- Synthetic-only Supabase API check succeeded: anonymous signup 200, owner consent insert 201, observation insert 201, duplicate 409 with owner confirmation, unauthenticated read 401, other owner returned zero rows, and token refresh 200 with owner unchanged. No personal health data or auth tokens were printed. The test row is marked `synthetic-test-0.5.4` and was retained.
