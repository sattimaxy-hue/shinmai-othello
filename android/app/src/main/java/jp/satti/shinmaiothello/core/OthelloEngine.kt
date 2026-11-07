package jp.satti.shinmaiothello.core

/**
 * オセロ（リバーシ）エンジン＋アドバイザー。
 * Web試作（prototype/othello.js）からの移植で、ロジックは同一。
 *
 * 盤面: IntArray(64)。index = row*8 + col（row 0 = 1段目、col 0 = a列）
 * 値: 0 = 空, 1 = 黒, -1 = 白
 */
object OthelloEngine {
    const val EMPTY = 0
    const val BLACK = 1
    const val WHITE = -1

    // 評価値の十分大きな上限（Int.MIN_VALUE の符号反転オーバーフローを避けるため有限値を使う）
    private const val INF = 1_000_000_000

    private val DIRS = arrayOf(
        intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1),
        intArrayOf(0, -1), intArrayOf(0, 1),
        intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1)
    )
    private val CORNERS = intArrayOf(0, 7, 56, 63)
    private val CORNER_ADJ = mapOf(
        0 to intArrayOf(1, 8, 9),
        7 to intArrayOf(6, 14, 15),
        56 to intArrayOf(48, 49, 57),
        63 to intArrayOf(54, 55, 62)
    )
    private val SQUARE_WEIGHTS = intArrayOf(
        120, -20, 20, 5, 5, 20, -20, 120,
        -20, -40, -5, -5, -5, -5, -40, -20,
        20, -5, 15, 3, 3, 15, -5, 20,
        5, -5, 3, 3, 3, 3, -5, 5,
        5, -5, 3, 3, 3, 3, -5, 5,
        20, -5, 15, 3, 3, 15, -5, 20,
        -20, -40, -5, -5, -5, -5, -40, -20,
        120, -20, 20, 5, 5, 20, -20, 120
    )

    data class Counts(val black: Int, val white: Int, val empty: Int)

    data class MoveAdvice(
        val idx: Int,
        val coord: String,
        val flips: List<Int>,
        val score: Int,
        val reasons: List<String>
    )

    data class AdviceResult(
        val pass: Boolean,
        val gameOver: Boolean,
        val moves: List<MoveAdvice>,
        val allCount: Int,
        val depth: Int,
        val counts: Counts
    )

    fun initialBoard(): IntArray {
        val b = IntArray(64) { EMPTY }
        b[27] = WHITE; b[28] = BLACK
        b[35] = BLACK; b[36] = WHITE
        return b
    }

    fun idxToCoord(idx: Int): String = "${"abcdefgh"[idx % 8]}${idx / 8 + 1}"

    fun flipsFor(board: IntArray, player: Int, idx: Int): List<Int> {
        if (board[idx] != EMPTY) return emptyList()
        val r = idx / 8
        val c = idx % 8
        val all = mutableListOf<Int>()
        for ((dr, dc) in DIRS) {
            val line = mutableListOf<Int>()
            var rr = r + dr
            var cc = c + dc
            while (rr in 0..7 && cc in 0..7 && board[rr * 8 + cc] == -player) {
                line.add(rr * 8 + cc)
                rr += dr; cc += dc
            }
            if (line.isNotEmpty() && rr in 0..7 && cc in 0..7 && board[rr * 8 + cc] == player) {
                all.addAll(line)
            }
        }
        return all
    }

    /** 合法手: idx -> 反転する石 */
    fun legalMoves(board: IntArray, player: Int): Map<Int, List<Int>> {
        val moves = LinkedHashMap<Int, List<Int>>()
        for (i in 0..63) {
            if (board[i] != EMPTY) continue
            val f = flipsFor(board, player, i)
            if (f.isNotEmpty()) moves[i] = f
        }
        return moves
    }

    fun applyMove(board: IntArray, player: Int, idx: Int, flips: List<Int> = flipsFor(board, player, idx)): IntArray {
        val next = board.copyOf()
        next[idx] = player
        for (f in flips) next[f] = player
        return next
    }

    fun countDiscs(board: IntArray): Counts {
        var black = 0; var white = 0; var empty = 0
        for (v in board) when (v) {
            BLACK -> black++
            WHITE -> white++
            else -> empty++
        }
        return Counts(black, white, empty)
    }

    fun evaluate(board: IntArray, player: Int): Int {
        val counts = countDiscs(board)
        val empties = counts.empty
        val myDiscs = if (player == BLACK) counts.black else counts.white
        val opDiscs = if (player == BLACK) counts.white else counts.black
        if (empties == 0) return (myDiscs - opDiscs) * 1000

        var score = 0
        for (corner in CORNERS) {
            if (board[corner] == player) score += 2500
            else if (board[corner] == -player) score -= 2500
        }
        for (i in 0..63) {
            if (board[i] == player) score += SQUARE_WEIGHTS[i]
            else if (board[i] == -player) score -= SQUARE_WEIGHTS[i]
        }
        for ((corner, adj) in CORNER_ADJ) {
            if (board[corner] != EMPTY) continue
            for (a in adj) {
                if (board[a] == player) score -= 60
                else if (board[a] == -player) score += 60
            }
        }
        val myMob = legalMoves(board, player).size
        val opMob = legalMoves(board, -player).size
        score += (myMob - opMob) * 12
        if (opMob == 0 && myMob > 0) score += 200
        val discWeight = when {
            empties > 20 -> 1
            empties > 10 -> 4
            else -> 25
        }
        score += (myDiscs - opDiscs) * discWeight
        return score
    }

    private fun search(board: IntArray, player: Int, depth: Int, alphaIn: Int, beta: Int): Int {
        var alpha = alphaIn
        val moves = legalMoves(board, player)
        val counts = countDiscs(board)
        val diff = if (player == BLACK) counts.black - counts.white else counts.white - counts.black
        if (counts.empty == 0) return diff * 1000
        if (moves.isEmpty()) {
            if (legalMoves(board, -player).isEmpty()) return diff * 1000
            if (depth == 0) return evaluate(board, player)
            return -search(board, -player, depth - 1, -beta, -alpha)
        }
        if (depth == 0) return evaluate(board, player)
        var best = -INF
        val entries = moves.entries.sortedByDescending { SQUARE_WEIGHTS[it.key] }
        for ((idx, flips) in entries) {
            val next = applyMove(board, player, idx, flips)
            val v = -search(next, -player, depth - 1, -beta, -alpha)
            if (v > best) best = v
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    /** おすすめ手ランキング（上位 topN、理由つき）。終盤（空き12以下）は全読み。 */
    fun advise(board: IntArray, player: Int, topN: Int = 3, depthOverride: Int? = null): AdviceResult {
        val counts = countDiscs(board)
        val depth = depthOverride ?: if (counts.empty <= 12) counts.empty else 5
        val moves = legalMoves(board, player)
        if (moves.isEmpty()) {
            return AdviceResult(
                pass = true,
                gameOver = legalMoves(board, -player).isEmpty(),
                moves = emptyList(), allCount = 0, depth = depth, counts = counts
            )
        }
        val results = moves.map { (idx, flips) ->
            val next = applyMove(board, player, idx, flips)
            val score = -search(next, -player, depth - 1, -INF, INF)
            buildAdvice(board, player, idx, flips, next, score)
        }.sortedByDescending { it.score }
        return AdviceResult(false, false, results.take(topN), results.size, depth, counts)
    }

    private fun buildAdvice(
        board: IntArray, player: Int, idx: Int, flips: List<Int>, next: IntArray, score: Int
    ): MoveAdvice {
        val reasons = mutableListOf<String>()
        reasons.add("${flips.size}枚返せる")
        if (idx in CORNERS) reasons.add("角が取れる（最重要）")
        var riskyCorner: Int? = null
        for (corner in CORNERS) {
            if (board[corner] == EMPTY && CORNER_ADJ[corner]?.contains(idx) == true) riskyCorner = corner
        }
        val oppMoves = legalMoves(next, -player)
        val oppCorner = oppMoves.keys.firstOrNull { it in CORNERS }
        if (oppCorner != null && idx !in CORNERS) {
            reasons.add("注意: 相手に角(${idxToCoord(oppCorner)})を渡す")
        } else if (riskyCorner != null) {
            reasons.add("角の隣なので慎重に（相手が角を狙いやすくなる）")
        }
        reasons.add("相手の着手可能は${oppMoves.size}手")
        if (oppMoves.isEmpty()) reasons.add("相手はパスになる")
        reasons.add("自分の次の候補は${legalMoves(next, player).size}手残る")
        return MoveAdvice(idx, idxToCoord(idx), flips, score, reasons)
    }
}
