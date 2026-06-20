# FloatDeck 使用指南

FloatDeck 是一款 Android 动态壁纸应用，利用设备的陀螺仪/加速度计传感器创建 3D 视差深度效果，让角色立绘以浮动卡片的形式呈现在屏幕上。

## 功能特性

- 基于陀螺仪的 3D 视差效果
- 导入并展示角色立绘作为浮动卡片
- 可自定义模板布局
- 锁屏支持，平滑过渡
- 惯性物理效果，自然流畅
- 完全开源，无追踪器（联网仅用于远程模板下载与更新检查，不收集任何数据）

## 安装

1. 从 [Releases](https://github.com/kxxoling/FloatDeck/releases) 下载最新 APK
2. 安装 APK（可能需要允许未知来源安装）
3. 打开应用或前往：设置 → 壁纸 → 动态壁纸 → FloatDeck

## 使用模板

### 导入模板

模板可以通过以下方式导入：

1. **远程 URL** — 在设置中输入 ZIP 下载链接
2. **本地 ZIP** — 从设备存储中选择 ZIP 文件
3. **本地目录** — 选择包含模板文件的文件夹

### 模板格式

ZIP 文件必须包含 `template.json` 和图片文件：

```
template_name/
├── template.json
├── wallpaper.webp
├── portrait_1.webp
└── portrait_2.webp
```

`template.json` 示例：

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

### 示例主题包

从 [Releases](https://github.com/kxxoling/FloatDeck/releases) 下载并导入：

| 主题包                        | 链接                                                                                                  |
| ----------------------------- | ----------------------------------------------------------------------------------------------------- |
| 崩坏3：逐火十三英桀           | [flame_chasers.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/flame_chasers.zip) |
| 崩坏：星穹铁道 - 翁法罗斯泰坦 | [hsr_titans.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/hsr_titans.zip)       |

> **注意：** 崩坏3 和 崩坏：星穹铁道 素材版权归 miHoYo Co., Ltd. 所有。

### 验证规则

- `template.json` 必须包含有效的 `id`、`wallpaper` 和 `portraits` 字段
- 仅允许 `.png`、`.jpg`、`.jpeg`、`.webp` 图片
- ZIP 最大 50MB，单个文件最大 10MB
- 文件名不能包含 `..` 等特殊路径符号
- 模板 ID：仅允许字母数字、下划线和连字符

## 设置壁纸

设置 → 壁纸 → 动态壁纸 → FloatDeck

或使用 ADB：

```bash
adb shell am start app.floatdeck/.settings.SettingsActivity
```
