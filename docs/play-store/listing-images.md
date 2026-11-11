# PetWorld STORE-03 — Google Play 이미지 검증·업로드 안전화

## 대상 및 준비 상태

- 저장소: `hebi10/PetWorld`, 패키지: `com.haebi.petworld`, 등록정보 언어: `ko-KR`
- 실제 등록 자산: `assets/play-store/ko-KR/`의 `play-icon.png` 1장, `feature-graphic.png` 1장, `01-home.png` ~ `08-backup.png` 8장 (총 10개).
- `play-icon-dark-preview.png`는 비교용 이미지이며 Google Play 업로드 목록에서 제외합니다.
- STORE-01 `manifest.json`, STORE-02 `screenshots-manifest.json`의 SHA-256·파일 크기·출처·순서를 검증합니다. 원본 에뮬레이터 화면을 AI로 합성하지 않습니다.
- 이번 STORE-03 작업에서는 **실제 Google Play API 업로드를 실행하지 않습니다.** 등록정보 변경은 이후 STORE-04에서 별도로 수행합니다.

## 검증 방식

```bash
python3 -m unittest discover -s scripts/play/tests -p 'test_listing_images.py' -v
python3 scripts/play/listing_images.py --mode check --report output/petworld-listing-check.json
```

- 모든 PNG의 원본 바이트 SHA-256, 메타데이터와 파일 크기, PNG 서명/청크 CRC/zlib 데이터/필터, 지정된 해상도·색상 모드를 확인합니다.
- 앱 아이콘은 512×512 RGBA, 실제 알파 값은 모두 불투명(255), 1MiB 이하를 강제합니다.
- 대표 이미지는 1024×500 RGB, 설명 이미지 8장은 1350×2400 RGB, 알파 없는 PNG를 강제합니다.
- 8장 이미지의 순서, Android 캡처 원본 파일명, Source SHA 및 GitHub Actions 실행 정보를 기존 메타데이터와 비교합니다.
- 등록 대상이 아닌 추가 PNG, 중복 이미지, 패키지·언어 불일치가 발견되면 즉시 실패합니다.
- 외부 의존성(Pillow 등)은 추가하지 않습니다. 테스트는 가짜 Play API를 사용하며 네트워크·실제 Google 자격증명이 필요하지 않습니다.

## GitHub Actions

새 워크플로: `.github/workflows/play-listing-images.yml` → **PetWorld Korean Play listing images**

- `pull_request`: 변경된 이미지·manifest·검증 코드를 자동 검증하되, Google 계정에 접근하지 않습니다.
- `workflow_dispatch`의 기본값 `mode=check`: 10개 파일을 검증하고 검증 보고서를 artifact로 보관합니다. Google Play 변경 없음.
- `mode=upload`: **STORE-04에서 승인받은 경우에만** `main`에서 수동 실행. `confirmation=PETWORLD-KO-LISTING-10`을 정확히 입력해야 합니다.
- 실제 업로드는 기존 `google-play` 환경과 PetWorld 전용 WIF (`PLAY_WORKLOAD_IDENTITY_PROVIDER`, `PLAY_SERVICE_ACCOUNT`)를 우선 사용하며, 기존 `PLAY_SERVICE_ACCOUNT_JSON`이 있는 환경만 호환합니다. 새로운 서명 키·API 서비스 계정을 생성하지 않습니다.
- 배포 단계는 기존 Play internal release와 동일한 `play-com-haebi-petworld` 동시실행 그룹을 사용하여 Edit 충돌을 줄입니다. Play Console에서 동시 수정하면 API Edit가 취소될 수 있습니다.

## 업로드 시 보존 범위

- API의 `/applications/com.haebi.petworld/edits/.../listings/ko-KR/{icon|featureGraphic|phoneScreenshots}`와 edit 생성/커밋/삭제만 허용합니다.
- 업로드 직전 파일 SHA·PNG 유효성 재검증 → 단일 Edit에서 대상 이미지 3종만 교체 → 업로드 응답 ID·SHA-256 및 배열 순서 확인 → Edit 1회 커밋 → 새로운 Edit로 조회 검증 → 검증 보고서 저장.
- 발행 여부 확인을 위해 이미지 수정 완료와 Google Play의 **심사 승인·스토어 노출**은 명확히 구분합니다.
- 오류가 커밋 전에 발생하면 편집을 가능한 한 폐기하며, 커밋 후 응답이 불분명한 경우 자동 재커밋하지 않습니다.
- AAB/APK, Play 테스트 트랙(`internal`, `alpha`), versionCode, 앱 서명, 가격, 스토어 설명 텍스트, 다른 언어 등록정보, 테스터 명단을 변경하지 않습니다.

## 병합 주의

현재 `main`의 Android CI 성공이 내부 테스트 자동 배포로 연결될 수 있습니다. 본 PR은 STORE-01 → STORE-02 → STORE-03 순서의 **stacked Draft PR**로 유지합니다. 관련 PR을 `main`에 병합하기 전에 기존 Play 바이너리 자동 배포의 변경 감지·승인 경계를 별도로 확인해야 합니다. **이미지 전용 PR도 무심코 병합하면 내부 테스트 자동 배포가 실행될 가능성이 있습니다.**

Google Play 공식 API 참고: https://developers.google.com/android-publisher/api-ref/rest/v3/edits.images
