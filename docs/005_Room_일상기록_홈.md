# PetWorld 1-D | Room 일상 기록 CRUD 및 홈 데이터 연동

- 문서 번호: 005
- 작성일: 2026-11-09 (KST)
- 저장소: https://github.com/hebi10/PetWorld
- PR: https://github.com/hebi10/PetWorld/pull/4
- 개발 브랜치: `feature/1d-room-daily-records`
- 구현 기준: 0단계 기능/화면 명세, 1-C SQLite 프로필, 1-B Pet Journal Mono 디자인

## 1. 개발 목표

기존 1-C 사용자 데이터가 들어있는 `petworld_profiles.db` 파일을 Room 데이터베이스 v2로 마이그레이션하고,
식사·음수·배변·활동·체중 다섯 기록을 생성·조회·수정·삭제할 수 있도록 합니다.
홈 대시보드는 선택한 반려동물의 오늘 집계와 최근 기록을 실제 Room 데이터에서 읽고,
기록 탭은 기간/유형 필터·상세·수정·삭제·통계 화면으로 연결합니다.

기본 사용은 로그인·네트워크·서버가 불필요하며 동물별 `petId`를 기준으로 독립 데이터를 유지합니다.

## 2. 데이터 무손실 이전 (1-C → 1-D)

- DB 파일은 변경하지 않습니다: `petworld_profiles.db`.
- 이전 스키마: `SQLiteOpenHelper` 버전 1, 테이블 `pets`.
- 새 스키마: `RoomDatabase` 버전 2, 테이블 `pets` 및 `care_entries`.
- `MIGRATION_1_2`는 기존 `pets` 컬럼/기본값/인덱스를 삭제하거나 변경하지 않고 `care_entries`와 인덱스 두 개만 추가합니다.
- `PetEntity`의 필드 타입/기본값/인덱스(아카이브/수정시각 DESC)는 기존 1-C 스키마와 정합성을 유지합니다.
- 초기 사용자 ID(UUID), 사진 파일명, 프로필 정보, 보관 상태, 날짜 필드가 그대로 유지됩니다.
- **파괴적 마이그레이션은 허용하지 않습니다.** 마이그레이션 실패 시 기존 DB를 초기화하지 않고 오류 상태로 남깁니다.
- 사용자 프로필 삭제는 기존 이중 확인 UX를 유지하고, 연관 기록 건수를 알린 후 외래키 CASCADE로 함께 삭제합니다.
- 기록·프로필 삭제 때 앱 내부에 보관된 관찰 사진 사본과 프로필 사진을 정리합니다.

## 3. Room 기술 스택 및 스키마

- Room 2.8.5 / KSP 2.3.13 / Robolectric 4.17.
- Room 엔터티: `PetEntity`, `CareEntity`; DAO: `PetDao`, `CareDao`.
- `CareEntity` 주요 필드: `id` UUID, `pet_id` FK, `type`, `occurred_at` (UTC epoch ms), `timezone_id`,
  음식 종류/제품명/제공량/섭취량/단위, 음수 mL(Nullable), 배변 종류/횟수, 활동 종류/분, 체중 g/원래 단위,
  관찰 사진 파일명, 메모, 작성/수정 시각.
- 외래키: `care_entries.pet_id → pets.id`, ON DELETE CASCADE.
- 조회 인덱스: `(pet_id, occurred_at)` 및 `(pet_id, type, occurred_at)`.
- DAO에서 반려동물별 기록을 구분하고, 모든 기록 쓰기/수정/삭제는 트랜잭션으로 수행합니다.
- 기록 편집으로 `petId` 또는 `type`을 다른 값으로 바꾸는 것은 금지합니다.
- Room Flow 관찰을 ViewModel의 화면 상태로 전달하며 저장/수정/삭제 시 홈·목록·통계가 동기화됩니다.

## 4. 입력 요구사항 및 검증 규칙

### 식사
- 종류: 사료/습식/간식/기타, 제품명 선택, 제공량 필수, 실제 섭취량 선택, 단위 g/mL.
- 제공량은 양수, 섭취량은 0 이상 제공량 이하. 소수 셋째 자리까지 지원하고 정수 milli-unit으로 저장합니다.
- 섭취량 빈칸(NULL)은 미측정, 섭취량 0은 실제로 먹지 않음을 확인한 상태입니다.
- 제공량과 섭취량을 다른 지표로 집계하고, 일부 기록이 미측정이면 미측정 건수도 표시합니다.
- g과 mL는 변환이나 합산 없이 각각 별도 표시합니다.

### 음수
- 음수량 mL를 정수로 저장. 측정값 0mL과 미측정(NULL)을 구분합니다.
- 공동 급수기 수치를 특정 동물에게 자동 배분하지 않습니다.

### 배변
- 소변/대변, 횟수(1~100), 관찰 메모, 선택적 사진.
- 사진은 Android Photo Picker로 고른 뒤 로컬 JPEG로 복사하며 원본 파일에 의존하지 않습니다.

### 활동
- 산책/놀이/수면/기타, 관찰 시작 일시와 지속시간(분, 1~1440).
- 겹치는 기록은 자동 삭제하지 않습니다.

### 체중
- kg(소수 3자리) 또는 g(정수) 입력. 내부 저장은 정확한 정수 g 단위이며 원래 입력 단위를 함께 보존합니다.
- 0 이하와 비정상적인 형식은 차단. 체중 그래프는 실측 2개 이상에서만 표시합니다.

### 공통
- 발생일시 사용자가 지정. UTC timestamp + 입력 당시 timezoneId 저장. 날짜 필터는 각 기록의 timezoneId로 그룹화.
- 과거 기록 허용, 미래 시각(5분을 초과)은 차단.
- 메모 2000자 이하. 잘못된 값/단위/범위를 저장하지 않음.
- 삭제 시 확인창을 표시하고, 성공 이후에만 상세를 닫습니다.
- 입력 도중 이탈 시 폐기 확인, 저장 실패 시 폼 상태를 유지합니다.

## 5. 화면 및 사용자 흐름

- 홈(S03): 선택 동물 이름/사진/주의사항, 오늘 날짜, 5개 유형의 실제 합계·건수, 최근 5개 기록, 빠른 기록 추가.
- 빠른 입력(S05): 홈 → 5종 기록 선택 시트 → 해당 입력 폼.
- 입력(S06~S10): 종/단위·값·발생 일시·메모·관찰 사진(배변) → 저장.
- 타임라인(S11): 기간 오늘/7일/30일/전체, 유형 필터(5종/전체), 기록 개수/리스트/추가.
- 상세(S12): 입력 값, 시간·메모·관찰 사진, 수정/삭제/뒤로가기.
- 건강(S18): 선택 반려동물의 체중 기록, 체중 빠른 입력. 진료·복약·증상은 후속 단계 안내.
- 통계(S14): 7일·30일·전체 기간, 기록 종류별 합계/건수, 실측 체중 기본 추이.
- 프로필 관리(S16): 삭제 이전에 연결된 기록 수를 표시하고 두 번 확인.

빈 데이터는 ‘기록 없음’이며, 입력되지 않은 날짜는 0으로 추론하지 않습니다.
모든 화면에서 라이트/다크/시스템 및 1-B PetIcons 스타일을 유지합니다.

## 6. 검증 범위 및 테스트

- `CareModelsTest`: 음식 제공·섭취·미측정, 음수 0 대 NULL, 체중 정수 g 변환, 미래 기록 차단, 날짜/종류별 집계.
- `LegacyRoomMigrationTest`: 1-C SQL로 프로필 두 마리를 생성 → Room 1→2 자동 이전 → UUID/사진/보관 상태 보존 확인 → 새 기록 생성 및 삭제 CASCADE 검증.
- `CareRepositoryIntegrationTest`: 고양이/강아지 별도 CRUD, 수정 전후 집계, 타 동물 기록 수정·삭제 차단, 보관된 동물 기록 생성 차단.
- GitHub CI: Unit Tests / Android Lint / Debug APK Assemble.
- **CI 성공은 실제 기기 화면 및 Photo Picker 동작을 보증하지 않습니다.**

## 7. 실기기 QA 및 후속 범위

### 1-D 미검증
- 이전 버전 1-C가 실제 설치된 실기기에서 업그레이드/데이터 보존 여부.
- Photo Picker, 대형 사진·메타데이터, 사진 삭제 후 파일 잔존, 앱 강제 종료 중 이미지 처리.
- 360dp, 글꼴 160%, 태블릿·폴더블, 시스템/라이트/다크, TalkBack 및 내비게이션.
- API 26/29/35/36/37 및 장시간 기록 누적 시 성능/안정성.

### 다음 개발
- 1-E: 암호화 백업·복원 및 DB 스키마 파일/사진 포함 정책.
- 3단계: 증상·진료·복약·예방 및 알림.
- 4단계: 고급 그래프·PDF 리포트/기간 비교.
- 5단계: 가족 공유·동기화.
- 출시 준비 단계: Android 실제 캡처와 Play 개인정보·권한·테스트 트랙 검증.

## 8. 관련 저장소 파일

- `data/room/PetWorldDatabase.kt`, `data/room/PetMapping.kt`
- `data/PetRepository.kt`, `data/CareModels.kt`, `data/CareRepository.kt`
- `MainViewModel.kt`, `MainActivity.kt`
- `ui/screens/CareRecordEditor.kt`, `ui/screens/CareRecordScreens.kt`
- `ui/screens/PetHomeAndManager.kt`, `ui/PetWorldNavigation.kt`
- `app/src/test/java/.../CareModelsTest.kt`, `LegacyRoomMigrationTest.kt`, `CareRepositoryIntegrationTest.kt`

참고: https://developer.android.com/jetpack/androidx/releases/room
