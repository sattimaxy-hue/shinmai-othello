package jp.satti.shinmaiothello.dat

/**
 * Meta Wearables Device Access Toolkit（DAT）への接続境界。
 *
 * 実装方針（docs/DESIGN.md）:
 * - アプリはスマホ側で動き、グラスはカメラ＋表示の周辺機器（DAT のモデル）。
 * - カメラ: DeviceSession に Camera を追加し、StreamConfiguration で
 *   解像度とフレームレートを指定する。実効上限は縦720x1280、fps は 2/7/15/24/30。
 *   本アプリは変化検知用に 2fps / MEDIUM(504x896) から始める。
 * - 表示: Ray-Ban Display の glasses.display に、HudState を描いた画像を渡す想定。
 *
 * 注意: DAT は開発者プレビューで API が動き得る。具体的なクラス名・Maven座標は
 * 公式ドキュメント（wearables.developer.meta.com）と公式リポジトリ
 * （facebook/meta-wearables-dat-android）の現行版で必ず確認してから実装すること。
 * このファイルは「境界インターフェース＋モック」のみで、DAT実バインディングは未実装。
 */

data class StreamConfig(
    val width: Int = 504,   // MEDIUM
    val height: Int = 896,
    val fps: Int = 2        // 変化検知には 2fps で十分。電池・帯域対策
)

class CameraFrame(
    val pixels: IntArray,   // ARGB
    val width: Int,
    val height: Int,
    val timestampMs: Long
)

interface GlassesClient {
    /** カメラストリームを開始する。フレームは onFrame で順次届く。 */
    fun startCamera(config: StreamConfig, onFrame: (CameraFrame) -> Unit)

    /** 静止画を1枚撮る（認識の本命はこちら。ストリームは変化検知用）。 */
    fun capturePhoto(onPhoto: (CameraFrame) -> Unit)

    /** HUD 画像（PNG想定）をグラスのディスプレイに表示する。 */
    fun showHudImage(pngBytes: ByteArray)

    fun close()
}

/**
 * 実機なしでパイプラインを回すためのモック。
 * DAT にも Mock Device Kit があるが、アプリ内ロジックの検証はこれで足りる。
 */
class MockGlassesClient : GlassesClient {
    var lastHudImage: ByteArray? = null
        private set

    override fun startCamera(config: StreamConfig, onFrame: (CameraFrame) -> Unit) {
        // モックではフレームを流さない。テスト側から pushFrame で注入する想定は将来拡張。
    }

    override fun capturePhoto(onPhoto: (CameraFrame) -> Unit) {
        // モック: 1x1 の空フレームを返す（呼び出し側は実データ前提にしないこと）
        onPhoto(CameraFrame(IntArray(1), 1, 1, System.currentTimeMillis()))
    }

    override fun showHudImage(pngBytes: ByteArray) {
        lastHudImage = pngBytes
    }

    override fun close() {}
}

/**
 * DAT 実装の置き場。具体的な接続手順は公式ガイドの順序に従う:
 * 登録（Meta AI アプリで連携許可）→ デバイスセッション確立 → カメラ権限 →
 * ストリーム開始 → 表示。
 *
 * TODO(dat): 公式SDKを取り込み、DeviceSession / Camera / StreamConfiguration /
 * glasses.display への実バインディングを実装する。
 */
class DatGlassesClient : GlassesClient {
    override fun startCamera(config: StreamConfig, onFrame: (CameraFrame) -> Unit) {
        throw NotImplementedError("TODO(dat): DAT SDK binding not implemented yet")
    }

    override fun capturePhoto(onPhoto: (CameraFrame) -> Unit) {
        throw NotImplementedError("TODO(dat): DAT SDK binding not implemented yet")
    }

    override fun showHudImage(pngBytes: ByteArray) {
        throw NotImplementedError("TODO(dat): DAT SDK binding not implemented yet")
    }

    override fun close() {}
}
