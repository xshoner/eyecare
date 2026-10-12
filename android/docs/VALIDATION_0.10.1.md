# EyeCare 0.10.1-alpha: observed-context patch

This patch leaves `efs-exposure-0.10`, its numeric score gate, research export contract, server schema, and all previously saved report versions unchanged. The app version is 0.10.1-alpha, versionCode 22, signed with the existing alpha debug certificate for in-place updates.

When the composite score is withheld, the live score card, home summary, report list, and report detail now foreground the **measured context**. The display distinguishes measured ambient lux, continuous screen-on usage minutes, the estimated environment-domain burden when its joint lux/relative-brightness evidence is valid, screen/eye measurement gaps, and any earlier same-day valid-frame cumulative burden. It never substitutes a partial context measurement for the composite score. A missing accumulated value is described as unmeasured, never zero, recovery, or a good result. Existing 0.10 reports can use the same display fields after upgrading; earlier score versions remain historical.

The synthetic 1 lux / 56.44-minute case with no screen or eye stream now reads as observed dark context and 56 minutes, shows the valid environment estimate separately, and says the composite score is withheld. If 24.5 valid screen minutes had already accumulated, their 38/100 burden remains visible as partial observation after capture loss. If no frames were valid, the accumulated value remains unavailable. A missing lux sensor is not turned into a dark or zero-lux claim.

The cumulative label is **"today's observed screen-use burden"**. Calendar rollover is an explicit display window boundary, not evidence of physiological recovery. The website's slider remains a separate virtual domain-average what-if; it cannot edit or reconstruct the phone's time-integrated score.

The synthetic 1-lux case is a test fixture, not a reproduction of the user's real 24-point report. The actual source report lacks the raw screen and camera evidence needed for that reproduction. No physical Android device was connected for interactive UI or on-device upload verification.

## Verification

- `testDebugUnitTest`: **97 passed**, including three new observed-context cases; zero failures.
- `assembleDebugAndroidTest`, `assembleRelease`, `lintDebug`: passed. Lint reported zero errors and 27 warnings.
- New phone APK: 56,649,051 bytes; SHA-256 `988dc995626bafd48df8a2c9739d4186f8be50c58eed6d4365e1dc19609162b3`; ARM64/ARMv7; existing signing certificate SHA-256 `095f122ea84fab93bf0d37780b822e3d437b4cbe44b5721a78460398d01791f0`.
