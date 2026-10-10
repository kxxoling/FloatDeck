# FloatDeck

[![F-Droid](https://img.shields.io/badge/Get%20it%20on%20F--Droid-1976D2?style=for-the-badge&logo=f-droid&logoColor=white)](https://f-droid.org/en/packages/app.floatdeck/)
[![GitHub Releases](https://img.shields.io/github/v/release/kxxoling/FloatDeck?style=for-the-badge&logo=github)](https://github.com/kxxoling/FloatDeck/releases)

Android live wallpaper with gyroscope parallax effect and floating character portraits.

> **User Guide:** [English](README.md) | [中文](USAGE.zh-CN.md) | [日本語](USAGE.ja-JP.md) | [한국어](USAGE.ko-KR.md)

## Project Structure

```
app/src/main/kotlin/app/floatdeck/
├── gl/        # OpenGL renderer, shaders, textures
├── sensor/    # Gyroscope / accelerometer input
├── service/   # Wallpaper service + EGL render thread
├── settings/  # Compose settings + license screens
└── data/      # Templates, preferences, remote import, update check
```

## Template System

Each template lives in a folder with a `template.json` and image files:

```json
{
  "id": "my_template",
  "name": "My Template",
  "wallpaper": "wallpaper.webp",
  "portraits": {
    "left": [{"file": "a.webp", "label": "A"}, ...],
    "right": [{"file": "b.webp", "label": "B"}, ...]
  }
}
```

#### Optional explicit positions

By default portraits are arranged by the built-in layout algorithm. A portrait
entry may instead pin its locked (keyguard) and/or unlocked placement with
screen-ratio coordinates (all fields except `x`/`y` optional; defaults:
`rotation: 0`, `scale: 1`, `tiltOffset: 1`):

```json
{
  "file": "a.webp", "label": "A",
  "locked":   {"x": 0.3, "y": 0.4, "rotation": 5, "scale": 0.9, "tiltOffset": 1.5},
  "unlocked": {"x": 0.05, "y": 0.5, "scale": 0.64}
}
```

Positions you adjust in-app (dragging portraits on the wallpaper, or the layout
editor) are saved as a per-template override file and take precedence over the
template's own values.

### Importing Templates

Templates can be imported from:

1. **Remote URL** — Enter a ZIP download URL in settings
2. **Local ZIP** — Select a ZIP file from device storage
3. **Local directory** — Select a folder containing template files

**ZIP format:** Must be structured as `folder_name/template.json` + `folder_name/*.png|jpg|webp`:

```
template_name/
├── template.json
├── wallpaper.webp
├── portrait_1.webp
└── portrait_2.webp
```

### Example Theme Packs

Download from [Releases](https://github.com/kxxoling/FloatDeck/releases) and import via Remote URL or Local ZIP:

| Theme Pack                           | Link                                                                                                  |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| Honkai 3rd: Flame Chasers            | [flame_chasers.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/flame_chasers.zip) |
| Honkai: Star Rail - Amphoreus Titans | [hsr_titans.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/hsr_titans.zip)       |

> **Note:** Honkai 3rd and Honkai: Star Rail assets are copyrighted by miHoYo Co., Ltd.

**Validation rules:**

- `template.json` is required with valid `id`, `wallpaper`, and `portraits` fields
- Only `.png`, `.jpg`, `.jpeg`, `.webp` images allowed
- Max ZIP size: 50MB, max single file: 10MB
- Path traversal (`..`) is blocked
- Template ID: alphanumeric, underscores, hyphens only

## Build

```bash
./gradlew assembleDebug
```

## Install & Set

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start app.floatdeck/.settings.SettingsActivity
```

Or: Settings → Wallpaper → Live Wallpapers → FloatDeck

## CI/CD

- **CI**: push/PR to `main`/`dev` → ktlint + test + debug build
- **Release**: GitHub Release published → signed APK → attached to release

## License

MIT
