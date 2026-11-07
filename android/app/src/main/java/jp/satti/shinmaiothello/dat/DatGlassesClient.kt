package jp.satti.shinmaiothello.dat

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ImageSize
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Meta Wearables Device Access Toolkit（DAT）への接続境界。
 *
 * 実装方針（docs/DESIGN.md）:
 * - アプリはスマホ側で動き、グラスはカメラ＋表示の周辺機器（DAT のモデル）。
 * - カメラ: DeviceSession に Camera を追加し、StreamConfiguration で
 *   解像度とフレームレートを指定する。実効上限は縦720x1280、fps は 2/7/15/24/30。
 *   本アプリは変化検知用に 2fps / MEDIUM(504x896) から始める。
 * - 表示: mwdat-display はコンポーネントDSL（flexBox/text/image 等）で、
 *   生のフレームバッファAPIはない。HUDは画像化して image コンポーネントで送る。
 *
 * API の根拠: 公式リポジトリ meta/meta-wearables-dat-android（DAT 1.0.1）の
 * AGENTS.md と samples/CameraAccess・samples/DisplayAccess の実装に合わせた。
 * 実機ビルド・実機動作は未検証（Android Studio でのビルド確認が次工程）。
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
 * DAT 実バインディング（1.0.1）。
 *
 * 接続手順（公式サンプル準拠）:
 * 1. Wearables.initialize（本クラスの init で実施）
 * 2. startRegistration(activity) で Meta AI アプリ連携（ユーザー操作）
 * 3. createSession(AutoDeviceSelector) → state が STARTED になってから
 *    addDisplay / addCamera（STARTED 前に追加すると SESSION_IDLE で失敗する）
 * 4. カメラは DAT パーミッション（Permission.CAMERA）の許可が別途必要。
 *    リクエストは Meta AI アプリへ遷移するため、Activity 側の
 *    ActivityResultLauncher（Wearables.RequestPermissionContract）経由で行い、
 *    結果を onPermissionResult で受け取る配線になっている。
 *
 * 未検証の前提（実機で確認すること）:
 * - 非圧縮 VideoFrame.buffer は YUV で、先頭が輝度(Y)プレーンであること
 *   （公式ドキュメントは「YUV」とのみ記載しレイアウトは明記していない）。
 *   変化検知は輝度だけで足りるため、この前提で輝度を取り出している。
 *   もし実機のレイアウトが違えば、変化検知だけが壊れる（写真認識は Bitmap 経由で無事）。
 */
class DatGlassesClient(context: Context) : GlassesClient {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableStateFlow("未初期化")
    /** UI 表示用の状態メッセージ（接続・権限・エラー等）。 */
    val status: StateFlow<String> = _status.asStateFlow()

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var display: Display? = null

    private var sessionStarted = false
    private var cameraAttached = false
    private var displayReady = false
    private var streaming = false

    private var pendingConfig: StreamConfig? = null
    private var frameCallback: ((CameraFrame) -> Unit)? = null
    private var frameJob: Job? = null

    /**
     * Activity 側で用意した権限ランチャーを呼ぶ受け口。
     * MainActivity が registerForActivityResult(Wearables.RequestPermissionContract())
     * の launcher.launch をここに配線する。
     */
    var permissionRequester: ((Permission) -> Unit)? = null

    init {
        Wearables.initialize(appContext).onFailure { error, _ ->
            setStatus("DAT初期化に失敗: ${error.description}")
        }
        setStatus("初期化済み（未接続）")
    }

    private fun setStatus(msg: String) {
        _status.value = msg
    }

    /** Meta AI アプリでの連携登録を開始する（ユーザー操作が入る）。 */
    fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    /** セッションを作成して開始する。能力の追加は STARTED を観測してから行う。 */
    fun connect() {
        if (session != null) {
            setStatus("接続済み（または接続処理中）")
            return
        }
        setStatus("セッション作成中…")
        val result = Wearables.createSession(AutoDeviceSelector(filter = { it.isDisplayCapable() }))
        result.onSuccess { created ->
            session = created
            observeSession(created)
            created.start()
            setStatus("セッション開始中…")
        }
        result.onFailure { error, _ ->
            setStatus("セッション作成に失敗: ${error.description}")
        }
    }

    private fun observeSession(s: DeviceSession) {
        scope.launch {
            s.state.collect { state ->
                when (state) {
                    DeviceSessionState.STARTED -> {
                        if (!sessionStarted) {
                            sessionStarted = true
                            setStatus("接続した")
                            attachDisplay()
                            ensureCameraPermissionThenAttach()
                        }
                    }
                    DeviceSessionState.STOPPED -> {
                        sessionStarted = false
                        cameraAttached = false
                        streaming = false
                        setStatus("切断された")
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            s.errors.collect { e -> setStatus("セッションエラー: $e") }
        }
    }

    // ---- カメラ権限（DAT パーミッション） ----

    private suspend fun ensureCameraPermissionThenAttach() {
        val result = Wearables.checkPermissionStatus(Permission.CAMERA)
        result.onSuccess { status ->
            if (status == PermissionStatus.Granted) {
                scope.launch { attachCamera() }
            } else {
                setStatus("カメラ権限が未許可。「カメラ権限を許可」ボタンから許可してね")
            }
        }
        result.onFailure { error, _ ->
            setStatus("権限確認に失敗: ${error.description}")
        }
    }

    /** 権限リクエストを開始する（Meta AI アプリへ遷移する）。 */
    fun requestCameraPermission() {
        val req = permissionRequester
        if (req == null) {
            setStatus("権限リクエストの受け口が未配線")
            return
        }
        req(Permission.CAMERA)
    }

    /** Activity のランチャーから結果を受け取る。 */
    fun onPermissionResult(status: PermissionStatus) {
        if (status == PermissionStatus.Granted) {
            setStatus("カメラ権限OK")
            if (sessionStarted) scope.launch { attachCamera() }
        } else {
            setStatus("カメラ権限が拒否された")
        }
    }

    // ---- カメラ ----

    private suspend fun attachCamera() {
        val s = session ?: return
        if (cameraAttached) {
            maybeStartStreaming()
            return
        }
        val cfg = pendingConfig ?: StreamConfig()
        val result = s.addCamera(
            StreamConfiguration(
                videoQuality = qualityFor(cfg),
                frameRate = cfg.fps,
                compressVideo = false // 非圧縮（YUV）で受け、輝度で変化検知する
            )
        )
        result.onSuccess { cam ->
            camera = cam
            stream = cam.stream
            cameraAttached = true
            setStatus("カメラ接続済み")
            maybeStartStreaming()
        }
        result.onFailure { error, _ ->
            setStatus("カメラ追加に失敗: ${error.description}")
        }
    }

    private fun qualityFor(cfg: StreamConfig): VideoQuality = when (cfg.width) {
        360 -> VideoQuality.LOW
        720 -> VideoQuality.HIGH
        else -> VideoQuality.MEDIUM
    }

    override fun startCamera(config: StreamConfig, onFrame: (CameraFrame) -> Unit) {
        pendingConfig = config
        frameCallback = onFrame
        when {
            session == null || !sessionStarted -> connect()
            !cameraAttached -> scope.launch { ensureCameraPermissionThenAttach() }
            else -> maybeStartStreaming()
        }
    }

    private fun maybeStartStreaming() {
        val st = stream ?: return
        if (frameCallback == null || streaming) return
        streaming = true
        st.start().onFailure { error, _ ->
            streaming = false
            setStatus("ストリーム開始に失敗: ${error.description}")
        }
        frameJob = scope.launch {
            st.videoStream.collect { frame ->
                val cb = frameCallback ?: return@collect
                convertFrame(frame)?.let(cb)
            }
        }
        setStatus("ストリーム配信中（変化検知）")
    }

    private fun convertFrame(frame: VideoFrame): CameraFrame? {
        if (frame.isCompressed) return null // compressVideo=false 前提。圧縮フレームは扱わない
        val w = frame.width
        val h = frame.height
        val need = w * h
        val buf = frame.buffer.duplicate()
        buf.rewind()
        if (buf.remaining() < need) return null
        val pixels = IntArray(need)
        // 前提: 先頭が輝度(Y)プレーン（クラス冒頭の未検証メモ参照）。
        for (i in 0 until need) {
            val y = buf.get().toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
        return CameraFrame(pixels, w, h, frame.presentationTimeUs / 1000)
    }

    override fun capturePhoto(onPhoto: (CameraFrame) -> Unit) {
        val st = stream
        if (st == null || !streaming) {
            setStatus("撮影にはストリーム開始が必要")
            return
        }
        scope.launch {
            val result = st.capturePhoto()
            result.onSuccess { photo ->
                val bmp = decodePhoto(photo)
                if (bmp == null) {
                    setStatus("写真のデコードに失敗")
                    return@onSuccess
                }
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                onPhoto(CameraFrame(pixels, bmp.width, bmp.height, System.currentTimeMillis()))
            }
            result.onFailure { error, _ ->
                setStatus("撮影に失敗: ${error.description}")
            }
        }
    }

    /** PhotoData（Bitmap / HEIC）を Bitmap に揃える。HEIC は EXIF の向きも適用する。 */
    private fun decodePhoto(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
            val buf = photo.data.duplicate()
            buf.rewind()
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (raw == null) null else applyExifOrientation(bytes, raw)
        }
    }

    private fun applyExifOrientation(bytes: ByteArray, bmp: Bitmap): Bitmap {
        val exif = try {
            ExifInterface(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            return bmp
        }
        val m = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ---- ディスプレイ ----

    private suspend fun attachDisplay() {
        val s = session ?: return
        if (display != null) return
        val result = s.addDisplay()
        result.onSuccess { d ->
            display = d
            scope.launch {
                d.state.collect { st ->
                    displayReady = st == DisplayState.STARTED
                    if (displayReady) setStatus("ディスプレイ準備OK")
                }
            }
        }
        result.onFailure { error, _ ->
            setStatus("ディスプレイ追加に失敗: ${error.description}")
        }
    }

    override fun showHudImage(pngBytes: ByteArray) {
        val d = display
        if (d == null) {
            setStatus("ディスプレイ未接続（HUD送信をスキップ）")
            return
        }
        val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        if (bmp == null) {
            setStatus("HUD画像のデコードに失敗")
            return
        }
        scope.launch {
            // 表示はコンポーネントDSL。ルートは単一の flexBox、その中に画像を敷く。
            d.sendContent {
                flexBox {
                    image(bitmap = bmp, sizePreset = ImageSize.FILL)
                }
            }.onFailure { error, _ ->
                setStatus("HUD送信に失敗: ${error.description}")
            }
        }
    }

    override fun close() {
        frameJob?.cancel()
        runCatching { camera?.stop() }
        runCatching { camera?.close() }
        runCatching { session?.removeDisplay() }
        runCatching { session?.stop() }
        camera = null
        stream = null
        display = null
        session = null
        scope.cancel()
    }
}
