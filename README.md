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
├── webapp/               グラス実機用 Web Apps 版（600x600 / D-pad操作）
│                         GitHub Pages で公開し、Meta AI アプリから Web App として登録する
└── android/              Android コンパニオンアプリ（DAT 1.0.1 実バインディング）
    └── app/src/main/java/jp/satti/shinmaiothello/
        ├── core/         OthelloEngine（Web版から移植）/ HudModel
        ├── vision/       BoardRecognizer（四隅＋色サンプリング）/ 変化検知
        ├── dat/          GlassesClient 境界＋モック＋DatGlassesClient（実バインディング）
        ├── hud/          HudRenderer（HudState→600x600画像）
        └── MainActivity.kt  登録→接続→監視→撮影→認識→HUD送信の開発用画面
```

## 動かし方

- グラス版（Web Apps）: GitHub Pages の `webapp/` を Meta AI アプリで
  Web App として登録すると新米のグラス上で動く。操作は D-pad
  （矢印キー＋Enter）: 矢印でカーソル移動、Enter 1回で反転プレビュー、
  もう1回で確定。盤面は実対局の着手をそのまま入力する記録式で、
  手番ごとに最適手（▼＋黄色リング）と理由を出す。カメラ認識は
  Web Apps では使えないため含まない（認識は DAT ネイティブ側の担当）。
  PCブラウザでも矢印キー＋Enter（クリック可、Uキーで一手戻る）で試せる。
- Web試作: `prototype/index.html` をブラウザで開くだけ（サーバ不要）。
  写真を選んで四隅を合わせると盤面が復元され、「おすすめを聞く」で
  最適手にカーソルが出る。新米HUDプレビュー（ミニ盤面）も付いている。
- Android（DAT版）: Android Studio で `android/` を開く（Gradle ラッパーは
  同梱していないので Android Studio に生成させる）。前提: Meta AI アプリで
  対象グラスの開発者モードを有効化し、実機（Android 12+）にインストール。
  操作は画面のボタン順: 1. 登録（Meta AI 連携）→ 2. 接続して監視開始 →
  カメラ権限を許可 → 静止検知で自動撮影・認識（手動撮影ボタンもあり）。
  エンジンの単体テストは `app/src/test/` にある。

## プラットフォームの要点（2026-11-07 調査）

- **Web Apps 経路（2026-05 追加）**: HTML/CSS/JS を公開HTTPSに置けば
  グラス上で直接動く。登録は Meta AI アプリ（開発者モード→
  App Connections → Web Apps）。制約: 600x600固定・スクロールなし、
  入力は D-pad（矢印キー＋Enter イベント）、黒は透明（加算表示）、
  カメラ/マイクは不可。→ グラス版はこの経路で実装（`webapp/`）。
- DAT 経路: Meta Wearables Device Access Toolkit（DAT 1.0、2026-09-30）。
  アプリはスマホで動き、グラスはカメラと表示の周辺機器。カメラが
  使えるのはこちらのみ。
- カメラストリームの上限は縦720×1280、fps は 2/7/15/24/30。
  Bluetooth Classic 経由で自動的に画質調整が入る。公式も低解像度・低fpsを
  推奨する場面がある。本アプリは 2fps の変化監視＋静止時の写真取得で設計。
- 表示は右眼600×600のHUD固定で空間アンカーはない。実盤への重ね合わせは
  できないため、答えは「ミニ盤面＋カーソル＋座標」で出す。
- DAT は開発者プレビューの段階で、一般公開（App Store 等）は未対応。
  本リポジトリは自分用・テスト用の試作を目的とする。

## ステータス

- グラス版 Web Apps: 実装・公開済み
  （https://sattimaxy-hue.github.io/shinmai-othello/webapp/）。
  グラス実機での表示確認はこれから。
- DAT 実バインディング: 実装済み（SDK 1.0.1、公式サンプルで API 確認のうえ
  接続・カメラ・撮影・表示を配線。詳細は docs/DESIGN.md §8）。
  Android Studio でのビルドと実機動作は未検証。
  四隅キャリブレーションUIは未実装（認識は仮置きの四隅で動作）。
- Web試作: 動作（エンジンは自動テスト6件合格、透視補正は検証済み）
- Kotlin エンジン: Web版から移植済み（テストは JUnit 版を用意。実機ビルド未検証）

## 関連

- 『宝石の煌めき』アドバイザー設計（本編）: ローカルの
  `~/workspace/splendor-advisor/DESIGN.md`（Step 0 として本プロジェクトを参照）
- 姉妹プロジェクト: SHINMAI PORTAL（新米用HUD） satti3104/-shinmai-portal
