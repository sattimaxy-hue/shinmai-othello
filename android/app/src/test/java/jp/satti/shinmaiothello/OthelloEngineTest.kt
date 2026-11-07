package jp.satti.shinmaiothello

import jp.satti.shinmaiothello.core.OthelloEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web試作の自動テスト（prototype の検証済みケース）の Kotlin 版。 */
class OthelloEngineTest {

    @Test
    fun initialLegalMoves() {
        val moves = OthelloEngine.legalMoves(OthelloEngine.initialBoard(), OthelloEngine.BLACK)
        val coords = moves.keys.map { OthelloEngine.idxToCoord(it) }.sorted()
        assertEquals(listOf("c4", "d3", "e6", "f5"), coords)
    }

    @Test
    fun flipOnD3() {
        val b = OthelloEngine.initialBoard()
        val d3 = 2 * 8 + 3
        val next = OthelloEngine.applyMove(b, OthelloEngine.BLACK, d3)
        assertEquals(OthelloEngine.BLACK, next[27]) // d4 が返る
        val counts = OthelloEngine.countDiscs(next)
        assertEquals(4, counts.black)
        assertEquals(1, counts.white)
    }

    @Test
    fun advisorPrefersCorner() {
        val b = IntArray(64) { OthelloEngine.EMPTY }
        b[2] = OthelloEngine.BLACK  // c1
        b[1] = OthelloEngine.WHITE  // b1 -> 黒は a1 に打てる
        b[27] = OthelloEngine.WHITE; b[28] = OthelloEngine.BLACK
        b[35] = OthelloEngine.BLACK; b[36] = OthelloEngine.WHITE
        val res = OthelloEngine.advise(b, OthelloEngine.BLACK, depthOverride = 4)
        assertEquals("a1", res.moves[0].coord)
        assertTrue(res.moves[0].reasons.joinToString(" ").contains("角"))
    }

    @Test
    fun passDetection() {
        val b = IntArray(64) { OthelloEngine.EMPTY }
        b[1] = OthelloEngine.BLACK   // b1
        b[9] = OthelloEngine.WHITE   // b2
        b[25] = OthelloEngine.BLACK  // b4（黒は b3 で挟める。白は挟める場所なし）
        assertTrue(OthelloEngine.advise(b, OthelloEngine.WHITE, depthOverride = 3).pass)
        assertTrue(!OthelloEngine.advise(b, OthelloEngine.BLACK, depthOverride = 3).pass)
    }

    @Test
    fun endgameFullSearch() {
        val b = IntArray(64) { OthelloEngine.BLACK }
        b[61] = OthelloEngine.WHITE  // f8
        b[62] = OthelloEngine.EMPTY  // g8 が唯一の合法手で最善
        val res = OthelloEngine.advise(b, OthelloEngine.BLACK)
        assertEquals("g8", res.moves[0].coord)
    }
}
