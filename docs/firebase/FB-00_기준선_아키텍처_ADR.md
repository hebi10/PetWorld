# FB-00 — Firebase 도입 기준선 및 아키텍처 결정(ADR)

- 작성일: 2026-11-12 (KST)
- 상태: **설계 기준선 / 앱 구현 전 / Firebase 리소스 생성 전**
- 기준 Git SHA: `a618de146215ee7b2447b433022e2b5d7e85130a` (`main`)
- 대상: `hebi10/PetWorld`, `com.haebi.petworld`
- 기존 Google Play: `0.1.0 (6)`; 내부 및 비공개 테스트의 **Play API commit·readback 성공**. Play Console 심사·테스터 설치는 별도 확인
- Drive 계획: https://docs.google.com/document/d/1GvCcc2Ht5lTBdDdVu-zAae27BxRKMiOh1DAcRdHZMZc/edit
- 후속 단계: FB-01~FB-09(도입·검증·배포), FB-10(별도 승인 가족 공유)

## 1. 현행 코드로 확인한 사실

| 대상 | 구현 확인 | Firebase 도입 시 영향 |
| --- | --- | --- |
| `app/build.gradle.kts` | Kotlin/Compose, Room, DataStore; Firebase/WorkManager 라이브러리 없음; `minSdk=26`, `targetSdk=36` | FB-01/05에서 필요한 의존성만 추가 |
| `AndroidManifest.xml` | `android:allowBackup="false"`; `INTERNET` 선언 없음 | FB-01에서 네트워크 권한/개인정보처리방침 갱신 |
| `data/room/PetWorldDatabase.kt` | `petworld_profiles.db`, Room **v2**, `pets`·`care_entries`; `get(context)` 앱 전역 싱글턴 | FB-03에서 계정 스코프 DB 제공자; 기존 DB 무손실 보존 |
| `data/PetRepository.kt` | `PetEntity` UUID 생성/수정/보관/영구삭제, 삭제 시 연결 사진 정리 | 쓰기 이벤트/outbox 및 삭제 보류 정책 필요 |
| `data/CareRepository.kt` | `CareEntity` CRUD, `petId` 검증, 저장·삭제 시 사진 정리 | 트랜잭션과 outbox 원자성, 사진 업로드 전 원본 보존 |
| `data/ThemePreferences.kt` | DataStore 전역 `theme_mode`·`selected_pet_id` | 선택 프로필만 계정별로 분리 검토 |
| `data/PetPhotoStore.kt` | `filesDir/pet_photos/pet_<UUID>.jpg`; 사진 파일로 로컬 보관 | FB-03/06: 계정별 경로·원격 저장 맵, 이전파일 지연 삭제 |
| `MainViewModel.kt` | `factory`에서 전역 DB/Repository 주입; 복원 후 Refresh | 계정 전환 시 이전 ViewModel/Flow 구독 종료 및 DB 전환 |
| `MainActivity.kt` | 첫 UI 구성 전에 `PetWorldRecoveryJournal.recoverIfPending` 실행 | 로그인 화면/계정 선택보다 복구 저널 처리 선행 |
| `data/backup/*` | 암호화 `.petworld`, 사진/설정 포함, 강제 종료 복구 저널, 전역 `MutationGate.mutex` | 계정별 복원·동기화 충돌 통제; 기존 포맷 유지 |
| `.github/workflows/play-internal.yml` | `main` push의 Android CI 성공 시 자동 deploy 가능 | 개발 PR `main` 병합 금지; FB-09 릴리스 게이트 분리 |

위 표는 **코드에서 확인한 내용**이다. Firebase 설치·로그인·클라우드 동기화 및 실기기 전환 테스트가 완료되었다는 의미가 아니다.

## 2. 결정 기록(ADR)

### ADR-001 — 로컬 우선·로그인 선택형

**결정:** 비회원 사용을 유지한다. 기존 데이터가 있는 사용자에게 업데이트 직후 로그인을 강제하지 않는다. Room을 화면과 오프라인 CRUD의 기준으로 사용하고, Firebase는 인증·클라우드 데이터 복제에 사용한다.

**근거:** 현행 UI·집계·필터·데이터 보존은 Room Flow/Repository에 결합되어 있다. 완전한 Firestore 전환은 회귀 범위가 크다.

**비목표:** Room 제거, 사용자 데이터 파괴적 초기화, 기존 `.petworld` 수동 백업 폐기.

### ADR-002 — 계정 스코프 명시

**결정:** 비회원 레거시 DB(`petworld_profiles.db`)는 그대로 유지한다. 로그인된 계정별 DB/사진/동기화 작업 공간은 **계정 스코프**로 만든다. 실제 파일 이름·열기/닫기 수명주기·UID 매핑은 FB-03에 구현·테스트한다.

**중요:** Firebase UID를 DataStore/로그에 무심코 노출하지 않고, 계정 전환 시 이전 Room Flow/이미지 캐시/outbox를 모두 종료·분리한다. `PetWorldDatabase.get()` 싱글턴을 그대로 공유하는 설계는 금지한다.

### ADR-003 — 로컬 원본 복사 후 최초 이행

**결정:** 최초 연결 시 **copy-verify-commit**. 원래 비회원 DB·사진·백업은 유지하고 계정 전용 복제본 및 서버 스냅샷을 준비한다. 원격 자료가 존재하면 '기기 데이터 가져오기/계정 데이터 사용/취소' 분기를 명시한다. 동일 UUID에 다른 내용이 있으면 자동 덮어쓰기하지 않는다.

**상태 흐름:**
```text
GUEST_LOCAL
  ├─ 로그인 취소/실패 → GUEST_LOCAL (원본 유지)
  └─ Google 로그인 성공 → AUTHENTICATED_NEEDS_IMPORT
        ├─ 클라우드 비어 있음 + 사용자 승인 → COPY_LOCAL → UPLOAD → VERIFY → SYNC_READY
        ├─ 클라우드 존재 → CHOOSE_SOURCE / REVIEW_COLLISIONS → VERIFY → SYNC_READY
        └─ 취소/오류/중단 → PAUSED (로컬 원본 보존, 무단 덮어쓰기 없음)
```

**검증:** 서버 재조회·레코드 ID·값·외래키·사진 해시가 확인되기 전에 `SYNC_READY` 표기 금지.

### ADR-004 — 한 번의 로컬 쓰기에서 데이터와 outbox 함께 기록

**결정:** FB-05에서 Room 트랜잭션 하나에 레코드 변경과 동기화 outbox 항목을 함께 기록한다. outbox는 계정별로 구분하고 `opId`와 예상 revision을 둔다. WorkManager가 전송한다. Firebase 자체 오프라인 캐시와 Room을 **별개의 진실 원천으로 혼용하지 않는다**.

**주의:** Firestore 모바일 오프라인 동기화는 동일 문서 충돌 시 마지막 쓰기 우선이므로, PetWorld의 보호자 기록에는 서버 revision/조건부 갱신과 충돌 표시가 필요하다.

### ADR-005 — 영구 삭제와 사진 교체는 동기화 확인까지 보류

**결정:** FB-05/06에서 사용자 삭제 의도를 `tombstone`으로 기록하고 다른 기기가 재연결해도 삭제 항목이 되살아나지 않게 한다. 사진은 새 파일 업로드·참조 연결 성공을 검증한 다음 이전 파일 제거를 예약한다. 현재 `PetRepository`와 `CareRepository`의 즉시 사진 삭제 경로를 클라우드 환경에 그대로 재사용하지 않는다.

### ADR-006 — 기존 백업과 복원 저널 유지

**결정:** `.petworld` 포맷 1 / 스키마 2를 후방 호환 기준으로 유지하고, 기존 복원 중단 저널을 훼손하지 않는다. 계정 연결 상태에서 복원은 서버 동기화 일시 정지 → 복원 대상 검증 → 충돌·덮어쓰기 확인 → 로컬 교체 → 후속 서버 조정으로 설계한다. **기존 복원 완료=서버 복원 완료가 아니다.**

**확인해야 할 리스크:** 기존 복원은 전역 DB의 `pets` 전체 삭제 후 데이터를 다시 채운다. 계정별 저장소 분리 없이 수행하면 사용자의 다른 계정 자료에 영향을 줄 수 있다.

### ADR-007 — 보안·개인정보·릴리스 분리

**결정:** `users/{uid}` 원격 경로는 해당 Firebase Auth UID만 접근하도록 Rules 적용. 계정/사진/동기화 작업을 인증 없이 원격 전송하지 않는다. Google 로그인/Firestore/Storage 도입과 개인정보처리방침·Play 데이터 보안/회원 탈퇴 정책을 함께 변경한다.

**운영 경계:** Firebase 생성·Blaze 연결·실제 클라우드 쓰기·Google Play AAB 업로드는 FB-00 범위 밖이다. `main` 병합은 내부 테스트 자동 배포를 유발할 수 있으므로 이번 단계는 Draft PR에만 기록한다.

## 3. 배포·리전·비용 관련 사실 확인(공식 문서)

- Firebase Auth Android Google 로그인은 Credential Manager, Firebase Authentication, Android 앱 서명 SHA 구성이 필요하다. https://firebase.google.com/docs/auth/android/google-signin
- Cloud Firestore: 프로젝트당 무료 할당 데이터베이스 1개, 1GiB 저장·일 50,000 읽기·20,000 쓰기 등이 기준이며 추가 사용/기능은 별도 과금될 수 있다. https://firebase.google.com/docs/firestore/quotas
- Firestore 위치 `asia-northeast3`는 서울이며, 운영 리전은 생성 전에 서비스·요금·정책을 확정한다. https://firebase.google.com/docs/firestore/locations
- Cloud Storage for Firebase는 2026년 현재 **Blaze 요금제 필수**, Blaze에서도 일부 무료 사용량이 있을 수 있다. **결제 연결은 FB-00에서 수행하지 않음**. https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024
- 오프라인 Firestore SDK의 동시 쓰기는 마지막 쓰기 우선이다. https://firebase.google.com/docs/firestore/manage-data/enable-offline

## 4. 결론과 인계 조건

- 본 단계는 저장소 코드·기획 기반 **문서형 기준선 확정**. 현재 사용자 기기 실데이터는 접근/조회/수정하지 않았다.
- FB-01 작업 전 확정할 값: Firebase 프로젝트/환경 분리, Firestore·Storage 리전, 과금 승인, Play 서명 SHA 등록 계획, Security Rules 검증 환경.
- FB-02~06은 FB-00 문서의 계정 스코프·ID 보존·오프라인·사진/백업 정책을 지켜 구현한다.
- FB-08에서 실기기 2대·다중 계정·기존 설치 업데이트·오프라인 충돌·사진·복원 무손실을 확인해야 FB-09 배포로 진행한다.
