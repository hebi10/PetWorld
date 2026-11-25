# 디자인 검토용 실제 화면 캡처 (일회성 Draft)

기준 앱 소스: PR #66 5affb5ee41d2bf37167a1d00e2d1a7bfda7da0ff. FB-07-B는 서버 측 작업이므로 Android 앱 본체는 동일하다. 이 PR은 앱 기능/디자인이나 최종 main 전용 QA·배포 정책을 변경하지 않는다.

- debug: 실제 Android Pixel 6 API 35, 기존 PetWorldScreenCaptureTest의 라이트·다크 합성 로컬 데이터 전체 캡처.
- firebaseDev: 합성 개발 config에서 Google 로그인하지 않은 계정 화면 2상태씩 라이트·다크 캡처. 실제 계정/동기화/충돌/탈퇴 실행 캡처는 포함하지 않는다.
- PR의 정확한 HEAD를 checkout하고 SHA/variant/scope provenance를 함께 저장한다. 실제 PNG만 증빙으로 업로드한다. 로그·토큰·APK·실계정 정보는 artifact에 올리지 않는다.
- Actions 결과 및 이미지 artifact가 생성된 경우에만 실제 캡처 확보로 판정한다. 부분 실패는 해당 범위 실패로 표시한다. Play/Firebase 배포 없음. 최종 release QA 대체 금지.
