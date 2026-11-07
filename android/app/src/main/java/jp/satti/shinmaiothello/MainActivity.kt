package jp.satti.shinmaiothello

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.PermissionStatus
import jp.satti.shinmaiothello.core.HudModel
import jp.satti.shinmaiothello.core.OthelloEngine
import jp.satti.shinmaiothello.dat.CameraFrame
import jp.satti.shinmaiothello.dat.DatGlassesClient
import jp.satti.shinmaiothello.dat.StreamConfig
import jp.satti.shinmaiothello.hud.HudRenderer
import jp.satti.shinmaiothello.vision.BoardRecognizer
import jp.satti.shinmaiothello.vision.FrameChangeDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * DAT 実機向けメイン画面（開発用）。
 *
 * 流れ（docs/DESIGN.md）:
 * 1. 「登録」で Meta AI アプリ連携 → 「接続して監視開始」でセッション＋カメラ開始
 * 2. 2fps ストリームの輝度で変化検知し、盤が静止したら自動で写真を撮る
 * 3. BoardRecognizer で盤面を復元（四隅は当面プレースホルダ。キャリブレーションUIは次工程）
 * 4. OthelloEngine.advise で助言を計算し、HudModel→画像をグラスへ送る
 *
 * 手番は「認識のたびに交互」前提の簡易管理（パス時は「手番を切替」ボタンで手動調整）。
 * 実機ビルド・実機動作は未検証。
 */
class MainActivity : Activity() {

    private lateinit var client: DatGlassesClient
    private lateinit var statusTv: TextView
    private lateinit var resultTv: TextView
    private lateinit var hudPreview: ImageView

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())
    private val recognizer = BoardRecognizer()

    private var currentTurn = OthelloEngine.BLACK

    // 変化検知まわり
    private var prevThumb: ByteArray? = null
    private var lastPhotoThumb: ByteArray? = null
    private var stillCount = 0
    private var photoInFlight = false

    // DAT 権限リクエスト（Meta AI アプリへ遷移して戻ってくる）。
    // 素の Activity でも使えるよう、契約の createIntent / parseResult を直接使う。
    private val datPermissionContract = Wearables.RequestPermissionContract()

    @Deprecated("startActivityForResult 経由の権限フローのため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DAT_PERMISSION && ::client.isInitialized) {
            val result = datPermissionContract.parseResult(resultCode, data)
            client.onPermissionResult(result.getOrDefault(PermissionStatus.Denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = DatGlassesClient(this)
        client.permissionRequester = { permission ->
            @Suppress("DEPRECATION")
            startActivityForResult(datPermissionContract.createIntent(this, permission), REQ_DAT_PERMISSION)
        }

        // Android ランタイム権限（Bluetooth 接続に必要）
        val needed = arrayOf(
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.INTERNET
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)

        statusTv = TextView(this).apply { textSize = 15f }
        resultTv = TextView(this).apply { textSize = 15f }
        hudPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 600
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@MainActivity).apply {
                text = "新米オセロ・アドバイザー（DAT開発版）"
                textSize = 20f
            })
            addView(statusTv)
            addView(btn("1. 登録（Meta AIで連携）") { client.startRegistration(this@MainActivity) })
            addView(btn("2. 接続して監視開始") {
                client.startCamera(StreamConfig()) { frame -> onFrame(frame) }
            })
            addView(btn("カメラ権限を許可") { client.requestCameraPermission() })
            addView(btn("今すぐ撮影して認識") {
                photoInFlight = true
                client.capturePhoto { photo -> onPhoto(photo) }
            })
            addView(btn("手番を切替（パス時など）") {
                currentTurn = -currentTurn
                resultTv.text = "手番を ${if (currentTurn == OthelloEngine.BLACK) "黒" else "白"} に切り替えた"
            })
            addView(hudPreview)
            addView(resultTv)
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        uiScope.launch {
            client.status.collect { statusTv.text = "状態: $it" }
        }
    }

    /** ストリームフレーム（輝度のみ）→ サムネイル差分で静止を検知し、自動撮影する。 */
    private fun onFrame(frame: CameraFrame) {
        val thumb = downsampleGray(frame, 32)
        val prev = prevThumb
        prevThumb = thumb
        if (prev == null || prev.size != thumb.size) return
        val diff = FrameChangeDetector.diffRatio(prev, thumb)
        if (FrameChangeDetector.isStill(diff)) {
            stillCount++
            val last = lastPhotoThumb
            val changedSincePhoto = last == null || FrameChangeDetector.diffRatio(last, thumb) > 0.03
            if (stillCount >= 3 && changedSincePhoto && !photoInFlight) {
                photoInFlight = true
                lastPhotoThumb = thumb
                stillCount = 0
                client.capturePhoto { photo -> onPhoto(photo) }
            }
        } else {
            stillCount = 0
        }
    }

    private fun downsampleGray(frame: CameraFrame, n: Int): ByteArray {
        val out = ByteArray(n * n)
        val stepX = frame.width / n
        val stepY = frame.height / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                // ストリーム由来フレームは輝度を ARGB 各成分に複写しているため B 成分で足りる
                out[y * n + x] = (frame.pixels[(y * stepY) * frame.width + x * stepX] and 0xFF).toByte()
            }
        }
        return out
    }

    /** 写真 → 盤面認識 → 助言 → HUD 送信。 */
    private fun onPhoto(photo: CameraFrame) {
        photoInFlight = false
        // 四隅は当面プレースホルダ（写真の内側6%）。キャリブレーションUIは次工程で、
        // それまでは「盤が写真いっぱいに写っている」前提でしか正しく読めない。
        val w = photo.width.toDouble()
        val h = photo.height.toDouble()
        val corners = arrayOf(
            BoardRecognizer.Pt(w * 0.06, h * 0.06),
            BoardRecognizer.Pt(w * 0.94, h * 0.06),
            BoardRecognizer.Pt(w * 0.94, h * 0.94),
            BoardRecognizer.Pt(w * 0.06, h * 0.94)
        )
        val result = recognizer.recognize(photo.pixels, photo.width, photo.height, corners)
        val board = result.board
        val advice = OthelloEngine.advise(board, currentTurn)
        val hud = HudModel.build(board, currentTurn, advice)

        hudPreview.setImageBitmap(HudRenderer.render(hud))
        client.showHudImage(HudRenderer.renderPng(hud))

        val low = result.lowConfidenceCells()
        resultTv.text = buildString {
            appendLine("認識完了（${if (currentTurn == OthelloEngine.BLACK) "黒" else "白"}の手番として計算）")
            appendLine(hud.countsLabel)
            if (advice.pass) {
                appendLine(if (advice.gameOver) "終局" else "打てる手なし（パス）")
            } else {
                appendLine("おすすめ: ${hud.bestCoord.uppercase()} — ${hud.reasonShort}")
                advice.moves.forEach { m ->
                    appendLine("・${m.coord}: ${m.reasons.joinToString("／")}")
                }
            }
            if (low.isNotEmpty()) {
                appendLine("低確信マス ${low.size} 件: " + low.joinToString(", ") { OthelloEngine.idxToCoord(it) })
            }
            appendLine("※四隅は仮置き。盤が写真いっぱいに写っている前提")
        }
        currentTurn = -currentTurn // 次の認識は相手の手番として扱う（簡易管理）
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.close()
        uiScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQ_DAT_PERMISSION = 4242
    }
}
