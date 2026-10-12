# EyeCare 0.10.0-alpha: exposure burden design and verification

## Why the score changed

The previous `efs-hypothesis-0.7` score averaged available feature values inside each domain, then renormalized the domain weights by quality. A missing screen or eye stream could leave a numeric low score based on only environment, posture, and usage. The historical report, score, and version are retained as recorded. The recent 24-point report is not treated as proof of actual eye comfort: the server has no raw screen brightness, display luminance, frame quality, detailed capture coverage, or app identity needed to reproduce the reported game session.

`efs-exposure-0.10` describes **observed screen exposure burden**, not eye health, diagnosis, or predicted symptoms. It is a product hypothesis informed by research. Its coefficients have not been clinically calibrated or validated for an individual phone or user.

## Versioned calculation

- Nominal domain shares stay **screen 26, environment 22, posture 8, usage 18, eyes 26**. Available domains contribute `weight × domain burden / 100`; missing domains are omitted without renormalization. Missing data is not a zero burden observation.
- Screen movement cues (change, changed area, large region, flash, scene, scroll) are correlated. The strongest valid cue represents this family instead of their average or sum. Environment uses the jointly observed low ambient light and relative OS brightness proxy. Thermal status controls measurement availability rather than acting as a second exposure dose. Posture uses the strongest available related cue. Eye burden requires both a blink cue family and an aperture cue family; yawns alone do not count. A valid near-distance estimate can raise the usage domain only, by at most 40 of its 100 internal points. Valid observed active screen time supplies the remaining 60. Continuous session minutes, breaks, and personal baseline differences are not multiplied into the rate a second time.
- The joint dark/relative-brightness and screen-motion condition adds at most **8 percentage points to the per-minute rate**. It is not an extra 8 points on the final score. The ledger integrates the domain rate plus this interaction once over observed active-screen seconds. `display = round(100 × (1 − exp(−burdenMinutes / 40)))`. The 40 burden-minute display scale, cue ramps, interaction limit, and band colors are design parameters, not published clinical thresholds.
- The same-day ledger survives service restart and report creation. Thermal pauses, stopped capture, stale frames, and gaps over five seconds contribute no invented exposure or recovery. The local calendar day is an explicit display window boundary, not a physiological reset. A service crash can lose up to roughly five seconds of unsaved ledger state.
- The exported `dark_stimulus_minutes` diagnostic is the observed joint-condition time **since the previous report**, capped at the schema's ten-minute maximum; it is not the cumulative daily score or a raw screen recording.
- The numerical score requires active capture, at least 30% valid recent screen time, screen plus usage plus environment or eyes, at least 66 nominal weight points of observed domains, and at least one minute of observed active time. Higher confidence also requires eye and environment signals with adequate coverage. When this gate fails, the report retains measured fields and any prior observed burden but withholds a new score and explains uncertainty.

Ambient lux and OS brightness setting are proxies, not measured display luminance or retinal dose. Front camera distance is an uncertain geometric estimate. A selected game type or app name is never used as a bonus and is not uploaded. Research export uses the existing append-only schema and stores only its allowed aggregate fields. The server does not receive the local time integral; the website cannot reconstruct a 0.10 score from one aggregate row. Old and new score versions are displayed separately.

## Research informing the hypothesis

- [Lin et al., 30-minute mobile reading in four room-light/screen-brightness conditions](https://pubmed.ncbi.nlm.nih.gov/41058350/) reported the largest tear-film, blink, and subjective fatigue changes for a bright screen in a dark room. That experiment studied reading in 30 young subjects, not a Galaxy S25+ game.
- [Bettach et al., 20-minute smartphone reading](https://pubmed.ncbi.nlm.nih.gov/33470679/) found reduced tear breakup times after reading. This motivates duration tracking, but does not specify the app's 40-minute display scale.
- [Talens-Estarelles et al., 20-20-20 reminders](https://pubmed.ncbi.nlm.nih.gov/35963776/) found symptom improvement during the intervention, without a matching change in measured ocular surface or tear-film signs. The app therefore does not claim a precise recovery rate from brief breaks.

## Synthetic comparison and tests

Controlled synthetic examples with valid camera and capture cues, no personal health data, and fixed relative brightness:

| Scenario | 5 min old/new | 10 min old/new | 30 min old/new | 60 min old/new |
|---|---:|---:|---:|---:|
| Bright, stable screen | 11 / 3 | 11 / 5 | 12 / 16 | 13 / 29 |
| Bright, changing screen | 20 / 5 | 20 / 10 | 21 / 28 | 22 / 48 |
| Dark, stable screen | 19 / 5 | 19 / 10 | 20 / 27 | 21 / 47 |
| Dark, changing screen | 28 / 8 | 28 / 16 | 29 / 40 | 30 / 64 |

The old column uses frozen 0.7 code, and the new column integrates an artificial full-quality interval. It does not reproduce the user's 24-point report. Tests also cover near distance, missing screen and eye signals, quality loss, redundant proxy inputs, restart and thermal gaps, and tick frequency.

Android unit tests: **93 passed** (`testDebugUnitTest`). Android app, phone release, and instrumented test APK builds: **passed** (`assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`). Android lint: **passed**, zero errors, 27 warnings and one hint. Web syntax and Node tests: **22 passed**. The phone APK is versionCode 21, versionName `0.10.0-alpha`, ARM64/ARMv7, 56,632,667 bytes, SHA-256 `35f3d815db34f8627a98d0c5b5a707a7c40f48491cbb1be8440698bef2d5660c`, and uses the existing debug signing certificate for an in-place alpha update. No physical Galaxy S25+ was available, so on-device camera, display, sleep, thermal, notification, and upload timing have not been checked for this release. No new server schema or RLS policy was changed.
