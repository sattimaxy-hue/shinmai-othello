// オセロ（リバーシ）エンジン＋アドバイザー
// 盤面: 長さ64の配列。index = row*8 + col（row 0 = 1段目, col 0 = a列）
// 値: 0 = 空, 1 = 黒, -1 = 白
(function (global) {
  "use strict";

  var EMPTY = 0, BLACK = 1, WHITE = -1;
  var DIRS = [
    [-1, -1], [-1, 0], [-1, 1],
    [0, -1],           [0, 1],
    [1, -1],  [1, 0],  [1, 1]
  ];
  var CORNERS = [0, 7, 56, 63];
  // 角に隣接するマス（角が空いている間は悪手になりやすい）
  var CORNER_ADJ = {
    0: [1, 8, 9], 7: [6, 14, 15], 56: [48, 49, 57], 63: [54, 55, 62]
  };
  // 位置の静的価値（序中盤の目安）
  var SQUARE_WEIGHTS = [
    120, -20,  20,   5,   5,  20, -20, 120,
    -20, -40,  -5,  -5,  -5,  -5, -40, -20,
     20,  -5,  15,   3,   3,  15,  -5,  20,
      5,  -5,   3,   3,   3,   3,  -5,   5,
      5,  -5,   3,   3,   3,   3,  -5,   5,
     20,  -5,  15,   3,   3,  15,  -5,  20,
    -20, -40,  -5,  -5,  -5,  -5, -40, -20,
    120, -20,  20,   5,   5,  20, -20, 120
  ];

  function initialBoard() {
    var b = new Array(64).fill(EMPTY);
    b[27] = WHITE; b[28] = BLACK; // d4=白, e4=黒
    b[35] = BLACK; b[36] = WHITE; // d5=黒, e5=白
    return b;
  }

  function idxToCoord(i) {
    return "abcdefgh"[i % 8] + String(Math.floor(i / 8) + 1);
  }

  function flipsFor(board, player, idx) {
    if (board[idx] !== EMPTY) return [];
    var r = Math.floor(idx / 8), c = idx % 8;
    var all = [];
    for (var d = 0; d < DIRS.length; d++) {
      var dr = DIRS[d][0], dc = DIRS[d][1];
      var line = [];
      var rr = r + dr, cc = c + dc;
      while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && board[rr * 8 + cc] === -player) {
        line.push(rr * 8 + cc);
        rr += dr; cc += dc;
      }
      if (line.length > 0 && rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && board[rr * 8 + cc] === player) {
        all = all.concat(line);
      }
    }
    return all;
  }

  // 合法手: Map idx -> 反転する石の配列
  function legalMoves(board, player) {
    var moves = new Map();
    for (var i = 0; i < 64; i++) {
      if (board[i] !== EMPTY) continue;
      var f = flipsFor(board, player, i);
      if (f.length > 0) moves.set(i, f);
    }
    return moves;
  }

  function applyMove(board, player, idx, flips) {
    var next = board.slice();
    next[idx] = player;
    var f = flips || flipsFor(board, player, idx);
    for (var i = 0; i < f.length; i++) next[f[i]] = player;
    return next;
  }

  function countDiscs(board) {
    var black = 0, white = 0, empty = 0;
    for (var i = 0; i < 64; i++) {
      if (board[i] === BLACK) black++;
      else if (board[i] === WHITE) white++;
      else empty++;
    }
    return { black: black, white: white, empty: empty };
  }

  function evaluate(board, player) {
    // player 視点の静的評価。終盤は石数差が支配的。
    var counts = countDiscs(board);
    var empties = counts.empty;
    var myDiscs = player === BLACK ? counts.black : counts.white;
    var opDiscs = player === BLACK ? counts.white : counts.black;
    if (empties === 0) return (myDiscs - opDiscs) * 1000;

    var score = 0;
    var i;

    // 角の確保は最重要
    for (i = 0; i < CORNERS.length; i++) {
      var corner = CORNERS[i];
      if (board[corner] === player) score += 2500;
      else if (board[corner] === -player) score -= 2500;
    }
    // 位置価値（角は上で数えたので控えめに加算）
    for (i = 0; i < 64; i++) {
      if (board[i] === player) score += SQUARE_WEIGHTS[i];
      else if (board[i] === -player) score -= SQUARE_WEIGHTS[i];
    }
    // 角が空のとき、その隣接マスに自分の石があると不利
    for (i = 0; i < CORNERS.length; i++) {
      var cnr = CORNERS[i];
      if (board[cnr] !== EMPTY) continue;
      var adj = CORNER_ADJ[cnr];
      for (var j = 0; j < adj.length; j++) {
        if (board[adj[j]] === player) score -= 60;
        else if (board[adj[j]] === -player) score += 60;
      }
    }
    // 着手可能手数（モビリティ）
    var myMob = legalMoves(board, player).size;
    var opMob = legalMoves(board, -player).size;
    score += (myMob - opMob) * 12;
    if (opMob === 0 && myMob > 0) score += 200; // 相手をパスに追い込むのは大きい
    // 空きが減るほど石数差の比重を上げる
    var discWeight = empties > 20 ? 1 : empties > 10 ? 4 : 25;
    score += (myDiscs - opDiscs) * discWeight;
    return score;
  }

  // negamax + アルファベータ。player の番から depth 手先まで読む静的評価を返す
  function search(board, player, depth, alpha, beta) {
    var moves = legalMoves(board, player);
    var counts = countDiscs(board);
    if (counts.empty === 0) {
      var diff = (player === BLACK ? counts.black - counts.white : counts.white - counts.black);
      return diff * 1000;
    }
    if (moves.size === 0) {
      var oppMoves = legalMoves(board, -player);
      if (oppMoves.size === 0) {
        var d2 = (player === BLACK ? counts.black - counts.white : counts.white - counts.black);
        return d2 * 1000;
      }
      if (depth === 0) return evaluate(board, player);
      return -search(board, -player, depth - 1, -beta, -alpha); // パス
    }
    if (depth === 0) return evaluate(board, player);
    var best = -Infinity;
    var entries = Array.from(moves.entries());
    // 角手を先に読むと枝刈りが効く
    entries.sort(function (a, b) { return SQUARE_WEIGHTS[b[0]] - SQUARE_WEIGHTS[a[0]]; });
    for (var k = 0; k < entries.length; k++) {
      var idx = entries[k][0];
      var next = applyMove(board, player, idx, entries[k][1]);
      var val = -search(next, -player, depth - 1, -beta, -alpha);
      if (val > best) best = val;
      if (best > alpha) alpha = best;
      if (alpha >= beta) break;
    }
    return best;
  }

  // おすすめ手ランキング（上位 n 件、理由つき）
  function advise(board, player, opts) {
    opts = opts || {};
    var counts = countDiscs(board);
    var empties = counts.empty;
    // 終盤（空き12以下）は全読み、それ以外は既定5手先
    var depth = opts.depth || (empties <= 12 ? empties : 5);
    var moves = legalMoves(board, player);
    if (moves.size === 0) {
      return { pass: true, moves: [], depth: depth, gameOver: legalMoves(board, -player).size === 0, counts: counts };
    }
    var results = [];
    var entries = Array.from(moves.entries());
    for (var i = 0; i < entries.length; i++) {
      var idx = entries[i][0], flips = entries[i][1];
      var next = applyMove(board, player, idx, flips);
      var score = -search(next, -player, depth - 1, -Infinity, Infinity);
      results.push(buildAdvice(board, player, idx, flips, next, score));
    }
    results.sort(function (a, b) { return b.score - a.score; });
    return { pass: false, moves: results.slice(0, opts.topN || 3), allCount: results.length, depth: depth, counts: counts };
  }

  function buildAdvice(board, player, idx, flips, next, score) {
    var reasons = [];
    var coord = idxToCoord(idx);
    reasons.push(flips.length + "枚返せる");
    if (CORNERS.indexOf(idx) >= 0) reasons.push("角が取れる（最重要）");
    // 角隣接の危険
    var riskyCorner = null;
    for (var i = 0; i < CORNERS.length; i++) {
      var cnr = CORNERS[i];
      if (board[cnr] === EMPTY && CORNER_ADJ[cnr].indexOf(idx) >= 0) riskyCorner = cnr;
    }
    // 自分の着手後の相手の状況
    var oppMoves = legalMoves(next, -player);
    var oppCorner = null;
    oppMoves.forEach(function (_, mIdx) {
      if (CORNERS.indexOf(mIdx) >= 0) oppCorner = mIdx;
    });
    if (oppCorner !== null && CORNERS.indexOf(idx) < 0) {
      reasons.push("注意: 相手に角(" + idxToCoord(oppCorner) + ")を渡す");
    } else if (riskyCorner !== null) {
      reasons.push("角の隣なので慎重に（相手が角を狙いやすくなる）");
    }
    reasons.push("相手の着手可能は" + oppMoves.size + "手");
    if (oppMoves.size === 0) reasons.push("相手はパスになる");
    var myNext = legalMoves(next, player).size;
    reasons.push("自分の次の候補は" + myNext + "手残る");
    return { idx: idx, coord: coord, flips: flips.slice(), flipCount: flips.length, score: score, reasons: reasons };
  }

  var api = {
    EMPTY: EMPTY, BLACK: BLACK, WHITE: WHITE,
    initialBoard: initialBoard,
    idxToCoord: idxToCoord,
    flipsFor: flipsFor,
    legalMoves: legalMoves,
    applyMove: applyMove,
    countDiscs: countDiscs,
    evaluate: evaluate,
    advise: advise
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api;
  global.Othello = api;
})(typeof window !== "undefined" ? window : globalThis);
