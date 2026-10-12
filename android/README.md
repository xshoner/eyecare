# NeuroLens EyeCare Android

Current version **0.5.3-alpha** uploads research aggregates after explicit consent during report export and supports private, time-limited browser pairing. Its session is protected with Android Keystore; withdrawal stops future uploads without deleting server records. This update restores CSV.gz export for 0.5.x sessions and returns to research consent after an NRS answer. The smartphone APK contains Android ARM libraries. See [0.5.3 validation](VALIDATION_0.5.3.md), the [private dashboard](https://eyecare-one.vercel.app/dashboard.html), and the [score formula](ALGORITHM_0.5.md). The milestone notes below describe earlier releases.

**현재 빌드: 0.5.0-alpha.** 점수는 [EFS 가설 0.5의 지표 비중·산식·상황 보정](ALGORITHM_0.5.md)에 따른 연구용 추정입니다. 5/10분 어두움·밝은 화면 노출과 당일 누적 사용 보정은 검증 전 가설이며, 보정된 거리 측정이 없어 근거리 보정은 아직 적용되지 않습니다. 앱의 개인 연구 데이터는 서버로 자동 업로드되지 않습니다. 연구 사이트는 현재 합성 데이터만 표시하며 인증·RLS 승인 후에만 실제 데이터 연동을 활성화할 수 있습니다. [0.5.0 검증 기록](VALIDATION_0.5.0.md)을 확인하세요.

현재 실험 빌드 `0.5.0-alpha`는 화면 RGB 전체 지표, 이용 가능한 기기·사용 맥락,
눈·깜빡임·하품 후보, 개인 기준선과 품질 가중 통합 EFS를 연결합니다.
실행된 검사와 미검증 항목은 [0.5.0 검증 기록](VALIDATION_0.5.0.md)을 참조하세요.

Android 연구 알파 **0.5.0** 개발 중입니다. 기준 문서는 [EyeCare PASSIVE PRD v0.3](EyeCare_PASSIVE_PRD_v0.3_2026-10-10.md)입니다.

## 지금 실행할 수 있는 흐름

1. 홈에서 **케어 시작**을 눌러 카메라 권한을 선택하고 Android 전체 화면 공유에 동의합니다. 카메라 권한을 거부해도 화면 기반 케어는 시작됩니다.
2. 평소처럼 다른 앱을 사용합니다. 케어는 직접 종료하거나 OS가 서비스를 종료할 때까지 유지됩니다. 화면 공유만 끊기면 앱에서 재개할 수 있습니다.
3. 설정에서 **10~120분** 리포트 주기와 화면 사용 시간/경과 시간 기준을 고릅니다. 기본값은 화면 사용 30분입니다. 5분 이상 화면을 끄면 주기가 초기화됩니다.
4. 정기 알림을 열어 연구용 EFS 점수와 관측 범위를 보고, **NRS 0~10** 응답을 남깁니다. 리포트는 알림을 꺼도 내부에 저장됩니다.
5. 종료된 세션은 JSON으로 내보내거나 로컬 수집기에 직접 보낼 수 있습니다. 내보내기 형식 3에는 정기 리포트, NRS 응답, 화면 켜짐/꺼짐 이벤트가 포함됩니다. 눈 관측 원자료는 별도 CSV.gz 파일로 저장할 수 있습니다.

0.5.0에서는 전면 카메라와 MediaPipe Face Landmarker로 눈 개방도, 홍채의 눈 안 상대 위치, 상대 움직임 안정성을 시범 수집합니다. 화면의 정확한 응시점은 측정하지 않습니다. 현재 EFS에는 **사용 시간 U01과 화면 변화 S06/S08만** 들어갑니다. 눈 지표는 검증 전이라 점수에 반영하지 않으며 신뢰도는 낮음으로 표시합니다. 점수는 검증된 피로 측정값이 아닌 연구용 잠정 점수입니다.

## 빌드

Windows PowerShell에서:

```powershell
.\scripts\bootstrap.ps1
.\build.ps1
```

기본 빌드는 JVM 단위 테스트, debug APK, Android lint를 실행합니다. APK는 `app/build/outputs/apk/debug/app-debug.apk`입니다. 노트북 수집기는 `Start-Collector.cmd`로 시작합니다.

## 구현 상태와 다음 작업

[v0.3 개발 기록](DEVELOPMENT_0.3.md)에 PRD 항목별 구현 상태를 적었습니다. [0.5.0 실기기 검증 항목](VALIDATION_0.5.0.md)은 아직 수행 전입니다. 기존 0.2.1 결과는 [검증 기록](VALIDATION_0.2.1.md)에 보존했습니다.
