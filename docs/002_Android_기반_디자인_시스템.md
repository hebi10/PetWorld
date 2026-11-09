# PetWorld 1-A | Android 프로젝트 기반 및 공통 디자인 시스템
상태: 1-A 구현 초안 · 2026-11-09 · 대상: feature/1a-android-foundation

## 범위
- AGP 9.3.1, Gradle 9.5.0, Kotlin/Compose Compiler 2.4.20, Compose BOM 2026.08.00, compileSdk 37, targetSdk 36, minSdk 26, JDK 17.
- Android 네이티브 단일 `app` 모듈을 먼저 구성. 패키지/applicationId는 초기값 `com.haebi.petworld` (Play 등록 전 충돌 여부 확인 및 확정 필요).
- 빌드·테스트·Lint 자동화, 앱 진입 Activity, 공통 디자인 토큰, 하단 5탭 내비게이션 껍데기, 설정에서 테마 선택 및 DataStore 영속화.
- 기록/프로필/진료/서버/AI 기능은 아직 구현하지 않는다. 실제 데이터가 없는 상태를 가짜 기록 수치로 채우지 않는다.

## 디자인 콘셉트: Pet Journal / Neutral Utility
- 사용성 우선의 조용한 반려동물 생활 다이어리. 사진·기록이 주인공. 현 단계는 로고/앱 아이콘 최종 디자인이 아니므로 단색 임시 P 마크 사용.
- 라이트: #FFFFFF 배경, #191919 본문/주요 액션, #F3F3F3 구분 화면, #CECECE 경계.
- 다크: #101010 배경, #181818 표면, #F2F2F2 본문/주요 액션, #292929 구분 화면, #525252 경계.
- 의미 색은 오류 등 상태에 한해 제한적으로 사용. 테마는 동적 Material You 팔레트를 사용하지 않고 제품의 중성 색상으로 고정.
- 모서리 반경 0dp, 표면 그림자 0dp, 그라데이션 없음, 무목적 애니메이션 없음, 인터랙션 없는 hover 장식 없음.
- 선택 상태는 색상+아이콘/텍스트 상태를 함께 사용. 탭 아이콘은 1-A 표준 Material Outlined을 임시 사용하며 브랜드 아이콘은 1-B에서 통일한다.

## 공통 타이포
- SansSerif 시스템 글꼴: 헤드라인 28sp/36sp semibold, 타이틀 20sp/28sp semibold, 본문 16sp/24sp, 보조 14sp/22sp, 라벨 12sp/18sp.
- 접근성 글자 확대를 막지 않음. 160%에서 텍스트 줄바꿈/스크롤 허용. 작은 화면에서 구조적 잘림 없는지 실제 캡처 QA 필요.

## 레이아웃·컴포넌트 토큰
- Spacing: 4, 8, 12, 16, 24, 32dp; 최소 터치 목표 48dp; 최대 콘텐츠 폭 760dp.
- PetSurfaceCard: 외곽선 1dp, 그림자·모서리장식 없음.
- PetPrimaryButton/SecondaryButton: 접근 가능한 높이/텍스트, Primary 대비 확보.
- PetEmptyState: 상황 설명과 이후 단계에서 연결할 실제 CTA의 공간 확보.
- PetStatusLabel: neutral/success/warning/error 상태, 상태색만 의존하지 않도록 텍스트 표기.
- Material3 NavigationBar, RadioButton은 공통 테마 토큰 적용. 설정의 테마 선택은 실제 저장/적용 기능.

## 화면 대응
- 초기 화면은 0단계 S03(홈), S11(기록), S18(건강), S14(통계), S30(더보기)의 내비게이션 위치만 연결.
- 기본 홈은 ‘개발 기반 미리보기’로 명확히 표시. 기록 카드·통계 값은 표시하지 않음.
- 이후 1-B/S00~S18 실제 구현 시 기록 CRUD/프로필 컨텍스트/다중 반려동물 전환을 Repository 기반으로 연결.
- 하단 탭과 선택 상태는 구성 변경 시 `rememberSaveable`로 보존. 사용자 테마는 `DataStore`에 보존.
- 시스템 모드: `isSystemInDarkTheme()`를 따르며, 라이트/다크 직접 선택은 시스템 변경과 무관하게 유지.

## 구현 파일
- `MainActivity.kt`, `MainViewModel.kt`, `data/ThemePreferences.kt`
- `ui/PetWorldApp.kt`, `ui/theme/{Colors,Typography,Theme,ThemeMode,Dimens}.kt`
- `ui/components/PetComponents.kt`
- Gradle version catalog, Gradle wrapper, AndroidManifest, launcher 임시 단색 아이콘, Android CI
- 테스트: ThemeMode 저장 문자열 처리, OS 테마 결합, 빌드/Lint/단위테스트 CI.

## 데이터·보안
- 초기 단계에서는 동물/의료·사진 데이터를 수집하지 않음. 네트워크 권한/클라우드 SDK/서명 키 미추가.
- 단말 설정(DataStore)은 최소한의 테마 값만 저장. 추후 데이터 저장은 1-C/1-D Room 설계 적용.
- Android 자동 백업 비활성. 향후 명시적 암호화 백업 UX 완료 후 제공.
- secrets, key, 서비스 계정 파일은 `.gitignore`에서 보호.

## 검증 기준
- [ ] Gradle wrapper jar를 바이너리 포함해 checkout한 새 환경에서 빌드.
- [ ] `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 성공.
- [ ] 기기에서 5개 탭 선택/뒤로가기/회전/프로세스 재기동.
- [ ] 시스템/라이트/다크가 유지되고 앱 재실행 후 선택값 영속화.
- [ ] 라이트/다크 전 화면, 360dp 작은 폰, 160% 폰트, 태블릿 및 폴더블 UI 캡처 비교.
- [ ] Material3 색상 대비·터치 영역·TalkBack 레이블 확인.
- [ ] PR 병합 전에 CI 전체 성공 확인; 결과 없을 경우 미검증으로 표기.

## 다음 단계
- 1-B — 브랜드·앱 아이콘 및 공통 기능 아이콘 확정.
- 1-C — 내비게이션·반려동물 선택 상태 / 온보딩.
- 1-D — Room / 기록 CRUD / 자동 집계.
- 1-E — 로컬 백업·복원.
- 참고 공식 문서:
  - https://developer.android.com/build/releases/agp-9-3-0-release-notes
  - https://developer.android.com/build/migrate-to-built-in-kotlin
  - https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
  - https://developer.android.com/develop/ui/compose/bom
  - https://gradle.org/release-checksums/
