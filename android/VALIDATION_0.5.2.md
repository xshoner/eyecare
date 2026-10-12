# EyeCare 0.5.2-alpha validation — 2026-10-10

## Delivered

- Android: private browser pairing by QR or manual Auth UUID, explicit grant approval, 30-day maximum grant, replace/revoke with offline revoke retry, optional NRS before immutable export, and visible research upload state. No QR token or raw camera/screen content is uploaded.
- Static site: public synthetic simulator plus an anonymous, grant-scoped private dashboard at https://eyecare-one.vercel.app/dashboard.html. It plots score over time, a peak, approximate quality-weighted domain contributions, same-report score/NRS pairs, and quality/version/missingness. Tagged synthetic observations are isolated from real summaries.
- GitHub source: https://github.com/xshoner/eyecare/commit/413fe0c035381027fb9ce00216c2953064ceaf51. Vercel served all five new dashboard assets with HTTP 200 and byte-for-byte SHA-256 matches to committed local files.

## Verification

- Android `testDebugUnitTest`: 45 tests, 0 failures.
- Android `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: passed; 0 lint errors (19 non-error issues).
- Web `npm run check`: passed. `npm test`: 3 tests, 0 failures. `git diff --check`: passed.
- Synthetic-only Supabase API test: anonymous owner/browser/other identities; observation insert 201; browser sees 0 rows before pairing, 1 after; other browser sees 0; browser observation write denied 403; grant expiry beyond 30 days denied 403; after revoke browser sees 0. The tagged synthetic observation remains in the database.
- Supabase Auth anonymous signup succeeded in that test. The browser-grant migration and 18 rollback security checks were completed by the parent task.

## APK

- Phone APK: `artifacts/eyecare-0.5.2-alpha-phone-debug.apk`, 63,514,266 bytes, versionCode 10, versionName 0.5.2-alpha.
- SHA-256: `B37A1A8F70733F0902EB2A4860A25FF511D4F32647C12536B66CC6DD9036A0BD`. APK v2 signature verified. Native ABIs: `arm64-v8a`, `armeabi-v7a`. The earlier universal 0.5.2 build was kept locally; earlier APKs were preserved.
- Drive: https://drive.google.com/file/d/1XU6fPGf9qHx7eRNSIBJ9M_X4zD5rEoK-/view?usp=drivesdk. Metadata readback confirmed name, 63,514,266-byte size, and parent folder `1H9Vz7eixy2wz855WxwOwVfBQpJ7avwNQ`. The folder permissions were not changed.

## Not run

- No Android device was attached (`adb devices` empty), so real QR scan, Play services scanner download, app install, and end-to-end phone UI were not exercised.
- No browser was available to the computer-use tool, so visual browser interaction was not run. Live static asset deployment and API/RLS flows were verified independently.
- Instrumentation tests were compiled into an APK but could not run without a device.

Uninstalling the app loses its anonymous identity and therefore recovery/revocation ability for that owner's rows. Withdrawal stops future uploads and does not permanently delete rows already stored. An offline browser revocation takes effect server-side after a successful retry.
