// すずめ雀（すごろくや）助言エンジン
// 牌: { t: '1'..'9' | 'H'(發) | 'C'(中), red: boolean }
//   - 使用牌は索子1〜9＋發＋中の11種×各4枚＝44枚
//   - 赤牌: 数牌1〜9は各1枚だけ赤い版がある。中は4枚とも赤扱い。發に赤はない
// ルール根拠（2026-11-12確認）: すごろくや公式ページ（構成品・人数）、
//   GIGAZINEプレイレポート（点数計算の具体例）、ルール紹介記事複数で相互確認。
//   役満同士の重複は構造上起きない（本文 DESIGN.md 参照）。
(function (global) {
  "use strict";

  var TYPES = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "H", "C"];
  var LABELS = { "1": "1", "2": "2", "3": "3", "4": "4", "5": "5", "6": "6", "7": "7", "8": "8", "9": "9", "H": "發", "C": "中" };

  function makeTile(t, red) {
    if (t === "C") red = true;            // 中は全て赤扱い
    if (t === "H") red = false;           // 發に赤はない
    return { t: t, red: !!red };
  }
  function isNumber(t) { return t >= "1" && t <= "9"; }
  function isTerminalOrHonor(t) { return t === "1" || t === "9" || t === "H" || t === "C"; }
  function isGreenType(t) { return t === "2" || t === "3" || t === "4" || t === "6" || t === "8" || t === "H"; }
  function isRed(tile) { return tile.t === "C" || !!tile.red; }
  function tileKey(tile) { return tile.t + (isRed(tile) && tile.t !== "C" ? "r" : ""); }
  function tileLabel(tile) { return (isRed(tile) && tile.t !== "C" ? "赤" : "") + LABELS[tile.t]; }
  function typeLabel(t) { return LABELS[t]; }

  // 手牌の妥当性チェック（枚数上限・赤の枚数）
  function validate(tiles) {
    var errors = [];
    var byType = {}, redByType = {};
    tiles.forEach(function (tile) {
      byType[tile.t] = (byType[tile.t] || 0) + 1;
      if (tile.red && isNumber(tile.t)) redByType[tile.t] = (redByType[tile.t] || 0) + 1;
      if (tile.red && tile.t === "H") errors.push("發に赤牌はないよ");
    });
    Object.keys(byType).forEach(function (t) {
      if (byType[t] > 4) errors.push(LABELS[t] + "が5枚以上ある（各4枚まで）");
    });
    Object.keys(redByType).forEach(function (t) {
      if (redByType[t] > 1) errors.push("赤" + LABELS[t] + "は1枚だけだよ");
    });
    return errors;
  }

  function countByType(tiles) {
    var c = {};
    tiles.forEach(function (tile) { c[tile.t] = (c[tile.t] || 0) + 1; });
    return c;
  }

  // 3枚がセット（順子 or 刻子）か判定。返り値: {kind:'sequence'|'triplet', tiles} or null
  function asSet(three) {
    var ts = three.map(function (x) { return x.t; });
    if (ts[0] === ts[1] && ts[1] === ts[2]) return { kind: "triplet", tiles: three.slice() };
    if (ts.every(isNumber)) {
      var nums = ts.map(Number).sort(function (a, b) { return a - b; });
      if (nums[1] === nums[0] + 1 && nums[2] === nums[0] + 2) return { kind: "sequence", tiles: three.slice() };
    }
    return null;
  }

  // 6枚を2セットに分ける全分割（重複は署名で除く）
  function partitions(tiles) {
    var out = [], seen = {};
    // tiles[0] を含むセットを固定して列挙
    for (var i = 1; i < 6; i++) {
      for (var j = i + 1; j < 6; j++) {
        var first = [tiles[0], tiles[i], tiles[j]];
        var rest = tiles.filter(function (_, k) { return k !== 0 && k !== i && k !== j; });
        var s1 = asSet(first), s2 = asSet(rest);
        if (!s1 || !s2) continue;
        var sig = [s1, s2].map(function (s) {
          return s.kind + ":" + s.tiles.map(tileKey).sort().join(",");
        }).sort().join("|");
        if (seen[sig]) continue;
        seen[sig] = true;
        out.push([s1, s2]);
      }
    }
    return out;
  }

  function setBasePoints(set) { return set.kind === "triplet" ? 2 : 1; }
  function setHasTerminalOrHonor(set) {
    return set.tiles.some(function (tile) { return isTerminalOrHonor(tile.t); });
  }
  function describeSet(set) {
    var names = set.tiles.map(tileLabel);
    if (set.kind === "triplet") return names[0] + "の刻子（同じ3枚）";
    var nums = set.tiles.filter(function (x) { return isNumber(x.t); })
      .map(function (x) { return Number(x.t); }).sort(function (a, b) { return a - b; });
    return nums.join("・") + "の順子（連番3枚）";
  }

  // 6枚の手の得点計算。opts: { dora: タイプ or null, dealer: boolean }
  function scoreHand(tiles, opts) {
    opts = opts || {};
    var result = {
      shapeWin: false, win: false, total: 0, finalScore: 0,
      base: 0, bonuses: [], yakuman: null, sets: [], errors: validate(tiles),
      redCount: 0, doraCount: 0
    };
    if (tiles.length !== 6) { result.errors.push("得点計算は6枚で"); return result; }
    if (result.errors.length) return result;

    var parts = partitions(tiles);
    if (parts.length === 0) return result;
    result.shapeWin = true;

    result.redCount = tiles.filter(isRed).length;
    result.doraCount = opts.dora ? tiles.filter(function (x) { return x.t === opts.dora; }).length : 0;
    var tanyao = tiles.every(function (x) { return !isTerminalOrHonor(x.t); });

    // 役満（ボーナスは加算せず、基本点だけを足す）
    var yakumans = [];
    if (tiles.every(isRed)) yakumans.push({ name: "スーパーレッド", points: 20 });
    if (tiles.every(function (x) { return isTerminalOrHonor(x.t); })) yakumans.push({ name: "チンヤオ", points: 15 });
    if (tiles.every(function (x) { return isGreenType(x.t) && !isRed(x); })) yakumans.push({ name: "オールグリーン", points: 10 });
    yakumans.sort(function (a, b) { return b.points - a.points; });
    var yakuman = yakumans[0] || null;
    result.yakuman = yakuman;

    var best = null;
    parts.forEach(function (part) {
      var base = part.reduce(function (s, set) { return s + setBasePoints(set); }, 0);
      var bonuses = [], total;
      if (yakuman) {
        total = base + yakuman.points;
      } else {
        // 赤とドラは重複して数える（赤いドラは1枚で2点）
        if (result.redCount) bonuses.push({ name: "赤牌×" + result.redCount, points: result.redCount });
        if (result.doraCount) bonuses.push({ name: "ドラ×" + result.doraCount, points: result.doraCount });
        if (tanyao) bonuses.push({ name: "タンヤオ", points: 1 });
        var chanta = part.every(setHasTerminalOrHonor);
        if (chanta) bonuses.push({ name: "チャンタ", points: 2 });
        total = base + bonuses.reduce(function (s, b) { return s + b.points; }, 0);
      }
      if (!best || total > best.total) best = { total: total, base: base, bonuses: bonuses, part: part };
    });

    result.total = best.total;
    result.base = best.base;
    result.bonuses = best.bonuses;
    result.sets = best.part.map(function (set) {
      return { kind: set.kind, points: setBasePoints(set), desc: describeSet(set), tiles: set.tiles.map(tileLabel) };
    });
    result.win = result.total >= 5;
    // 親ボーナス+2は「5点以上」の条件には含めず、成立後に加える
    result.finalScore = result.win ? result.total + (opts.dealer ? 2 : 0) : result.total;
    return result;
  }

  // 場に見えている枚数を差し引いた残り枚数（タイプ別）。
  // opts: { dora, visible: tile配列（捨て牌など）, ownDiscards: タイプ配列 }
  function remainingCounts(handTiles, opts) {
    opts = opts || {};
    var used = countByType(handTiles);
    if (opts.dora) used[opts.dora] = (used[opts.dora] || 0) + 1; // ドラ表示牌そのものが場に出ている
    (opts.visible || []).forEach(function (tile) { used[tile.t] = (used[tile.t] || 0) + 1; });
    var rem = {};
    TYPES.forEach(function (t) { rem[t] = Math.max(0, 4 - (used[t] || 0)); });
    return rem;
  }
  function redRemaining(handTiles, opts, t) {
    // そのタイプの赤がまだ場に残っているか（数牌のみ）
    if (!isNumber(t)) return false;
    // ドラ表示牌が赤だった場合、その赤は表示牌として消費済み（表示牌は表向きなので見て分かる）
    if (opts && opts.dora === t && opts.doraRed) return false;
    var seen = handTiles.concat((opts && opts.visible) || []);
    return !seen.some(function (x) { return x.t === t && x.red; });
  }

  // 5枚の手の待ち・有効牌分析
  function analyzeFive(tiles, opts) {
    opts = opts || {};
    var rem = remainingCounts(tiles, opts);
    var waits = [], shapeOnlyWaits = [];
    var ownDiscards = opts.ownDiscards || [];

    TYPES.forEach(function (t) {
      if (rem[t] <= 0) return;
      var variants = [makeTile(t, false)];
      if (isNumber(t) && redRemaining(tiles, opts, t)) variants.push(makeTile(t, true));
      if (t === "C") variants = [makeTile(t, true)];
      var bestScore = null, shapeScore = null;
      variants.forEach(function (v) {
        var sc = scoreHand(tiles.concat([v]), opts);
        if (!sc.shapeWin) return;
        if (sc.win) { if (!bestScore || sc.finalScore > bestScore.finalScore) bestScore = sc; }
        else if (!shapeScore || sc.total > shapeScore.total) shapeScore = sc;
      });
      var furiten = ownDiscards.indexOf(t) >= 0;
      if (bestScore) {
        waits.push({
          t: t, label: typeLabel(t), remaining: rem[t],
          score: bestScore.total, finalScore: bestScore.finalScore,
          yakuman: bestScore.yakuman ? bestScore.yakuman.name : null,
          furiten: furiten
        });
      } else if (shapeScore) {
        shapeOnlyWaits.push({
          t: t, label: typeLabel(t), remaining: rem[t],
          score: shapeScore.total, shortBy: 5 - shapeScore.total, furiten: furiten
        });
      }
    });

    var isTenpai = waits.length > 0;
    var result = {
      isTenpai: isTenpai, waits: waits, shapeOnlyWaits: shapeOnlyWaits,
      totalWaitTiles: waits.reduce(function (s, w) { return s + w.remaining; }, 0),
      maxScore: waits.reduce(function (s, w) { return Math.max(s, w.finalScore); }, 0),
      improvements: []
    };

    if (!isTenpai) {
      // 次に引くと「テンパイ or 即アガリ」になる牌（有効牌）
      TYPES.forEach(function (t) {
        if (rem[t] <= 0) return;
        var variants = [makeTile(t, false)];
        if (isNumber(t) && redRemaining(tiles, opts, t)) variants.push(makeTile(t, true));
        if (t === "C") variants = [makeTile(t, true)];
        var best = null;
        variants.forEach(function (v) {
          var six = tiles.concat([v]);
          var sc = scoreHand(six, opts);
          if (sc.win) {
            if (!best || !best.immediateWin) best = { t: t, immediateWin: true, finalScore: sc.finalScore };
            return;
          }
          // 引いた6枚から1枚切ってテンパイになるか
          for (var k = 0; k < six.length; k++) {
            var five = six.filter(function (_, idx) { return idx !== k; });
            var a5 = analyzeFiveShallow(five, opts);
            if (a5.isTenpai && (!best || best.immediateWin !== true)) {
              if (!best || a5.totalWaitTiles > best.waitTiles) {
                best = { t: t, immediateWin: false, discardKey: tileKey(six[k]), waitTiles: a5.totalWaitTiles, maxScore: a5.maxScore };
              }
            }
          }
        });
        if (best) result.improvements.push({
          t: t, label: typeLabel(t), remaining: rem[t],
          immediateWin: !!best.immediateWin,
          waitTiles: best.waitTiles || 0, maxScore: best.finalScore || best.maxScore || 0
        });
      });
      result.improvements.sort(function (a, b) {
        if (a.immediateWin !== b.immediateWin) return a.immediateWin ? -1 : 1;
        return (b.remaining * 10 + b.waitTiles) - (a.remaining * 10 + a.waitTiles);
      });
    }
    return result;
  }

  // 再帰用: 待ち判定だけの軽量版（improvements を辿らない）
  function analyzeFiveShallow(tiles, opts) {
    opts = opts || {};
    var rem = remainingCounts(tiles, opts);
    var waits = [];
    TYPES.forEach(function (t) {
      if (rem[t] <= 0) return;
      var variants = [makeTile(t, false)];
      if (isNumber(t) && redRemaining(tiles, opts, t)) variants.push(makeTile(t, true));
      if (t === "C") variants = [makeTile(t, true)];
      var ok = false, maxFinal = 0;
      variants.forEach(function (v) {
        var sc = scoreHand(tiles.concat([v]), opts);
        if (sc.win) { ok = true; maxFinal = Math.max(maxFinal, sc.finalScore); }
      });
      if (ok) waits.push({ t: t, remaining: rem[t], finalScore: maxFinal });
    });
    return {
      isTenpai: waits.length > 0, waits: waits,
      totalWaitTiles: waits.reduce(function (s, w) { return s + w.remaining; }, 0),
      maxScore: waits.reduce(function (s, w) { return Math.max(s, w.finalScore); }, 0)
    };
  }

  // 6枚（ツモ後）の捨て牌アドバイス
  function analyzeSix(tiles, opts) {
    opts = opts || {};
    var handScore = scoreHand(tiles, opts);
    if (handScore.win) return { win: true, score: handScore, discards: [] };

    var seen = {}, discards = [];
    tiles.forEach(function (tile, idx) {
      var key = tileKey(tile);
      if (seen[key]) return;
      seen[key] = true;
      var five = tiles.filter(function (_, k) { return k !== idx; });
      var a5 = analyzeFive(five, opts);
      var entry = {
        discard: tile, discardLabel: tileLabel(tile), handAfter: five,
        tenpai: a5.isTenpai, waits: a5.waits, shapeOnlyWaits: a5.shapeOnlyWaits,
        totalWaitTiles: a5.totalWaitTiles, maxScore: a5.maxScore,
        improvements: a5.improvements,
        improvementTiles: a5.improvements.reduce(function (s, x) { return s + x.remaining; }, 0),
        reasons: []
      };
      // 理由づけ
      if (a5.isTenpai) {
        entry.reasons.push("テンパイ: " + a5.waits.map(function (w) { return w.label; }).join("・") +
          "待ち 合計" + a5.totalWaitTiles + "枚");
        entry.reasons.push("アガれば最大" + a5.maxScore + "点");
        if (a5.waits.every(function (w) { return w.furiten; })) {
          entry.reasons.push("注意: 待ちが全部フリテン（自分の捨て牌）なのでロンはできずツモのみ");
        } else if (a5.waits.some(function (w) { return w.furiten; })) {
          entry.reasons.push("一部の待ちはフリテン（ロン不可・ツモのみ）");
        }
      } else if (a5.shapeOnlyWaits.length) {
        entry.reasons.push("形は " + a5.shapeOnlyWaits.map(function (w) { return w.label; }).join("・") +
          " で完成するけど点数があと" + a5.shapeOnlyWaits[0].shortBy + "点足りずアガれない（点数不足テンパイ）");
      }
      if (!a5.isTenpai && a5.improvements.length) {
        entry.reasons.push("次に " + a5.improvements.slice(0, 4).map(function (x) { return x.label; }).join("・") +
          " を引けば前進（有効牌 合計" + entry.improvementTiles + "枚）");
      }
      if (isRed(tile)) entry.reasons.push("赤牌を切る（残すと1点）");
      if (opts.dora && tile.t === opts.dora) entry.reasons.push("ドラを切る（残すと1点）");
      if (handScore.shapeWin && !handScore.win) {
        entry.reasons.push("今の6枚は形は完成・あと" + (5 - handScore.total) + "点で点数不足");
      }
      // 順位付け用スコア
      entry.rank = (a5.isTenpai ? 10000 : 0) +
        a5.totalWaitTiles * 100 + a5.maxScore * 10 +
        entry.improvementTiles * 5 +
        a5.improvements.reduce(function (s, x) { return Math.max(s, x.maxScore || 0); }, 0);
      if (isRed(tile)) entry.rank -= 15;
      if (opts.dora && tile.t === opts.dora) entry.rank -= 15;
      discards.push(entry);
    });
    discards.sort(function (a, b) { return b.rank - a.rank; });
    return { win: false, score: handScore, discards: discards };
  }

  // ---------- 危険牌予測（相手のロン確率） ----------
  // 前提: 相手の手牌は見えないので、場に見えている牌（自分の手牌・ドラ表示・全員の捨て牌）を
  // 差し引いた残り山から、相手の5枚をモンテカルロでサンプリングして
  // 「この牌を切ると、その相手が何点でアガれる手になっている確率」を数える。
  // 相手ごとに独立にサンプリングする近似（相手同士の手牌の重なりは無視）。
  // フリテン（相手が既に捨てた牌種）は、その相手には確率0で確定。
  function mulberry32(seed) {
    var a = seed >>> 0;
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function fullTileSet() {
    var tiles = [];
    TYPES.forEach(function (t) {
      if (isNumber(t)) { tiles.push(makeTile(t, true), makeTile(t, false), makeTile(t, false), makeTile(t, false)); }
      else if (t === "C") { tiles.push(makeTile(t, true), makeTile(t, true), makeTile(t, true), makeTile(t, true)); }
      else { tiles.push(makeTile(t, false), makeTile(t, false), makeTile(t, false), makeTile(t, false)); }
    });
    return tiles;
  }

  // pool から1枚取り除く。preferRed=true なら赤を、false なら通常を優先（無ければもう一方）
  function removeFromPool(pool, t, preferRed) {
    var idx = -1, fallback = -1;
    for (var i = 0; i < pool.length; i++) {
      if (pool[i].t !== t) continue;
      if (fallback < 0) fallback = i;
      if (isRed(pool[i]) === preferRed) { idx = i; break; }
    }
    if (idx < 0) idx = fallback;
    if (idx >= 0) pool.splice(idx, 1);
    return idx >= 0;
  }

  function buildPool(opts) {
    var pool = fullTileSet();
    (opts.hand || []).forEach(function (tile) { removeFromPool(pool, tile.t, isRed(tile)); });
    if (opts.dora) removeFromPool(pool, opts.dora, !!opts.doraRed);
    (opts.ownDiscards || []).forEach(function (t) { removeFromPool(pool, t, false); });
    (opts.opponents || []).forEach(function (op) {
      (op.discards || []).forEach(function (t) { removeFromPool(pool, t, false); });
    });
    (opts.visible || []).forEach(function (tile) { removeFromPool(pool, tile.t, isRed(tile)); });
    return pool;
  }

  function danger(opts) {
    opts = opts || {};
    var samples = opts.samples || 1500;
    var rand = mulberry32(opts.seed || 1234);
    var pool = buildPool(opts);
    var opponents = opts.opponents || [];
    // 候補は手牌の実物（赤かどうかも含めて区別）
    var candByKey = {}, candKeys = [];
    (opts.hand || []).forEach(function (tile) {
      var k = tileKey(tile);
      if (!candByKey[k]) { candByKey[k] = tile; candKeys.push(k); }
    });
    var stats = {}; // key -> {perOpp: [{hits, scoreSum}], }
    candKeys.forEach(function (k) { stats[k] = []; });

    if (pool.length < 5 || opponents.length === 0) {
      return { error: "場の情報が不足（残り山が5枚未満 or 相手未設定）", byKey: {}, candidates: [] };
    }

    opponents.forEach(function (op, oi) {
      var furiten = {};
      (op.discards || []).forEach(function (t) { furiten[t] = true; });
      var hits = {}, scoreSum = {};
      candKeys.forEach(function (k) { hits[k] = 0; scoreSum[k] = 0; });
      for (var s = 0; s < samples; s++) {
        // pool から重複なしで5枚サンプリング（部分 Fisher-Yates）
        var idxs = pool.map(function (_, i) { return i; });
        var hand5 = [];
        for (var d = 0; d < 5; d++) {
          var j = d + Math.floor(rand() * (idxs.length - d));
          var tmp = idxs[d]; idxs[d] = idxs[j]; idxs[j] = tmp;
          hand5.push(pool[idxs[d]]);
        }
        for (var c = 0; c < candKeys.length; c++) {
          var k = candKeys[c], tile = candByKey[k];
          if (furiten[tile.t]) continue;
          var sc = scoreHand(hand5.concat([tile]), { dora: opts.dora || null });
          if (sc.win) { hits[k]++; scoreSum[k] += sc.total; }
        }
      }
      candKeys.forEach(function (k) {
        stats[k].push({
          name: op.name || ("相手" + (oi + 1)),
          prob: hits[k] / samples,
          avgScore: hits[k] ? scoreSum[k] / hits[k] : 0,
          furitenSafe: !!furiten[candByKey[k].t]
        });
      });
    });

    var byKey = {}, candidates = [];
    candKeys.forEach(function (k) {
      var per = stats[k];
      var pStay = 1, expLoss = 0, allFuriten = per.every(function (p) { return p.furitenSafe; });
      per.forEach(function (p) { pStay *= (1 - p.prob); expLoss += p.prob * p.avgScore; });
      var entry = {
        tile: candByKey[k], key: k, label: tileLabel(candByKey[k]),
        perOpponent: per, pAny: 1 - pStay, expectedLoss: expLoss, allFuritenSafe: allFuriten
      };
      entry.level = allFuriten ? "safe" : entry.pAny >= 0.25 ? "high" : entry.pAny >= 0.12 ? "mid" : "low";
      byKey[k] = entry; candidates.push(entry);
    });
    candidates.sort(function (a, b) { return b.pAny - a.pAny; });
    return { byKey: byKey, candidates: candidates, poolCount: pool.length, samples: samples };
  }

  function advise(tiles, opts) {
    if (tiles.length === 6) return Object.assign({ mode: "six" }, analyzeSix(tiles, opts));
    if (tiles.length === 5) return Object.assign({ mode: "five" }, analyzeFive(tiles, opts));
    return { mode: "incomplete", errors: ["手牌は5枚（待ち分析）か6枚（捨て牌相談）で入れてね"] };
  }

  var api = {
    TYPES: TYPES, LABELS: LABELS,
    makeTile: makeTile, isNumber: isNumber, isRed: isRed,
    isTerminalOrHonor: isTerminalOrHonor, tileKey: tileKey, tileLabel: tileLabel, typeLabel: typeLabel,
    validate: validate, scoreHand: scoreHand, analyzeFive: analyzeFive, analyzeSix: analyzeSix,
    advise: advise, remainingCounts: remainingCounts, danger: danger, buildPool: buildPool
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  global.Suzume = api;
})(typeof window !== "undefined" ? window : globalThis);
