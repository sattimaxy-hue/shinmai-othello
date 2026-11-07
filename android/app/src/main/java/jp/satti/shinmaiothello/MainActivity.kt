package jp.satti.shinmaiothello

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import jp.satti.shinmaiothello.core.HudModel
import jp.satti.shinmaiothello.core.OthelloEngine

/**
 * スケルトン: 起動直後にエンジンの自己診断（初期局面の助言計算）を表示するだけ。
 *
 * 本実装の流れ（docs/DESIGN.md）:
 * 1. DAT でグラスとセッションを張る（dat/DatGlassesClient）
 * 2. 2fps ストリームで盤面の変化を監視し、静止したら写真を撮る
 * 3. BoardRecognizer で盤面を復元し、低確信マスはこの画面でユーザーが直す
 * 4. OthelloEngine.advise で助言を計算し、HudModel を描いた画像をグラスへ送る
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        val board = OthelloEngine.initialBoard()
        val advice = OthelloEngine.advise(board, OthelloEngine.BLACK)
        val hud = HudModel.build(board, OthelloEngine.BLACK, advice)
        tv.text = buildString {
            appendLine("新米オセロ・アドバイザー（スケルトン）")
            appendLine()
            appendLine("初期局面のおすすめ: ${hud.bestCoord}")
            appendLine(hud.reasonShort)
            appendLine(hud.countsLabel)
            appendLine()
            appendLine("候補:")
            advice.moves.forEach { m -> appendLine("・${m.coord}: ${m.reasons.joinToString("／")}") }
        }
        setContentView(tv)
    }
}
