# FB-00 — 데이터 보존·보안·배포 검증 계획과 위험 목록

- 일자: 2026-11-12
- 범위: Firebase **도입 전** 코드·기획 기준선 검토, 향후 단계별 검증 명세
- 근거 `main`: `a618de146215ee7b2447b433022e2b5d7e85130a`
- 실행 구분: **정적 소스 확인 완료** / 사용자 기기 데이터 조회·Firebase 실호출·Android 신규 QA는 **미실행**
- 연결: `docs/firebase/FB-00_기준선_아키텍처_ADR.md`, `docs/firebase/FB-00_스키마_파일_매핑.md`

## 1. FB-00 완료 항목과 미완료 경계

| 항목 | 상태 | 근거/후속 |
| --- | --- | --- |
| Room DB/Entity/DAO/마이그레이션 확인 | 확인 | `PetWorldDatabase.kt`, `PetRepository.kt`, `CareRepository.kt` |
| 현행 DataStore/사진 경로 확인 | 확인 | `ThemePreferences.kt`, `PetPhotoStore.kt` |
| 백업 파일 포맷·사진/복원 저널 확인 | 확인 | `PetWorldBackupExporter.kt`, `PetWorldBackupRestorer.kt`, `PetWorldRecoveryJournal.kt` |
| GitHub/Google Play 배포 트리거 확인 | 확인 | `play-internal.yml`; 내부·alpha는 기존 버전 API 성공 |
| 계정별 DB/대기열·충돌·삭제 설계 | 설계 기준선 | 구현은 FB-03~FB-06 |
| Firebase 프로젝트/리전/결제 연결 | 미실행 | FB-01의 실제 설정·필요한 승인 |
| 기존 사용자 **실기기** 데이터 무손실 검증 | 미실행 | FB-03/04/08 실기기 설치·업데이트 확인 |
| 계정 탈퇴·보안 Rules 시나리오 | QA 명세 작성 | FB-01/07/08 실제 Emulator·서버 테스트 |
| Play Console 심사·설치 | 미확인 | FB-09 별도 릴리스 게이트 |

**정적 코드 분석의 완료를 클라우드/실기기 기능 테스트 통과로 표현하지 않는다.**

## 2. 위험 등록부 — 사전 차단 우선순위

| ID | 위험 | 영향 | 대응·검증 | 담당 단계 |
| --- | --- | --- | --- | --- |
| R-01 / 차단 | 전역 `PetWorldDatabase.get()`로 서로 다른 UID의 Room 데이터 노출 | 계정 간 기록 혼합·개인정보 유출 | 계정별 DB Provider, 이전 Flow/캐시 해제, UID A/B 반복 전환 | FB-03 |
| R-02 / 차단 | 기존 사용자 데이터 자동 덮어쓰기·실수 삭제 | 데이터 영구 손실 | 원본 DB/사진 보존·복사본 이전·사용자 승인·ID/해시 검증 | FB-03/04 |
| R-03 / 차단 | `PetRepository`/`CareRepository` 사진 교체·삭제 직후 로컬 파일 정리 | 서버 업로드 실패 시 사진 영구 소실 | 사진 전송 완료·서버 참조 readback 이전 원본/이전 사진 삭제 금지 | FB-05/06 |
| R-04 / 차단 | 계정 연결 후 `.petworld` 전체 복원이 서버 상태를 무심코 덮어씀 | 다른 기기 기록 유실 | 복원 전 동기화 정지, 계정별 DB 선택, 충돌 선택·롤백 | FB-03/05/08 |
| R-05 / 차단 | Firestore 마지막 쓰기 우선에 의존 | A/B 동시 수정 기록 소실 | 조건부 revision, outbox 상태, 충돌 보류·선택 UI | FB-05 |
| R-06 / 차단 | 원격 삭제 후 오프라인 옛 레코드 재등장 | 삭제 취소·오염 | Tombstone 및 서버 삭제 revision·충돌 처리 | FB-05 |
| R-07 / 차단 | Storage/Firestore Security Rules 오구성 | 타인 사진·기록 유출 | Auth UID 경계, Rules Emulator 허용/거부 자동 테스트 | FB-01/06/08 |
| R-08 / 높음 | Google Play 서명 SHA/Google 로그인 인증 불일치 | 테스트 설치 후 로그인 실패 | Play App Signing SHA 및 테스트/개발 인증 비교 | FB-01/02/09 |
| R-09 / 높음 | 기존 `MainActivity` 복구 저널보다 인증 초기화가 먼저 진행 | 복원 오염·무한 대기 | 복구 저널 확인 우선, 로그인/DB 열기 순서 회귀 테스트 | FB-02/03 |
| R-10 / 높음 | 사용자 계정 삭제 후 Firestore 하위 컬렉션·Storage 잔존 | 개인정보 처리 정책 위반 | 서버 삭제 워크플로+재시도·확인; 외부 삭제 URL | FB-07/08 |
| R-11 / 높음 | Blaze/Storage 이용량 증가·잘못된 반복 리스너 | 예기치 못한 과금 | 프로젝트 예산 경보·사용량 한도·쿼리 계측·사진 제한 | FB-01/05/06 |
| R-12 / 높음 | 데이터만 있는 Draft PR을 main에 병합해 자동 internal 릴리스 | 테스트 버전 불필요 증가 | Draft 유지; 현재 CI→Play 배포 트리거 확인, FB-09 전 병합 금지 | 전 단계 |
| R-13 / 보통 | 앱 로그/아티팩트에 UID·개인 기록·사진·토큰 노출 | 개인정보 유출 | 테스트 픽스처만 사용, 비밀정보·실제 사용자 자료 로그 금지 | 전 단계 |
| R-14 / 높음 | 복구 저널에 복원용 암호 문자열이 기기 내부에 저장됨 | 샌드박스 접근 가능한 환경에서 노출 위험 | 기기 저장 암호 보호 강화/키 관리 설계, 회귀·실기기 보안 검토 | FB-03/08 |

## 3. 필수 테스트 행렬

| 그룹 | 상황 | 기대 결과 | 자동/실기기 |
| --- | --- | --- | --- |
| QA-01 | 기존 0.1.0(6) 데이터 → 새 버전 앱 업데이트 | `pets`, `care_entries`, 사진, 설정, 복원 파일 불변 | 실기기 필수 |
| QA-02 | 비회원으로 첫 실행·오프라인 5종 기록 CRUD | 로그인 없이 전 기능 사용 가능 | Unit/계측 |
| QA-03 | Google 로그인 취소·실패·네트워크 단절 | 로컬 데이터 보존, 재시도·오류 표시 | Emulator+실기기 |
| QA-04 | 기기 기존 DB/클라우드 빈 계정 → 최초 업로드 | 양쪽 건수/UUID·해시·NULL/0·시간대 동일 | Emulator+실기기 |
| QA-05 | 같은 계정 기존 서버 데이터 존재·로컬 데이터도 존재 | 충돌/원본 선택 UI, 자동 덮어쓰기 없음 | Unit/계측 |
| QA-06 | UID A → 로그아웃 → UID B, 반복 전환 | A의 기록·사진·outbox B에 미노출/미전송 | 계측+실기기 |
| QA-07 | 업로드 중 강제 종료·앱 재시작 1~20회 | 중복 없는 재시도, 부분 완료 복원 | Unit/계측 |
| QA-08 | 두 기기 동시에 동일 레코드 오프라인 수정 | 충돌 감지·사용자 승인 전 손실 없음 | 실기기 필수 |
| QA-09 | 삭제 오프라인/반대쪽 온라인 → 재연결 | 삭제된 기록 부활 방지 | 2기기 |
| QA-10 | 프로필·배변 사진 교체/삭제 도중 연결 종료 | 기존 JPG/서버 참조 유지·재시도 | 계측+실기기 |
| QA-11 | 기존 `.petworld` 백업 → 비회원/로그인 환경 복원 | 암호·FK·사진 일치, 서버와 충돌 분리 | 계측+실기기 |
| QA-12 | 다른 UID로 Firestore/Storage 접근 | 모두 permission-denied | Rules Emulator |
| QA-13 | Auth 사용자 탈퇴·서버 데이터 삭제 | 계정·하위 데이터·사진 정리, 재로그인 재전송 불가 | Emulator+서버 |
| QA-14 | 360dp/160%, Pixel Fold 3자세, TalkBack | 핵심 UI/버튼 접근 가능, 원본 자료 유실 없음 | 계측+실기기 |
| QA-15 | Android API 29/35/36, 라이트/다크 | 신규 Firebase UI 및 기존 S02~S18 회귀 없음 | GitHub Actions |

*테스트 결과는 테스트가 실제 실행된 정확한 커밋 SHA·기기·로그와 함께 기록한다. 미래 계획을 현재 'PASS'로 채우지 않는다.*

## 4. 출시 보존·롤백 기준

1. 현행 Google Play `0.1.0 (6)` internal/alpha 등록은 유지. Firebase 작업이 릴리스되기 전까지 새 Play 버전이 필요하지 않다.
2. 개발은 `feature/fb-XX-*` 개별 브랜치/PR로 분리. Auth/Room/사진 보안 관련 PR은 실제 데이터·계측 QA 전 자동 병합 금지.
3. `.github/workflows/play-internal.yml`은 `main` push Android CI 성공에서 내부 배포를 시도할 수 있다. 기획 PR을 main에 병합하여 의도치 않은 새 AAB를 만들지 않는다.
4. 점진 도입: Feature Flag/환경 분리 → 개발 프로젝트 Rules 검증 → 내부 테스트 → 실제 설치/계정 QA → 기존 동일 AAB를 비공개 테스트 승격.
5. Firebase 인증 실패 시 비회원 로컬 모드 진입 가능해야 하며, 백그라운드 업로드·원격 쓰기는 안전하게 중단된다.
6. 이미 클라우드에 배포한 이후에는 구버전 APK 롤백만으로 스키마 충돌이 해소되지 않을 수 있으므로, **원격 스키마·Rules의 후방 호환과 동기화 중단 절차**를 별도로 준비한다.
7. 개인정보처리방침은 현재 로그인/서버 전송이 없다고 설명하므로, 실제 Firebase 빌드 배포 전 FB-07에서 반드시 수정한다.

## 5. 단계별 GO / NO-GO

| 인계 | GO(완료 기준) | NO-GO(차단) |
| --- | --- | --- |
| FB-00 → FB-01 | 코드 기반선·매핑·ADR·QA·배포 경계 검토 기록 완성 | 기존 로컬 자료 파괴를 전제로 하는 구조 |
| FB-01 → FB-02 | Firebase Auth·Firestore·Storage 환경 설정과 기본 Rules 테스트 | 타 UID 접근 허용, 서명 SHA 불명확 |
| FB-02 → FB-03 | Google 로그인/비회원 UI 검증 | 로그인 강제, 기존 UI 사용 불가 |
| FB-03 → FB-04 | 게스트·A·B DB/사진 완전 분리, 업데이트 무손실 | 계정 간 데이터 유출, 원본 변경 |
| FB-04 → FB-05 | 최초 데이터 이전 중단·중복·값/해시 검증 | ID 충돌 자동 덮어쓰기 |
| FB-05 → FB-06 | 양방향/삭제/outbox/동시 수정 검증 | 삭제 부활·동기화 누락 |
| FB-06 → FB-07 | 사진 업로드·참조 전환·보존 검증 | 이전 사진 유실·타 사용자 노출 |
| FB-07 → FB-08 | 탈퇴·개인정보처리방침·Play Data Safety 일치 | 계정 삭제 미완료·정책 불일치 |
| FB-08 → FB-09 | Android + Firebase Rules + 실기기 2대 모두 GO | 서버-로컬 값 불일치·QA 차단 결함 |

## 6. FB-01 선행 체크리스트(실제 리소스 생성 전)

- [ ] Firebase 프로젝트명을 현재 PetWorld 앱 전용으로 확정. 개발/프로덕션 프로젝트 구분 여부 결정.
- [ ] Android 패키지 `com.haebi.petworld`와 Play 앱 서명 SHA 등록 경로를 검증.
- [ ] Firestore 리전(우선 `asia-northeast3`) 및 Cloud Storage 버킷 위치·과금 정책 확정.
- [ ] **Cloud Storage는 Blaze 요금제 필수**. 예산 경보·요금 정책을 검토하고 비용 발생 승인 없이 결제 수단 연결하지 않음.
- [ ] Firebase Auth Google 공급자, 필요 SDK 최소 의존성, Credential Manager 계획.
- [ ] Firestore/Storage Rules Emulator의 allow/deny 테스트 설계.
- [ ] 사용자 개인정보처리방침·Play Console 데이터 보안 항목 변경 목록 작성.
- [ ] Firebase 구성·개발/운영 분리와 민감정보 Git 노출 방지 원칙 확인.
- [ ] 이전 릴리스의 설치·업데이트/복원·사진·프로필 최소 2종 픽스처 준비.

## 7. 참고

- 소스: https://github.com/hebi10/PetWorld
- Google Drive FB-00 기획: https://docs.google.com/document/d/1GvCcc2Ht5lTBdDdVu-zAae27BxRKMiOh1DAcRdHZMZc/edit
- Firebase Auth: https://firebase.google.com/docs/auth/android/google-signin
- Firestore 사용량: https://firebase.google.com/docs/firestore/quotas
- Firestore 리전: https://firebase.google.com/docs/firestore/locations
- Storage 결제 정책: https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024
- Firestore 오프라인 충돌: https://firebase.google.com/docs/firestore/manage-data/enable-offline
