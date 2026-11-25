# FB-08-A — 전체 기능 통합 후보 및 검증 기준 (2026-11-25)

## 정확한 통합 범위
- main 45ba20d7601dde5338b82a5f2b153d3a06c359bd, 기존 기능 tip #85 175a743ac9d13d12e047f50ef5d46d294ced8a43. 이전 common 8efea01819b3371934baa8cbf5290d487850340c에서 main은 5 commits/4 files, 기능 후보는 247 commits/197 files. 양쪽 변경 파일의 교집합 없음.
- main의 보호된 FB-04 개발 APK workflow/정적 검사/문서/스크립트 4개 blob을 그대로 보존한 two-parent merge commit e753e9d4da3a9f2413f048d6b8aebc36f25809bc을 별도 후보에 생성. 기존 기능 tip 대비 변경은 해당 4개 파일뿐이며 main 대비 197개 파일, behind 0 확인. 실제 main ref는 변경하지 않았다.
- #55의 #37/#54 통합 원본 ancestry 및 #55~85(증빙 전용 #67/#70 제외 29 PR) 각 head와 base 연결 확인. 기존 Draft와 검증 이력은 그대로 유지하며 QA 후보를 main 대상으로 검증한다. 제품 코드를 통합한 것이지 각 작업의 실제 운영 기능 활성화 또는 main 병합을 뜻하지 않는다.
- #67 이전 캡처 실패는 미해결 이력으로 유지(해당 구버전 QA 전용). #70 소형 160%/폴더블 핵심 탭 별도 QA 성공, 현재 후보와 동일 SHA 증거 아님. 최종 main 전용 전체 캡처·접근성·API29/35/36 검증으로 갱신. QA 전용 브랜치를 제품 코드에 불필요하게 포함하지 않음.

## 기존 웹 실제 화면 검토
#85 Chromium 3/3, Firebase Rules/서버 80/80, 로컬 E2E 및 Android/OPS/Play 검사 모두 성공. GitHub artifact 14999945556 (https://github.com/hebi10/PetWorld/actions/runs/45564544733/artifacts/14999945556), Drive 저장 및 원본 확인: https://drive.google.com/file/d/1hzfmU2ko9DefU2sVCsXtDMbB7A8y9gGk/view .
- 01-disabled-mobile.png 390×2529: 검토 페이지의 모든 동작 비활성, 가로 overflow 없음. 본문·동의·복구 UI 가독성 확인.
- 02-request-confirmed.png 1200×2021: 합성 접수·접근 차단, 제출 버튼 비활성 및 아직 전체 완료가 아님 표시.
- 03-recovered-complete.png 1200×2021: 합성 비회원 복구, 서버 완료 표시와 기기/외부 백업 범위 구분.
- 개선점: 긴 화면의 상태 조회 위치, 브라우저 로케일에 따른 영문 native 파일 선택 버튼. 사용자 계정/실제 서버 결과가 아닌 합성 Chromium UI 증빙. 공개 배포 전 접근성·한국어 로케일 및 복구 중심 탐색 검증 필요.

## 통합 CI 및 중단 기준
- fb08-integrated-source-manifest.json: exact source 29 PR + #37/#54, 보호 main 파일, 필수 PR workflow 목록, 실제 환경 미검증 플래그. check_integrated_candidate.py 및 신규 PR offline gate는 전체 이력·고정 base/head·보호 서명/Play/AGENTS 및 비활성 웹/SDK 게이트를 읽기 전용으로 검증. 실행 후 성공 여부를 확인하기 전 합격 판정 금지.
- 새 main-target 후보 exact HEAD의 Android CI, Rules, Backup Restore, 계정 화면/격리(API29/35/36 개별 job), isolated SDK, local deletion E2E, Chromium, firebaseDev 서명 preflight, 빠른 기록·설정, Play exact-source, OPS-07/09, FB-08 offline gate를 확인한다. 스킵/누락/실패 또는 SHA 변경은 합격 아님. 일반 전수 UI 및 main-only QA는 중복 실행하지 않고 기존 final-device-qa.yml에서 최종 main SHA로 검증한다.
- 실제 개발 APK 보호 서명 workflow는 기존 PR #55에만 허용된다. 이 정책을 수정하거나 우회하지 않았다. 통합 후보 실제 개발 APK·Google 로그인 등 QA는 적절한 승인과 보호 workflow의 정식 검토가 선행되어야 한다. 실제 Firebase App Check/Rules 배포/IAM/Storage 버전·보존·soft-delete/HTTPS/TLS/TTL/worker, 기존 설치 공유 캐시의 전체 사용자 미전송 쓰기, 실제 PIN/Keystore와 UI·Room·image/listener drain, 복구 슬롯 해제, GIS auth_time·Web App/OAuth/domain/CSP/호스팅 및 외부 삭제 URL 검증은 별도. 현재 UNVERIFIED_SHARED 및 정적 웹 비활성 유지.
- 순서: 통합 PR 새 SHA CI 성공 → 실제 환경/실기기 증빙 및 미해결 안전 조건 검증 → 필요 보완 → 기존 정책에 맞는 안전 병합 및 최종 main 검증 → final-device-qa(API29/35/36, 화면/접근성/키보드/백업/기록/설정) → 사용자 승인 시 기존 서명 AAB/internal release 절차. PR 병합/실제 서명·계정 삭제/서버·웹·Play 배포를 이번 작업에서 수행하지 않음.
