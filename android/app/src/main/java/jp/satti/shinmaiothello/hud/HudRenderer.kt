package jp.satti.shinmaiothello.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import jp.satti.shinmaiothello.core.HudState
import jp.satti.shinmaiothello.core.OthelloEngine
import java.io.ByteArrayOutputStream

/**
 * HudState を 600x600 の Bitmap に描く（DAT の image コンポーネント送信用）。
 *
 * Ray-Ban Display は加算表示で黒が透明になるため、背景は黒、
 * 盤と文字は明るい色で描く。Web Apps 版 webapp/ の見た目に合わせた配色。
 */
object HudRenderer {

    fun render(state: HudState, size: Int = 600): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 10) }
        val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 212, 255) }

        // ヘッダー: 手番と石数
        text.textSize = 30f
        canvas.drawText(state.turnLabel, 24f, 44f, text)
        text.textSize = 24f
        canvas.drawText(state.countsLabel, 24f, 80f, text)

        // 盤面: 416px を中央に
        val boardPx = 416
        val ox = (size - boardPx) / 2f
        val oy = 96f
        val cell = boardPx / 8f
        val boardPaint = Paint().apply { color = Color.rgb(6, 48, 15) }
        canvas.drawRect(ox, oy, ox + boardPx, oy + boardPx, boardPaint)
        val line = Paint().apply { color = Color.rgb(31, 122, 58); strokeWidth = 2f }
        for (i in 0..8) {
            canvas.drawLine(ox + cell * i, oy, ox + cell * i, oy + boardPx, line)
            canvas.drawLine(ox, oy + cell * i, ox + boardPx, oy + cell * i, line)
        }

        val discBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 21, 21) }
        val discBlackEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(217, 217, 217); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        val discWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val bestRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 214, 10); style = Paint.Style.STROKE; strokeWidth = 5f
        }
        val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 212, 255); textSize = 20f; isFakeBoldText = true
        }

        state.cells.forEachIndexed { idx, hudCell ->
            val cx = ox + cell * (idx % 8) + cell / 2
            val cy = oy + cell * (idx / 8) + cell / 2
            val rad = cell * 0.40f
            when (hudCell.state) {
                OthelloEngine.BLACK -> {
                    canvas.drawCircle(cx, cy, rad, discBlack)
                    canvas.drawCircle(cx, cy, rad, discBlackEdge)
                }
                OthelloEngine.WHITE -> canvas.drawCircle(cx, cy, rad, discWhite)
            }
            when (hudCell.rank) {
                1 -> {
                    canvas.drawCircle(cx, cy, rad + 4f, bestRing)
                    yellow.textSize = 22f
                    val label = "▼"
                    canvas.drawText(label, cx - yellow.measureText(label) / 2, cy - rad - 8f, yellow)
                }
                2, 3 -> canvas.drawText(hudCell.rank.toString(), cx + rad - 12f, cy - rad + 18f, rankPaint)
            }
        }

        // フッター: おすすめと理由
        yellow.textSize = 44f
        yellow.isFakeBoldText = true
        canvas.drawText("おすすめ ${state.bestCoord.uppercase()}", 24f, oy + boardPx + 56f, yellow)
        text.textSize = 24f
        val reason = if (state.reasonShort.length > 26) state.reasonShort.take(26) + "…" else state.reasonShort
        canvas.drawText(reason, 24f, oy + boardPx + 92f, text)
        return bmp
    }

    fun renderPng(state: HudState, size: Int = 600): ByteArray {
        val out = ByteArrayOutputStream()
        render(state, size).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
