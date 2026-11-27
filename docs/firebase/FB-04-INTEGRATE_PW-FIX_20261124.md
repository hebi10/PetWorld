# FB-04-INTEGRATE — Firebase 기존 스택과 PW-FIX 통합 후보

- 기준: PW-FIX #51~53 병합 main a83a947179fff1abca332233e5a244332d3f9409, 페이지 조회 PR #54 HEAD 8efea01819b3371934baa8cbf5290d487850340c, 기존 Firebase PR #37 HEAD 22a2a47e4d12626af4dfa37ff4aa598ef7415fbb.
- 기존 PR #27~37 스택은 그대로 보존한다. 이 통합 브랜치는 두 부모 커밋을 갖는 검증용 후보이며 선행 #54 검증, 최신 main 재대조 및 Firebase 실기기/실서버 검증 전 main 병합을 하지 않는다.
- 충돌 범위 7개: MainActivity, MainViewModel, CareRepository, PetRepository, PetPhotoStore, PetWorldDatabase, PetWorldNavigation.
- Guest/계정별 Room 및 사진 경로, ThemePreferences, 선택형 로그인, 비회원 데이터 원본/암호화 백업 보존, 기존 Firebase Rules/이행 승인·충돌·재시도 설계를 보존한다.
- 최신 기록 조회 재시도, 개체별 집계, 50건 단위 페이지 조회 및 상세 ID 조회, 파일 크기 제한·EXIF 보정·원자적 JPEG 저장, 쓰기 중 취소 보호를 유지한다.
- 계정 전환 시 이전 UI를 숨기고 ViewModel을 해제하며 동일 전역 쓰기 잠금 아래 기존 DB를 닫는다. Activity 재생성 시 기존 ViewModel의 계정 범위가 현재 인증 범위와 일치하는지도 검사한다.
- 기존 계정 격리 API29/35/36 테스트에 선택 계정의 페이지 조회, 정확한 건수, 다른 계정 레코드 조회 거부를 추가했다.
- 기존 OPS-04~09 정책, 최신 main의 수동 최종 QA 및 Play 배포 경로를 변경하지 않는다.
- CI 및 실제 계정 로그인/개발 서버 업로드·재조회 미검증 상태. 기존 #46 보호된 개발 APK 서명 워크플로는 별도 리뷰·통합 HEAD 검증 필요. 실제 Firebase 서버 또는 Google Play 변경 없음.
