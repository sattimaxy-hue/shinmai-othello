package jp.satti.shinmaiothello.vision

import jp.satti.shinmaiothello.core.OthelloEngine
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 盤面スナップショット認識（Web試作の方式を移植）。
 *
 * 入力は ARGB ピクセル配列と、盤の四隅（画像座標）。
 * 四隅はコンパニオンアプリの画面でユーザーが合わせる（キャリブレーション）想定で、
 * 対局中はその値を保持し、ズレ検知時のみ再キャリブレーションする設計（docs/DESIGN.md）。
 *
 * 各マスの中心付近を透視補正（ホモグラフィ）でサンプリングし、
 * 輝度＋緑さで 黒／白／空 を判定、確信度も返す。確信度が低いマスは
 * 修正UI（スマホ側）で人間が直す前提で、推測で埋めない。
 */
class BoardRecognizer {

    data class Pt(val x: Double, val y: Double)

    data class Result(
        val board: IntArray,
        val confidence: DoubleArray
    ) {
        fun lowConfidenceCells(threshold: Double = 0.6): List<Int> =
            confidence.indices.filter { confidence[it] < threshold }
    }

    fun recognize(pixels: IntArray, width: Int, height: Int, corners: Array<Pt>): Result {
        require(corners.size == 4) { "corners must be 4 points (TL, TR, BR, BL)" }
        require(pixels.size == width * height)
        val h = computeHomography(corners)
        val p00 = applyH(h, 0.0, 0.0)
        val p10 = applyH(h, 1.0, 0.0)
        val cellPx = hypot(p10.x - p00.x, p10.y - p00.y) / 8.0
        val radius = max(2, floor(cellPx * 0.22).toInt())

        val board = IntArray(64) { OthelloEngine.EMPTY }
        val conf = DoubleArray(64) { 1.0 }
        for (r in 0..7) {
            for (c in 0..7) {
                val pt = applyH(h, (c + 0.5) / 8.0, (r + 0.5) / 8.0)
                val sample = sampleAverage(pixels, width, height, pt.x, pt.y, radius)
                val lum = 0.299 * sample[0] + 0.587 * sample[1] + 0.114 * sample[2]
                val greenish = sample[1] - (sample[0] + sample[2]) / 2.0
                val idx = r * 8 + c
                when {
                    lum < 80 -> {
                        board[idx] = OthelloEngine.BLACK
                        conf[idx] = min(1.0, (80 - lum) / 50.0 + 0.55)
                    }
                    lum > 175 && greenish < 25 -> {
                        board[idx] = OthelloEngine.WHITE
                        conf[idx] = min(1.0, (lum - 175) / 50.0 + 0.55)
                    }
                    greenish > 14 -> {
                        board[idx] = OthelloEngine.EMPTY
                        conf[idx] = min(1.0, (greenish - 14) / 30.0 + 0.5)
                    }
                    else -> {
                        board[idx] = OthelloEngine.EMPTY
                        conf[idx] = 0.3 // 判定に自信なし: 空として置き、低確信で印す
                    }
                }
            }
        }
        return Result(board, conf)
    }

    private fun sampleAverage(pixels: IntArray, width: Int, height: Int, cx: Double, cy: Double, radius: Int): DoubleArray {
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var cnt = 0
        val x0 = max(0, floor(cx - radius).toInt())
        val y0 = max(0, floor(cy - radius).toInt())
        val x1 = min(width - 1, floor(cx + radius).toInt())
        val y1 = min(height - 1, floor(cy + radius).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val argb = pixels[y * width + x]
                sr += (argb shr 16) and 0xFF
                sg += (argb shr 8) and 0xFF
                sb += argb and 0xFF
                cnt++
            }
        }
        if (cnt == 0) return doubleArrayOf(0.0, 0.0, 0.0)
        return doubleArrayOf(sr / cnt, sg / cnt, sb / cnt)
    }

    /** 単位正方形 -> 画像四隅へのホモグラフィ（h33 = 1 とした8係数） */
    private fun computeHomography(dst: Array<Pt>): DoubleArray {
        val src = arrayOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0),
            doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0)
        )
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0..3) {
            val x = src[i][0]; val y = src[i][1]
            val bigX = dst[i].x; val bigY = dst[i].y
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -bigX * x, -bigX * y)
            b[2 * i] = bigX
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -bigY * x, -bigY * y)
            b[2 * i + 1] = bigY
        }
        // ガウス消去（ピボット選択つき）
        val n = 8
        var row = 0
        var col = 0
        while (col < n && row < n) {
            var piv = row
            for (r in row + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[piv][col])) piv = r
            val tmpRow = a[row]; a[row] = a[piv]; a[piv] = tmpRow
            val tmpB = b[row]; b[row] = b[piv]; b[piv] = tmpB
            val div = if (a[row][col] != 0.0) a[row][col] else 1e-12
            for (c in col until n) a[row][c] /= div
            b[row] /= div
            for (r in 0 until n) {
                if (r == row) continue
                val f = a[r][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[row][c]
                b[r] -= f * b[row]
            }
            row++; col++
        }
        return b
    }

    private fun applyH(h: DoubleArray, u: Double, v: Double): Pt {
        val w = h[6] * u + h[7] * v + 1.0
        return Pt((h[0] * u + h[1] * v + h[2]) / w, (h[3] * u + h[4] * v + h[5]) / w)
    }
}

/**
 * フレーム変化検知: 縮小グレースケール同士の平均絶対差。
 * 「盤が変わった」ことと「手が離れて静止した」ことの判定に使う（docs/DESIGN.md）。
 */
object FrameChangeDetector {
    fun diffRatio(prev: ByteArray, cur: ByteArray): Double {
        require(prev.size == cur.size)
        if (prev.isEmpty()) return 0.0
        var sum = 0.0
        for (i in prev.indices) {
            sum += kotlin.math.abs((prev[i].toInt() and 0xFF) - (cur[i].toInt() and 0xFF))
        }
        return sum / prev.size / 255.0
    }

    /** 変化が大きい（手が動いている等） */
    fun isMoving(diff: Double): Boolean = diff > 0.08

    /** 静止している（認識に使えるフレーム） */
    fun isStill(diff: Double): Boolean = diff < 0.02
}
