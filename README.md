# 新米オセロ・アドバイザー（shinmai-othello）

Meta Ray-Ban Display（愛称: 新米）で動かすオセロ助言アプリの開発リポジトリ。

実物のオセロ盤をグラスのカメラで見て、スマホ側で盤面を認識し、最適手を
グラスのミニ盤面HUDにカーソル付きで出す。『宝石の煌めき』アドバイザーの
Step 0 として、共通パイプライン（撮像→認識→助言→提示）を一番簡単な盤で
先に通すためのプロジェクト。

## 構成

```
.
├── docs/DESIGN.md        新米向けの設計（DATの使い方、変化検知、HUD提示）
├── prototype/            Web試作（検証済み）: index.html + othello.js
│                         写真認識→助言→ミニ盤面HUDプレビューまで一通り動く
└── android/              Android コンパニオンアプリ（Kotlin スケルトン）
    └── app/src/main/java/jp/satti/shinmaiothello/
        ├── core/         OthelloEngine（Web版から移植）/ HudModel
        ├── vision/       BoardRecognizer（四隅＋色サンプリング）/ 変化検知
        ├── dat/          DAT接続の境界インターフェース＋モック（実バインディングは未実装）
        └── MainActivity.kt  エンジン自己診断を表示するだけの最小画面
```

## 動かし方

- Web試作: `prototype/index.html` をブラウザで開くだけ（サーバ不要）。
  写真を選んで四隅を合わせると盤面が復元され、「おすすめを聞く」で
  最適手にカーソルが出る。新米HUDプレビュー（ミニ盤面）も付いている。
- Android: Android Studio で `android/` を開く。現状はスケルトンで、
  起動すると初期局面の助言計算結果が表示される。
  エンジンの単体テストは `app/src/test/` にある。

## プラットフォームの要点（2026-11-07 調査）

- 経路は Meta Wearables Device Access Toolkit（DAT）。アプリはスマホで動き、
  グラスはカメラと表示の周辺機器。
- カメラストリームの上限は縦720×1280、fps は 2/7/15/24/30。
  Bluetooth Classic 経由で自動的に画質調整が入る。公式も低解像度・低fpsを
  推奨する場面がある。本アプリは 2fps の変化監視＋静止時の写真取得で設計。
- 表示は右眼600×600のHUD固定で空間アンカーはない。実盤への重ね合わせは
  できないため、答えは「ミニ盤面＋カーソル＋座標」で出す。
- DAT は開発者プレビューの段階で、一般公開（App Store 等）は未対応。
  本リポジトリは自分用・テスト用の試作を目的とする。

## ステータス

- Web試作: 動作（エンジンは自動テスト6件合格、透視補正は検証済み）
- Kotlin エンジン: Web版から移植済み（テストは JUnit 版を用意。実機ビルド未検証）
- DAT 実バインディング: 未実装。`dat/DatGlassesClient.kt` の TODO から着手する

## 関連

- 『宝石の煌めき』アドバイザー設計（本編）: ローカルの
  `~/workspace/splendor-advisor/DESIGN.md`（Step 0 として本プロジェクトを参照）
- 姉妹プロジェクト: SHINMAI PORTAL（新米用HUD） satti3104/-shinmai-portal
