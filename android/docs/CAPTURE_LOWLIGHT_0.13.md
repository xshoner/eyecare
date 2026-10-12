# 저조도 눈 측정과 카메라 조건 기록 (0.13 캡처 계층)

## 왜 바꿨나

10/12 폰 내보내기에서 문제 세 가지가 보였습니다.
- 0.3~0.7의 카메라 처리는 8~13fps였고, 깜빡임 중앙값은 분당 1.2회로 생리적으로 비현실적이었습니다.
- 밤 세션(조도 0~1 lx)에서 눈 검출률이 43%에서 0%로 떨어졌습니다.
- 조도는 오후에도 1 lx로 기록됐습니다.

이번 변경은 어두운 곳에서도 얼굴을 찾을 수 있게 하고, 카메라 조건을 따로 기록해 이런 현상을 나중에 검증할 수 있게 하는 것입니다. **점수 산식(`efs-exposure-0.11`)과 가중치는 바꾸지 않았습니다.** 새 값은 기록·내보내기에만 쓰입니다.

## 변경 내용

| 항목 | 내용 | 위치 |
|---|---|---|
| AE 목표 fps 범위 | 하한이 15fps 이상인 범위 중에서 상한 ≤30을 우선하고, 그다음 높은 상한, 그다음 낮은 하한을 고릅니다(예: [15,30]). 낮은 하한은 어두울 때 노출을 길게 줄 여지를 줍니다. 15fps 이상 범위가 없으면 하한이 가장 높은 범위를 씁니다. | `LowLightPolicy.chooseFpsRange` |
| 노출 보정 | 원본 Y 중앙값이 55 미만으로 1초 이어지면 +1EV 올립니다(장치 최대까지). 120 초과가 2초 이어지면 −1EV 내립니다(기본값 0 아래로는 내리지 않음). 변경 간격은 최소 1.5초이고, 값이 바뀔 때만 반복 요청을 다시 보냅니다. | `ExposureCompensator` |
| 디지털 보정 | Y를 8픽셀 간격으로 샘플링해 중앙값을 구합니다. 중앙값이 70 미만이면 MediaPipe에 넣기 전에 Y에 이득(중앙값을 약 100/255로 올리는 값, 최대 4배)을 적용합니다. 이득이 2배 이상이면 감마 0.85, 중앙값 25 미만이면 흑백(색 잡음 제거)으로 처리합니다. | `LowLightPolicy.enhancementFor / lut` |
| 품질 감산 | 보정된 프레임의 랜드마크는 잡음이 커서 품질을 낮춥니다. 이득 1.5배까지는 1.0, 4배면 0.5입니다. 눈 지표의 품질과 거리 추정 품질에 곱합니다. | `LowLightPolicy.enhancementQuality` |
| MediaPipe 임계값 | 얼굴 검출·존재 신뢰도를 0.5에서 0.4로 낮췄습니다. 추적 신뢰도는 0.5 그대로입니다. 어두운 얼굴은 검출 신뢰도부터 떨어지기 때문입니다. 잘못된 랜드마크는 기존 눈 기하 검증과 품질 감산이 걸러냅니다. | `FaceEyeExtractor` |
| YUV→ARGB 변환 | 플레인을 ByteArray로 한 번에 복사하도록 바꿨습니다. 기존에는 픽셀마다 `ByteBuffer.get`을 불렀습니다. 회전 처리(`FrameRotation`)는 그대로입니다. | `FrontCameraSource.toBitmap` |
| 프레임 시각 | 카메라가 REALTIME 타임스탬프 소스이고 값이 콜백보다 0~1초 앞서면 센서 노출 시작 시각을 씁니다. 처리 지연이 깜빡임 길이에 섞이지 않게 하려는 것이고, 조건에 맞지 않으면 콜백 시각을 씁니다. MediaPipe에 넘기는 시각은 항상 증가하도록 보장합니다. | `CameraClock.frameTimeMs` |
| 노출 메타데이터 | CaptureResult의 노출 시간, ISO, 디지털 부스트, 조리개로 EV100 = log2(N²/t) − log2(ISO_eff/100)을 계산합니다. 조도 센서 값과 대조하는 용도입니다. | `LowLightPolicy.ev100` |
| 저조도 사유 | 원본 Y 중앙값이 40 미만인데 얼굴을 못 찾으면 상태를 `low_light_no_face`로 둡니다. 창의 절반 이상이 저조도이면 눈 지표 결측 사유를 `low_light_face_not_found`로 기록합니다. 코칭 코드 `LOW_LIGHT`를 추가했습니다. | `FaceEyeExtractor`, `EyeSignalEngine`, `QualityGuide` |

## 새 신호 (점수 미반영, mvpSignals·CSV·JSON에 자동 포함)

| ID | 의미 | 단위 | 결측 사유 |
|---|---|---|---|
| C01.cameraFps | 프레임 간격 중앙값 기준 전달 fps | fps | `insufficient_camera_frames`, 카메라 상태 사유 |
| C02.lowLightGain | 적용한 디지털 이득의 중앙값 | 배(1 = 없음) | `insufficient_camera_frames` |
| C03.enhancedShare | 보정된 프레임 비율 | 0–1 | `insufficient_camera_frames` |
| C04.cameraEv100 | 카메라 노출 기반 장면 밝기 중앙값(추정, 품질 0.6) | EV100 | `camera_exposure_metadata_unavailable` |
| C05.lowLightShare | 원본 중앙값이 40/255 미만인 프레임 비율 | 0–1 | `raw_luma_unavailable` |
| C06.rawLumaP50 | 보정 전 Y 중앙값의 중앙값 | 0–1 | `raw_luma_unavailable` |
| C07.aeTargetFpsMin | 요청한 AE fps 범위의 하한 | fps | `fps_range_unavailable` |
| E01.maxRangeLux | 기본 조도 센서의 최대 측정 범위 | lx | `sensor_absent_or_inactive` |
| E01.sensorCount | 기기의 조도 센서 수 | 개 | — |

- C 신호는 눈 기하를 얻지 못한 창에서도 기록됩니다. 카메라가 꺼지거나 열로 멈추거나 권한이 없으면 해당 사유로 결측이 되고, 0으로 채우지 않습니다.
- 화면 쪽은 `EyeLive.lowLight`, `lowLightGain`, `rawLumaP50`, `cameraEv100`으로 측정 중 저조도 상태를 읽을 수 있습니다.

## 조도 센서 점검 결과

- 코드상 버그는 찾지 못했습니다. `values[0]`(lx)를 읽고, 음수와 비정상 값은 제외하며, 등록 이전 이벤트는 버립니다.
- 0~1 lx가 실제 어두운 환경인지, 디스플레이 아래 센서의 판독 특성인지는 코드만으로 판단할 수 없습니다.
- 그래서 C04(카메라 EV)와 E01 메타데이터를 함께 기록해 대조하게 했습니다. 실내 조명에서 lux가 1인데 EV가 높게 나오면 센서 쪽을 의심할 수 있습니다.

## 검증

- JVM 단위 테스트 128건, 실패 0건입니다. 이번에 추가한 테스트는 15건입니다.
  - `LowLightPolicyTest` 10건: fps 범위, 휘도 통계, 보정·LUT, 품질 감산, EV, 시각 선택, 노출 보정 히스테리시스
  - `EyeSignalLowLightTest` 4건
  - `QualityGuideTest` 1건
- `assembleDebug`와 `lintDebug` 성공. lint 오류 0건, 경고 27건(기존과 같음)입니다.
- **실기기 검증은 전혀 하지 않았습니다.** 아래 항목은 모두 확인이 필요합니다.
  - 실제 fps 범위 선택과 전달 fps
  - 노출 보정의 체감 효과와 진동 여부
  - 보정 이미지에서 MediaPipe 검출률
  - 낮아진 신뢰도 임계값에서 잘못 잡힌 랜드마크 비율
  - EV 값의 타당성
  - 처리 시간과 발열

## 하지 않은 것

- **화면을 조명으로 쓰는 기능:** 사용자 화면 내용을 바꾸므로 넣지 않았습니다.
- **카메라 EV로 lux 추정값 만들기:** 어두운 곳에서는 얼굴이 화면 빛을 받아 주변 조도와 다르므로 EV만 기록했습니다.
- **눈 원자료 CSV.gz에 새 필드 추가:** 넣지 않았습니다. 보고서 단위 C 신호로 기록합니다.
