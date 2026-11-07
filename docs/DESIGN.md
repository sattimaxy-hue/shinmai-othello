# 新米（Ray-Ban Display）版 設計メモ v0.1

日付: 2026-11-07
元設計: 『宝石の煌めき』アドバイザー設計書の Step 0（オセロで共通パイプラインを検証）

## 1. 全体像

```
[Ray-Ban Display] ──DAT──> [スマホ: コンパニオンアプリ] ──DAT表示──> [Ray-Ban Display]
   カメラ                      変化検知 → 写真取得 → 盤面認識            ミニ盤面HUD
   (2fps監視 + 静止画)         → 修正UI（低確信マスのみ） → 助言計算    （座標＋カーソル）
```

- 計算はすべてスマホ側。グラスにアプリ本体は載らない（DAT のモデル）。
- 盤面認識と助言エンジンは Web試作（prototype/）で検証済みの方式をそのまま移植する。

## 2. 撮像: 常時解析しない

- ストリームは StreamConfiguration で **2fps / MEDIUM(504×896)** を指定する。
  目的は解析ではなく変化検知。帯域（Bluetooth Classic）と電池・発熱への配慮。
- フレームを縮小グレースケール化し、FrameChangeDetector で差分を見る。
  - 差分が大きい = 手や石が動いている → 何もしない
  - 差分が小さい状態が続く = 盤が静止 → その時点で写真取得（または高品質フレームを1枚使う）
- 写真が前回の確定盤面と同じなら無視。1マスでも変わっていれば認識→助言を回す。
- 手番の概念と結びつける: 相手の着手で盤が変わったことを検知したら、
  自分の手番が来たとみなして助言まで自動で出すのが最終形。

## 3. 四隅キャリブレーション

Web試作では写真の上で四隅をドラッグしたが、グラスではそれができない。そこで:

- セッション開始時（卓に着いたとき）に1回だけ、スマホ画面で実施する。
  グラス映像をスマホに表示し、盤の四隅をタップ／ドラッグで合わせる。
- 対局中は四隅を保持する。頭の動きで画角は変わるため、四隅は「そのフレームでの
  盤の位置」として、静止フレームごとに盤の緑色領域から再推定し、保持値との
  ズレが大きいときだけ再キャリブレーションを促す（自動検出は段階導入:
  v1 は静止画ごとに手動確認でも可とする）。
- 認識は BoardRecognizer（透視補正＋マス中心サンプリング）。確信度が低いマスは
  スマホの修正UIで直してから助言に進む（推測で埋めない原則は本編と共通）。

## 4. 助言と提示

- エンジン: OthelloEngine（序中盤5手先、空き12以下は全読み）。Web版と同一ロジック。
- 提示は HudModel を画像化して送る（mwdat-display はコンポーネントDSL のため、
  画像は image コンポーネントで送る。詳細は §8）。
  - 左: おすすめ座標を大きく（例 f5）
  - 右: ミニ盤面。最適手に▼カーソル＋リング、2位・3位にバッジ
  - 下: 理由を2要素まで
  - 空間アンカーがないので実盤への重ね合わせはしない。ミニ盤面で完結させる。
- 助言の鮮度: 盤面が変わったら古い助言は即座に消す（Web試作の stateKey 方式を踏襲）。

## 5. 制約と割り切り（2026-11-07 時点の調査に基づく）

- ストリーム上限は縦720×1280。fps は 2/7/15/24/30 から選択。帯域に応じて
  自動ラダーで解像度→fps の順に落とされる。フレーム圧縮で画質が落ちるため、
  認識は「石の明暗」レベルの単純さに保つ（オセロが Step 0 に選ばれた理由）。
- 長時間の連続ストリーミングは電池・発熱で止まり得る。前提にしない。
- DAT は開発者プレビュー。公開配布は未対応のため、自分用・招待テスト用に限定。
- DAT の具体的な API（クラス名・依存座標）は公式ドキュメントの現行版で
  確認してから書く。推測で書いたコードを「動く」と言わない。

## 6. 実装ステップ

1. [済] Web試作でパイプライン検証（認識・エンジン・HUDモデル）
2. [済] Kotlin へエンジン／認識／HUDモデルを移植（本リポジトリ）
3. [済] グラス版 Web Apps（webapp/）を実装・公開（D-pad操作の手入力式）
4. [済] DAT 実バインディング実装（下記 §8。コードのみ・実機ビルド未検証）
5. [次] Android Studio でビルドを通し、単体テストを回す
6. 実グラスのカメラで盤面認識を通す（四隅キャリブレーションUIが前提）
7. 実盤・実卓で1ゲーム通し検証（認識率、助言レイテンシ、電池の持ち）

## 8. DAT 実装メモ（2026-11-07、SDK 1.0.1）

公式リポジトリ meta/meta-wearables-dat-android（AGENTS.md と
samples/CameraAccess・DisplayAccess）で API を確認してから実装した。

- 依存: Maven Central の com.meta.wearable（mwdat-core / mwdat-camera /
  mwdat-display / mwdat-mockdevice）すべて 1.0.1。追加リポジトリ不要。
- ビルド要件はサンプルに一致: compileSdk/targetSdk 36、minSdk 31、
  AGP 8.11.1、Kotlin 2.2.21、JVM 17。
- マニフェスト: meta-data APPLICATION_ID / CLIENT_TOKEN は開発者モード用に
  プレースホルダ "0"。権限は BLUETOOTH / BLUETOOTH_CONNECT / INTERNET。
  登録コールバック用に自アプリスキーム shinmaiothello の VIEW フィルタを追加。
- 接続: Wearables.initialize → startRegistration（Meta AI 連携）→
  createSession（表示対応デバイスに絞る AutoDeviceSelector）→
  state が STARTED になってから addDisplay / addCamera。
- カメラ権限は DAT パーミッション（Permission.CAMERA）で、リクエストは
  Wearables.RequestPermissionContract を Activity から使う。
  ストリームは MEDIUM(504x896) / 2fps / 非圧縮。静止画は stream.capturePhoto()
  （PhotoData は Bitmap か HEIC。HEIC は EXIF の向きを適用してから使う）。
- 表示: mwdat-display は生フレームバッファではなくコンポーネントDSL
  （sendContent { flexBox { ... } }）。HUD は HudRenderer で 600x600 画像にし、
  image コンポーネント（ImageSize.FILL）で送る。ネイティブコンポーネントで
  盤面を組む案は将来の改善（画像より鮮明になる可能性がある）。
- 未検証の前提: 非圧縮 VideoFrame.buffer のレイアウトは公式に明記がなく、
  「YUV で先頭が輝度プレーン」と仮定して変化検知に使っている。
  実機で違えば変化検知のみ差し替える（写真→Bitmap 経由の認識は無事）。
- 検証状況: Android SDK のない環境で、調査済みAPIに基づくスタブに対して
  全 Kotlin ソースのコンパイルを通した（構文・型整合の確認）。
  実SDKでのビルド可否と実機動作は未検証であり、次のマイルストーン。
- 当面の割り切り: 四隅キャリブレーションUIは未実装で、認識時は写真の
  内側6%を四隅とする仮置き。手番は認識ごとに交互の簡易管理
  （パス時は MainActivity の「手番を切替」ボタンで手動調整）。
- Gradle ラッパーは同梱していない。Android Studio で開いて生成させる。

## 7. 本編（Splendor）への引き継ぎ

- 変化検知→静止画→認識→修正→助言→HUD の骨格はそのまま使う。
- 変わるのは認識対象（カードの数字・色）とエンジン（合法手列挙＋評価関数）と
  HUD の情報量。オセロで詰めた「確信度と修正フロー」「助言の鮮度管理」が効く。

## 9. Even G2 版メモ（2026-11-07、SDK 0.0.16 / 公式ドキュメント確認済み）

- G2 はカメラなし。撮影は Even アプリ内 WebView からブリッジ API
  `captureImageFromCamera()` / `pickImageFromAlbum()` でスマホ側カメラ/アルバムを使い、
  結果は base64 で返る（app.json に camera / album 権限が必須）。
- 表示は 576×288 キャンバスへのコンテナ合成。画像コンテナは最大 288×144 のため、
  盤面 288×288 を上下2枚の画像コンテナで送る。画像データはグレースケール bytes を
  `updateImageRawData` で送信（呼び出しは直列化、1回ごとに画像パスを 100ms 占有）。
- イベント受け口は isEventCapture: 1 のテキストコンテナが1つだけ。
  CLICK は eventType が undefined に化ける場合があるため、そのケースもクリック扱いにする。
- 終了は個人用ビルドでは `shutDownPageContainer(0)`。ストア提出時はルートで
  `shutDownPageContainer(1)` が必須だが、1 の確認ダイアログ表示後はそのセッションで
  updateImageRawData が sendFailed になる既知不具合（公式リポジトリ issue #18）がある。
- 配布は .ehpk: `evenhub pack app.json <dist> -o app.ehpk` → `evenhub create/upload`。
  開発中は `evenhub qr --url http://<LAN IP>:5173` で QR サイドロード（開発者モード必須、
  Even アプリを背面化すると切れる）。ビルド成果物のホスティング自体は不要。
- ソースは eveng2-src/（Vite + TypeScript）、成果物は eveng2/（GitHub Pages でも配信し、
  シミュレータ確認に使う）。
- 未検証: 実機での画像 bytes の解釈（1px=1byte 前提）、ブリッジ経由カメラの画質と認識率、
  スクロール方向と候補切替の対応感。
