# FloatDeck

Android live wallpaper with gyroscope parallax effect and floating character portraits.

> **User Guide:** [English](README.md) | [中文](USAGE.zh-CN.md) | [日本語](USAGE.ja-JP.md) | [한국어](USAGE.ko-KR.md)

## Project Structure
```
app/src/main/
├── kotlin/app/floatdeck/
│   ├── gl/
│   │   ├── FloatDeckRenderer.kt     # Main GL renderer (render loop, animation, touch)
│   │   ├── Shaders.kt               # GLSL source (portrait + background)
│   │   └── TextureLoader.kt         # Texture creation from assets/bitmaps
│   ├── sensor/
│   │   └── SensorHandler.kt         # Gyroscope/accelerometer listener
│   ├── service/
│   │   └── FloatDeckWallpaperService.kt  # Wallpaper service + EGL thread
│   ├── settings/
│   │   └── SettingsActivity.kt      # Compose settings UI
│   └── data/
│       ├── Models.kt                # Data classes (PortraitConfig, TemplateConfig)
│       ├── SettingsRepository.kt    # DataStore-backed user preferences
│       ├── Templates.kt             # Template JSON loader & layout calculator
│       └── RemoteTemplateLoader.kt  # Remote/local ZIP & directory import
└── assets/
    └── templates/                   # Built-in template assets (JSON + images)
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

| Theme Pack | Link |
|---|---|
| Honkai 3rd: Flame Chasers | [flame_chasers.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/flame_chasers.zip) |
| Honkai: Star Rail - Amphoreus Titans | [hsr_titans.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/hsr_titans.zip) |

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
