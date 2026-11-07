package jp.satti.shinmaiothello.core

/**
 * 新米（Ray-Ban Display）のHUDに渡すミニ盤面モデル。
 *
 * DAT の表示は右眼600x600のHUD固定で空間アンカーがないため、
 * 実物の盤に重ねるのではなく「ミニ盤面＋カーソル＋座標」で提示する（docs/DESIGN.md）。
 * 提示側はこのモデルを画像に描くか、DATの表示コンポーネントに流す。
 */
data class HudCell(
    val state: Int,          // OthelloEngine の EMPTY / BLACK / WHITE
    val rank: Int? = null    // 助言順位（1 = 最適手）。2位・3位はバッジ表示用
)

data class HudState(
    val cells: List<HudCell>,   // 64マス
    val bestCoord: String,      // 例 "f5"。助言がなければ "—"
    val reasonShort: String,    // グラスで読める長さに絞った理由（最大2要素）
    val turnLabel: String,      // 例 "黒の手番"
    val countsLabel: String     // 例 "● 黒 12　○ 白 9　空き 43"
)

object HudModel {
    fun build(board: IntArray, turn: Int, advice: OthelloEngine.AdviceResult?): HudState {
        val rankByIdx = mutableMapOf<Int, Int>()
        if (advice != null && !advice.pass) {
            advice.moves.forEachIndexed { i, m -> rankByIdx[m.idx] = i + 1 }
        }
        val cells = (0..63).map { i -> HudCell(state = board[i], rank = rankByIdx[i]) }
        val counts = OthelloEngine.countDiscs(board)
        val best = advice?.moves?.firstOrNull()
        return HudState(
            cells = cells,
            bestCoord = best?.coord ?: "—",
            reasonShort = best?.reasons?.take(2)?.joinToString("／")
                ?: if (advice?.pass == true) "打てる手がない（パス）" else "おすすめを計算すると出るよ",
            turnLabel = (if (turn == OthelloEngine.BLACK) "黒" else "白") + "の手番",
            countsLabel = "● 黒 ${counts.black}　○ 白 ${counts.white}　空き ${counts.empty}"
        )
    }
}
