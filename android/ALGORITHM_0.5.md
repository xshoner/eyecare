# EyeCare EFS hypothesis 0.5

This is a transparent research heuristic, not a validated diagnosis, an exclusive invention, or a learned model. A 0–100 score is shown only when at least two domains, three measured feature inputs, and 15% effective evidence weight are present. Missing measurements remain missing, never zero or reassuring evidence.

## Base score

| Domain | Nominal share of 100 | Representative observed inputs |
| --- | ---: | --- |
| Screen stimulus | 20 | RGB blue ratio; frame/area/scene changes; flash rate; estimated scroll; edge density; estimated emission |
| Environment | 10 | ambient-to-screen mismatch; time of day; thermal status; estimated walking |
| Posture/motion | 15 | estimated device tilt/shake; relative head motion |
| Use/habits | 20 | continuous use; recent app switches/unlocks; interval micro-breaks; established personal deviations |
| Eye response | 35 | blink rate/interval; incomplete and long closures; openness; squint; relative gaze movement; yawning only as a weak cue |

For feature `i`, its bounded risk `r_i` is 0–100, with observation quality `q_i` in 0–1. Each domain score is `D_d = Σ(q_i × r_i) / Σq_i`. A domain's observed coverage is `C_d = Σq_i / number_of_defined_features_in_domain`; eye response needs at least three observed features. Effective weight is `W_d = nominal_weight_d × C_d`. The base score is `round(Σ(W_d × D_d) / ΣW_d)`. The UI shows nominal shares, measured domain risks, actual point contributions, and effective evidence weight. The denominator uses only available domains; this does **not** make absent domains safe. Confidence is low unless several domains and substantial eye and screen coverage are observed.

The bounded feature risk ramps in `MvpSignals.kt` are engineering hypotheses. Blue share or screen brightness is not a direct optical dose, and an on-device camera estimate is not a clinical exam.

## Interaction and exposure adjustments

Adjustments are added **after** the base score, with combined cap 12 and final clamp 0–100. Their thresholds and points are unvalidated hypotheses chosen for prospective calibration, not medical cutoffs.

| Scenario | Required evidence and quality | Added points |
| --- | --- | ---: |
| Dark + bright + changing screen | ambient light `<50 lux`, estimated emission `≥0.45`, frame change `≥0.05`; each input quality `≥0.5`; continuous observed exposure | 5–<10 min: +4; ≥10 min: +8 |
| Calibrated near distance | actual calibrated distance `<25 cm` with quality `≥0.75` | 20–<25 cm: +4; <20 cm: +6 |
| High daily screen use | device usage events with Usage Access and quality `≥0.5` | 240–<360 min: +3; 360–<480 min: +6; ≥480 min: +8 |

Daily use is excluded from the base usage-domain feature list so it is not counted twice. Dark + bright is an interaction of already observed screen/environment signals, intentionally capped so correlated inputs do not accumulate without bound. Calibrated distance is currently always missing because the app has no valid distance calibration; **no near-distance points are applied in this build**. Estimated head movement or a generic face size is never presented as centimetres.

The dark scenario counter uses a monotonic clock, requires consecutive high-quality observations, and clears on missing input, screen-off/rest, capture reset, a gap over 90 seconds, or local day change. No exposure is inferred across a gap. Usage totals are recalculated from local-day Android usage events; if Usage Access is absent, its adjustment is absent. Short rest lowers subsequent exposure by resetting the continuous scenario rather than applying a speculative recovery credit.

## Bands and confidence

| Score | In-app sentence |
| ---: | --- |
| 0–24 | 눈 피로도가 양호해요. 건강한 눈을 만들어가요. |
| 25–49 | 눈 피로도는 보통 정도예요. 너무 오래 보지 않기를 권장드려요. |
| 50–74 | 눈이 피곤해지고 있어요. 눈을 쉬게 해주세요. |
| 75–100 | 눈이 너무 피곤해요. 오늘은 그만 쉬어주세요. |

No sentence is shown for an unavailable score. Confidence assesses coverage and quality, not clinical certainty. The correction's version, evidence IDs, points, and rationale are stored in each local report. Historical reports retain their original algorithm versions.

## Validation path and sources

Validate against prospectively collected subjective fatigue ratings, keeping participants and later sessions separate between calibration and held-out evaluation. Report calibration, error by lighting/distance/usage subgroup, missingness and abstention, and battery/thermal costs. Do not automatically retrain or deploy from incoming reports.

Published evidence supports studying screen duration, lighting, and viewing distance, but does not validate this formula or its point thresholds: [Digital eye strain review](https://pmc.ncbi.nlm.nih.gov/articles/PMC9983433/), [ambient brightness and visual comfort experiment](https://pmc.ncbi.nlm.nih.gov/articles/PMC12551090/), [smartphone viewing-distance study](https://pubmed.ncbi.nlm.nih.gov/27716998/). Android usage data requires the user's Usage Access and can be unavailable when a device is locked: [Android UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager).
