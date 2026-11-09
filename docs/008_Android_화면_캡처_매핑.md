# PetWorld Android 실제 화면 캡처 기준표

- 저장소: https://github.com/hebi10/PetWorld
- 기준: Pet Journal / Android API 35 Pixel 6 에뮬레이터
- 목적: 0단계 화면 설계 및 1-A~1-E 디자인 가이드와 **실제 앱 화면** 비교
- 출력: GitHub Actions `Android Full UI Screenshot Capture`, artifact `petworld-actual-ui-light-dark`
- 원본: PNG, `screenshots/ui-captures/light/` 및 `screenshots/ui-captures/dark/`
- 검증 원칙: 실제 `UiDevice.takeScreenshot()` 이미지 외 대체 이미지·모의 이미지는 사용하지 않음

## 캡처 매핑

| 기준 ID | 출력 파일명 | 화면 내용 |
|---|---|---|
| S00 | S00-onboarding | 첫 설치 |
| S02 | S02-profile-create | 신규 프로필 |
| S02 | S02-profile-edit | 프로필 수정 |
| S03 | S03-home | 실제 데이터 홈 |
| S04 | S04-pet-switcher | 반려동물 전환 시트 |
| S05 | S05-quick-record-sheet | 5종 빠른 기록 |
| S06 | S06-food-form | 식사 입력 |
| S07 | S07-water-form | 음수 입력 |
| S08 | S08-elimination-form | 배변 입력 |
| S09 | S09-activity-form | 활동 입력 |
| S10 | S10-weight-form | 체중 입력 |
| S11 | S11-record-list | 기록 타임라인 |
| S13 | S13-range-picker | 기록 조회 기간 선택 |
| S13 | S13-type-picker | 기록 유형 선택 |
| S12 | S12-record-detail | 기록 상세 |
| S12 | S12-record-edit | 기존 기록 편집 |
| S33 | S33-record-delete-dialog | 기록 삭제 확인 |
| S18 | S18-health | 건강 홈 |
| S14 | S14-statistics | 통계 홈 |
| S30 | S30-settings | 더보기 설정 |
| S31 | S31-backup-password-dialog | 백업 암호 모달 |
| S31 | S31-restore-file-picker | 시스템 파일 선택기 |
| S16 | S16-profile-manager | 프로필 관리 |
| S33 | S33-profile-delete-dialog | 프로필 삭제 확인 |

각 파일은 `light`, `dark` 2종을 수집합니다. **총 24개 상태 × 2 = 48장**을 목표로 합니다.

## 이후 디자인 비교 기준

1. 각 PNG와 동일한 S번호의 기획/와이어프레임을 나란히 비교
2. 레이아웃 정보 구조, 카드 높이, 콘텐츠 밀도, 하단 내비게이션 일관성
3. 360dp 작은 화면에서 버튼 잘림, 글꼴 확대와 모달 내부 스크롤
4. 시스템 라이트/다크 대비, 상태색, 아이콘 스타일 및 여백
5. 미구현 기획 항목(S19~S29 등)은 구현 화면과 혼동하지 않고 **미구현**으로 분리

## 확인 상태

GitHub Actions #10에서 실제 화면 PNG **44장 pull 완료**, Android UI 계측 테스트 `OK (1 test)` 확인. 산출물 ID `13655568166`에 라이트·다크 PNG 44장과 instrumentation 로그가 저장되었습니다. 최종 검증 스크립트의 pull 대상 경로 판단 오류로 workflow 전체는 실패 처리됐으며, 검증 경로 수정 후 #11을 재실행했습니다. 화면 PNG의 개별 시각 디자인 검수 및 기획 시안과의 비교는 아직 진행하지 않았습니다.
