# FloatDeck ユーザーガイド

FloatDeck は、デバイスのジャイロスコープ/加速度センサーを使用して3Dパララックス深度効果を作成し、キャラクターの肖像画を浮遊カードとして表示するAndroidライブ壁紙アプリです。

## 機能

- ジャイロスコープベースの3Dパララックス効果
- キャラクター肖像画を浮遊カードとしてインポート・表示
- カスタマイズ可能なテンプレートレイアウト
- ロック画面サポート、スムーズなトランジション
- 慣性ベースの物理演算、自然な動き
- 完全オープンソース、トラッカーなし、インターネット権限なし

## インストール

1. [Releases](https://github.com/kxxoling/FloatDeck/releases) から最新のAPKをダウンロード
2. APKをインストール（不明なソースのインストールを許可する必要がある場合があります）
3. アプリを開くか、設定 → 壁紙 → ライブ壁紙 → FloatDeck を選択

## テンプレートの使用

### テンプレートのインポート

テンプレートは以下の方法でインポートできます：

1. **リモートURL** — 設定でZIPダウンロードリンクを入力
2. **ローカルZIP** — デバイスのストレージからZIPファイルを選択
3. **ローカルディレクトリ** — テンプレートファイルを含むフォルダを選択

### テンプレート形式

ZIPファイルには `template.json` と画像ファイルが含まれている必要があります：

```
template_name/
├── template.json
├── wallpaper.webp
├── portrait_1.webp
└── portrait_2.webp
```

`template.json` の例：

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

### サンプルテーマパック

[Releases](https://github.com/kxxoling/FloatDeck/releases) からダウンロードしてインポート：

| テーマパック                              | リンク                                                                                                |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| 崩壊3rd：十三英傑                         | [flame_chasers.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/flame_chasers.zip) |
| 崩壊：スターレイル - アンフォレウスの巨人 | [hsr_titans.zip](https://github.com/kxxoling/FloatDeck/releases/download/v0.1.0/hsr_titans.zip)       |

> **注意：** 崩壊3rd と 崩壊：スターレイル の素材は miHoYo Co., Ltd. の著作権です。

### 検証ルール

- `template.json` は有効な `id`、`wallpaper`、`portraits` フィールドが必要
- `.png`、`.jpg`、`.jpeg`、`.webp` の画像のみ許可
- ZIP最大50MB、単一ファイル最大10MB
- ファイル名に `..` などの特殊パス記号を含めることはできません
- テンプレートID：英数字、アンダースコア、ハイフンのみ

## 壁紙の設定

設定 → 壁紙 → ライブ壁紙 → FloatDeck

またはADBを使用：

```bash
adb shell am start app.floatdeck/.settings.SettingsActivity
```
