# 미궁 제도실 (안드로이드)

미궁 제도실 플로어 에디터를 휴대폰에서 쓰는 앱. 편집기 HTML은 `app/src/main/assets/index.html`.

- main에 올리면 GitHub Actions가 APK를 만들어 릴리스에 올린다.
- `app/native-level`을 올리지 않으면 편집기 HTML만 바뀐 업데이트 → 앱이 켤 때 몰래 받아 다음 실행 때 적용.
- `app/native-level`을 올리면 앱(APK)을 새로 까는 업데이트 → 앱이 창으로 알린다.
- 릴리스 설명은 `release.json`의 notes.
