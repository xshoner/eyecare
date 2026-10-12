# EyeCare 0.5.1-alpha research upload validation

## Implementation

- Research aggregate export now asks for explicit upload consent, queues the selected report, signs in anonymously without a login screen, and uploads only the aggregate observation.
- The Android Keystore AES-GCM key protects persisted Auth access/refresh tokens, consent state, pseudonym, and pending observations. Rotated refresh tokens are saved immediately. The manifest disables app backup.
- Pending uploads retry when the app resumes or network access returns; report IDs remain stable across retries. A duplicate insert is accepted only after an owner-scoped read confirms the report already exists.
- Settings show upload state and pending count, offer retry and consent withdrawal, and explain that app removal prevents recovery of the anonymous account. Withdrawal stops later uploads and clears the local queue; it updates server consent when online. It does not delete server records.
- Requests use only the publishable key and the authenticated user's bearer token. The payload has no camera or screen original, screen text, or app name. No privileged key is included.

## Local validation

- `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` passed. JVM tests: 45 passed, 0 failed. Lint: 0 errors, 17 warnings.
- APK package `com.neurolens.eyecare`, versionCode 9, versionName `0.5.1-alpha`, minSdk 29, targetSdk 36. `apksigner verify` passed.
- `artifacts/eyecare-0.5.1-alpha-debug.apk`: 99,222,154 bytes; SHA-256 `41A37889F5F88C390BA28EBC1D5F782AC6F6A63340FC4AFAE80899CDFB195217`.
- `adb devices` showed no attached device. Instrumentation tests compiled but were not run; no on-device consent, offline, Keystore, or lifecycle test was possible.

## Live API validation (synthetic only)

- Public Auth settings returned `anonymous_users=true` after the user's Save.
- A synthetic test account and an observation tagged `algorithm_version=synthetic-test-0.5.1` were created. Anonymous signup returned 200, consent insert 201, observation insert 201, owner-scoped read 200, duplicate insert 409, and unauthenticated table read 401. The synthetic observation remains in the database.
- A separate synthetic account verified token refresh 200 with the same owner, consent withdrawal PATCH 204, and a post-withdrawal insert denied with 403. No observation was stored for that second account.
- The app's exact Android network/UI path was not exercised without a device. Live REST checks establish the server contract, not an installed-app end-to-end result.

## Distribution

- Uploaded as a new file to the existing `뉴로렌즈` Drive folder. Readback confirmed name, 99,222,154-byte size, and parent folder ID `1H9Vz7eixy2wz855WxwOwVfBQpJ7avwNQ`.
- Drive file: https://drive.google.com/file/d/1IYelKPvm38RU2jTsjbuvwUnIFaob1Ggi/view?usp=drivesdk
- The prior APK and other folder files were retained; folder permissions were not changed.
