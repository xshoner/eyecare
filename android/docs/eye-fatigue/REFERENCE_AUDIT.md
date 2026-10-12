# EyeCare 눈 피로 알고리즘 참조 자산과 연구 검토

검토일 2026-10-10

**현재 적용 기준:** [자연 관측형 PRD v0.2](../../EyeCare_PASSIVE_PRD_2026-10-10.md). 사용자 정렬·시선 보정 표적·표준 검사 절차는 폐기했다. 아래 표준 과제/보정 연구는 내부 센서 평가 참고이며 소비자 사용 단계로 채택하지 않는다.

현재 화면 자극 분석에 시선·깜빡임·눈꺼풀 감김·rPPG를 결합하는 데 사용할 자산과 근거를 정리한다. 일반 웹캠 기반 눈 행동 측정 연구는 존재한다. 스마트폰 전면 RGB 카메라와 콘텐츠 신호를 결합한 EyeCare의 성능은 별도 벤치마크로 확인한다.

## 기존 앱

작업 기준은 `D:\Neurolens2`의 Android 0.2.1-alpha다. `ExposureEngine.kt`는 디지털 화면 변화와 유효 시간을 계산하고 10초 창별 품질을 기록한다. `ProjectionService.kt`는 최대 2fps의 화면 표본, 사용자 동의, 잠금·중지 처리를 담당한다. `SessionStore.kt`, `ReportExporter.kt`, `collector/aggregate.mjs`는 기록 보존·버전별 내보내기·중복 제거·응답 시각 집계를 제공한다.

카메라 권한과 CameraX·MediaPipe 의존성은 아직 없다. 화면 엔진의 2fps를 눈·맥박 측정에 사용하면 시간 해상도가 부족하다. 별도의 전면 카메라 경로를 추가하고 기존 화면 기록과 연결해야 한다. [실행 범위](../../README.md), [현재 검증](../../VALIDATION_0.2.1.md)

## 뉴로렌즈 자산의 고정 기준

공개 저장소의 `main`을 2026-10-10 확인한 커밋은 **05ee0f80c46d78fdc840c19dc66f0d6717c50377**이다. 참조 파일 8개를 해당 커밋에서 받아 [읽기 전용 감사 자료](../../artifacts/eye-fatigue-design/neurolens-05ee0f8/manifest.json)에 경로·SHA-256을 남겼다. 파일 합계는 약 161KB다.

로컬 `C:\Users\Curioud\Desktop\neurolens-main`은 커밋 `a9121d9d1c6a68cb5af79ef4c77acfdec0d17a97`이며 별도의 미추적 개발 파일이 있다. 이번 이전 설계의 기준은 고정한 공개 커밋이다. 로컬 미추적 `newbiz2-*`, `phone-ppg-*`는 추가 자산 후보로만 기록한다. 특히 접촉식 손가락 PPG는 얼굴 rPPG와 구별해야 한다.

| 파일과 기능 | 재사용 방법 | 이전 전에 바꿀 부분 |
|---|---|---|
| `newbiz-core.js` In_mind core 2.6 | 홍채의 눈 좌표, 머리 자세 특징, 보정·검증 수학, POS와 신호 처리 개념 | JavaScript 수학을 Kotlin으로 이전하고 동일 입력 재생으로 비교. 정서 편향·스트레스 판정 규칙은 피로 모델에 사용하지 않음 |
| `condition-signal.js` | 이마·양 볼 피부색 표본, 눈 영상 품질, 시간 커버리지 | 피부색 필터의 피부톤·조명별 탈락률, YUV 색 변환, 미관측 처리 확인 |
| `condition-fusion.js` | POS·CHROM 후보, ROI 간 합의, 독립 구간 근거, 품질 표시 | 겹치는 창을 독립 근거로 세지 않음. 세션 흐름으로 추정한 값은 실제 맥박 관측과 구별 |
| `condition-camera-worker.js` | 영상과 수치 분석의 분리, 랜드마크용 밝기 보정 | rPPG에는 원래 RGB 사용. Android의 카메라·GPU 경로로 교체 |
| `condition-gaze-model.js` | 모델·특징·해시 고정, 보정 보호, 그림자 실행 | 기존 학습 모델의 모바일 활성화는 별도 평가 필요 |
| `model-registry.json` | 세션별 모델 고정과 승격 기록 | `active=null`, 잔차 MLP는 `shadow-exception`. 기존 모델을 승인된 시선 정확도로 취급하지 않음 |
| `CONDITION_FUSION_BENCHMARK.json` | 합성 신호 회귀 시나리오 | 파일 자체가 사람 영상·기준 센서 없는 합성 회귀임을 명시. 실제 스마트폰 HR 정확도 근거는 별도 |
| `release_policy.json` | 검증·잠금 시험·시간 분리·품질 조건 | EyeCare의 센서와 피로 평가 기준으로 새 정책을 작성. HR 정확도로 IBI·HRV 정확성을 승인하지 않음 |

[고정 공개 소스](https://github.com/jeebs0627/neurolens/tree/05ee0f80c46d78fdc840c19dc66f0d6717c50377)

## 이전할 때 반드시 해결할 문제

1. `faceFeatures()`의 눈별 `q`가 눈 열림값에 의존한다. 이 값을 그대로 검출 품질로 쓰면 눈 감김이 제외돼 폐쇄 시간이 과소 계산된다. 영상·검출 품질과 눈 개방도를 독립적으로 계산한다.
2. 기존 `blinkRate()`는 blendshape 0.5 경계와 50~500ms를 사용한다. 개인 눈 모양·하방 시선·누락에 대한 눈 피로용 검증이 필요하다. 끝이 보이지 않는 이벤트를 확정하지 않는 상태 기계로 확장한다.
3. 시선 커서의 보기 좋은 안정화·경계 제한·목표 근접 처리를 평가용 시선에 적용하면 오차가 작아 보일 수 있다. 원시·보정·표시 좌표를 구별하고 성능 평가는 원시 또는 고정 보정 출력으로 한다.
4. `repairPhase()` 같은 세션 추세 보정과 미래 이웃 창은 변화량을 줄이고 실시간 평가에 미래 정보를 섞을 수 있다. 실시간 피로 입력은 실제 관측 및 인과적인 처리만 채택한다. 사후 분석은 별도 버전으로 표시한다.
5. 웹의 화면 좌표·카메라 미러링·종횡비를 모바일에 그대로 적용하면 오류가 난다. 물리적 좌우, 센서 회전, 화면 좌표계, 정규화 기준을 데이터 계약에서 고정한다.

## 참조 연구와 적용 범위

| 참조 | 확인한 방법 | 설계에 적용 | 적용 한계 |
|---|---|---|---|
| [Sensors 2022 눈깜빡임 연구](https://www.mdpi.com/1424-8220/22/3/916), DOI 10.3390/s22030916. [열람한 논문 PDF](https://pdfs.semanticscholar.org/67bf/492b73382700cb48c6f5e08f60ec66b4f5fc.pdf) | 웹캠 60fps로 120분 VDT 시청 중 눈깜빡임·불완전 깜빡임 특징과 응답을 관찰 | 눈 이벤트 정의·수동 라벨·시청 전후 반복 평가의 참조 | 해당 카메라·조건의 결과이며 스마트폰 피로 판정 경계값을 제공하지 않음 |
| [Saxena 등 웹캠 시선 연구](https://link.springer.com/article/10.3758/s13428-023-02190-6), 2023 공개·2024 권호 | 고정 응시·구역 분류·자유 보기·추적·깜빡임 과제로 일반 웹캠 모델 평가 | 표준 과제와 검출 벤치마크 구성 | 특정 모델·보정·노트북 성능을 모바일 정확도로 전용하지 않음 |
| [EyeFatigue Tracker, JMIR 2026](https://humanfactors.jmir.org/2026/1/e84479), DOI 10.2196/84479 | 38명, 적외선 양안 장비, 1시간 게임 후 CVS-Q 변화와 눈 특징으로 악화 분류 | 눈꺼풀·깜빡임·시선 특징을 함께 평가하는 연구 방향 | 일반 RGB 웹캠이 아님. 작은 표본·교차검증 밖 특징 선별의 편향을 저자도 지적하므로 해당 성능을 EyeCare 목표 성능으로 복사하지 않음 |
| [읽기와 깜빡임 연구 2023](https://pubmed.ncbi.nlm.nih.gov/36763349/), DOI 10.1167/iovs.64.2.14 | 읽기 중 깜빡임 감소를 확인했지만 눈 표면 증상과 해당 깜빡임 지표 사이 연관성은 확인되지 않음 | 과제·집중도·증상을 구별하고 개인 기준선과 여러 특징을 함께 평가 | 깜빡임 감소만으로 피로를 확정할 근거가 되지 않음 |
| [rPPG-Toolbox 논문](https://arxiv.org/abs/2210.00716), [공식 저장소](https://github.com/ubicomplab/rPPG-Toolbox) | 공개 데이터와 여러 rPPG 방법의 평가 도구 | POS·CHROM 기준 방법, 공개 데이터 재생과 교차 데이터 평가 | 공개 맥박 데이터에는 EyeCare의 눈 피로 정답이 없음. 데이터·모델별 사용 조건 확인 필요 |
| [CVS-Q 개발 연구](https://pubmed.ncbi.nlm.nih.gov/25744132/) | 컴퓨터 사용 관련 증상 설문 개발·검증 | 장기/반복 증상 평가 도구 후보 | 한국어 타당화·사용 허락·회상 기간을 확인한 뒤 채택. 짧은 세션의 매분 정답으로 자동 사용하지 않음 |

PERCLOS의 출발점은 졸림 평가다. [NHTSA 원 보고서](https://www.nhtsa.gov/sites/nhtsa.dot.gov/files/drowsydr.pdf). EyeCare에서는 상대 눈 개방도로 계산한 폐쇄 비율을 별도 특징으로 명명하고, 졸림 응답을 함께 기록해 눈 피로와 구별한다.

## 플랫폼 선택의 근거

- [MediaPipe Android Face Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android)는 랜드마크와 표정 계수를 제공한다. 시선 좌표·동공 직경·피로 점수는 추가 모델과 검증이 필요하다.
- [CameraX 분석](https://developer.android.com/media/camera/camerax/analyze)은 프레임 처리와 역압 제어를 제공한다. 최신 프레임만 처리하면 누락이 생길 수 있으므로 실제 표본 간격을 기록한다.
- [Camera2 타임스탬프](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE)는 기기별 시간 기준을 확인해야 한다. 카메라 센서 시각을 무조건 `elapsedRealtime`과 동일하다고 가정하지 않는다.
- [Android camera 전경 서비스](https://developer.android.com/develop/background-work/services/fgs/service-types#camera)는 앱이 보이는 상태에서 적법하게 시작한 카메라를 다른 앱 사용 중 이어가는 경로를 제공한다. 시작 조건·카메라 경쟁·OEM·스토어 적합성 검증이 필요하다. 다른 앱 시청 중 카메라 사용을 기술적으로 불가능하다고 단정하지 않는다.
- [MediaProjection](https://developer.android.com/media/grow/media-projection)은 세션마다 동의가 필요하고 잠금 시 중단된다. 화면과 카메라의 독립 동의·중단·부분 결과를 설계한다.

참조의 목적은 이미 구현된 수학·품질관리를 재사용하는 것이다. 기존 시선 보정 절차를 이식하지 않는다. 자연 사용의 피로 목표·스마트폰 조건·자체 라벨·누락과 자세/조도 보정은 새로 정의한다.
