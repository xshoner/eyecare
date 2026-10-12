# EyeCare 0.5.3-alpha export regression review — 2026-10-10

## Code findings and fixes

- The raw eye-data CSV.gz button was gated to sessions from 0.3.1 and 0.4, so it was absent from 0.5.x session details even though the capture service still writes eye observations. The gate now accepts all supported versions from 0.3.1 onward. A two-case unit regression test covers old, current, and future versions. If a session has no saved eye observations, the exporter still reports that condition rather than creating an empty CSV.
- Selecting **응답 후 내보내기** opened the NRS report dialog, then stopped the export flow after an answer. The app now returns to the research-export consent dialog with the saved answer. Dismissal cancels the pending export; the explicit no-answer option remains available.
- The research export path awaited network retry before showing Android's share chooser. It now persists the aggregate file and encrypted upload queue first, opens the chooser immediately, and retries Supabase on an IO coroutine. Network errors remain in the visible upload status; chooser-launch failure has a separate error.
- CSV save-picker launch failure now surfaces an app error instead of escaping from a success callback.

## Checked paths

- General JSON: report button to completed-session selection, JSON serialization, cache file, FileProvider read-only content URI, share chooser or Android CreateDocument copy. Existing Android instrumentation tests cover JSON and its URI but could not be run here.
- CSV.gz: button eligibility, saved eye-row query, gzip/CSV serialization, Android CreateDocument copy. Camera permission or missing eye samples can legitimately leave no CSV data; the exporter gives a visible error.
- Research: report version, NRS answer/skip/cancel, explicit consent, local aggregate serialization, Keystore-backed outbox, offline retry, Auth refresh, owner consent POST/PATCH contract, observation INSERT, and duplicate 409 readback. No server schema or RLS was changed.

## Verification

- `testDebugUnitTest`: 47 tests, 0 failures (two added for CSV button eligibility).
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: passed; 0 lint errors.
- The approved parent-side gateway review saw successful reads/pairing but no research consent or observation write request in the reported period. This is consistent with a client-side flow interruption but does not prove which action the user took.
- No Android device is attached. The available emulator is x86_64 while the phone APK has ARM ABIs; UI interaction and real-device reproduction remain untested.

## Artifact

- Android package: `com.neurolens.eyecare`; versionCode 11; versionName `0.5.3-alpha`.
- Phone APK: `artifacts/eyecare-0.5.3-alpha-phone-debug.apk`, 63,514,266 bytes, Android ARM ABIs. APK v2 signature verified against the same signer certificate as 0.5.2.
- SHA-256: `04A28B48F1982D0A79954612EBC188F40417BC5C59E4B7525A608BDEEE01DE56`.

No actual user health record, token, or existing app database was read, deleted, or reset during this review.
