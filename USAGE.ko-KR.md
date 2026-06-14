# FloatDeck 사용 가이드

FloatDeck은 기기의 자이로스코프/가속도계 센서를 사용하여 3D 패럴랙스 깊이 효과를 만들고 캐릭터 초상화를 부유 카드 형태로 표시하는 Android 라이브 월페이퍼 앱입니다.

## 기능

- 자이로스코프 기반 3D 패럴랙스 효과
- 캐릭터 초상화를 부유 카드로 가져오기 및 표시
- 사용자 정의 가능한 템플릿 레이아웃
- 잠금 화면 지원, 부드러운 전환
- 관성 기반 물리 효과, 자연스러운 움직임
- 완전 오픈소스, 추적기 없음, 인터넷 권한 없음

## 설치

1. [Releases](https://github.com/kxxoling/FloatDeck/releases)에서 최신 APK 다운로드
2. APK 설치 (알 수 없는 출처 설치 허용 필요할 수 있음)
3. 앱을 열거나 설정 → 배경화면 → 라이브 배경화면 → FloatDeck 선택

## 템플릿 사용

### 템플릿 가져오기

템플릿은 다음 방법으로 가져올 수 있습니다:

1. **원격 URL** — 설정에서 ZIP 다운로드 링크 입력
2. **로컬 ZIP** — 기기 저장소에서 ZIP 파일 선택
3. **로컬 디렉토리** — 템플릿 파일이 포함된 폴더 선택

### 템플릿 형식

ZIP 파일에는 `template.json`과 이미지 파일이 포함되어야 합니다:

```
template_name/
├── template.json
├── wallpaper.webp
├── portrait_1.webp
└── portrait_2.webp
```

`template.json` 예시:

```json
{
  "id": "my_template",
  "name": "My Template",
  "wallpaper": "wallpaper.webp",
  "portraits": {
    "left": [{ "file": "a.webp", "label": "A" }],
    "right": [{ "file": "b.webp", "label": "B" }]
  }
}
```

### 샘플 테마 팩

[Releases](https://github.com/kxxoling/FloatDeck/releases)에서 다운로드하여 가져오기:

| 테마 팩                            | 링크                                                                                                  |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------- |
| 붕괴3rd: 13인의 영웅               | [flame_chasers.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/flame_chasers.zip) |
| 붕괴: 스타레일 - 앰포레우스의 거인 | [hsr_titans.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/hsr_titans.zip)       |

> **참고:** 붕괴3rd 및 붕괴: 스타레일 자산은 miHoYo Co., Ltd.의 저작권입니다.

### 검증 규칙

- `template.json`에 유효한 `id`, `wallpaper`, `portraits` 필드가 필요
- `.png`, `.jpg`, `.jpeg`, `.webp` 이미지만 허용
- ZIP 최대 50MB, 단일 파일 최대 10MB
- 파일 이름에 `..` 등의 특수 경로 기호를 포함할 수 없습니다
- 템플릿 ID: 영숫자, 밑줄, 하이픈만 허용

## 배경화면 설정

설정 → 배경화면 → 라이브 배경화면 → FloatDeck

또는 ADB 사용:

```bash
adb shell am start app.floatdeck/.settings.SettingsActivity
```
