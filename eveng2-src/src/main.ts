// オセロ・アドバイザー for Even G2（Even Hub アプリ）
// 構成: Even アプリ内 WebView で動き、
//   - 撮影/アルバム取得はブリッジ API（captureImageFromCamera / pickImageFromAlbum、base64で返る）
//   - 認識（四隅合わせ＋色サンプリング）と助言エンジンは Web 側（新米版と共通の othello.js）
//   - G2 表示はコンテナ方式: 盤面=画像コンテナ×2（288x144 を上下2枚）、助言=テキストコンテナ
//   - 画像データはグレースケール bytes（1px=1byte、コンテナ寸法と一致させる）
// グラス入力: CLICK=撮影 / SCROLL_TOP・BOTTOM=候補切替 / DOUBLE_CLICK=終了
import {
  waitForEvenAppBridge,
  OsEventTypeList,
  StartUpPageCreateResult,
  ImageRawDataUpdateResult,
  CreateStartUpPageContainer,
  TextContainerProperty,
  ImageContainerProperty,
  TextContainerUpgrade,
  ImageRawDataUpdate,
  type EvenAppBridge,
} from '@evenrealities/even_hub_sdk';
import './othello.js';
import type { AdviceResult } from './othello.d.ts';

const O = window.Othello;
const E = O.EMPTY, B = O.BLACK, W = O.WHITE;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const statusEl = $('status');
const setStatus = (s: string) => { statusEl.textContent = s; };

// ---------- 状態 ----------
let bridge: EvenAppBridge | null = null;
let board: number[] = O.initialBoard();
let confidence: number[] = new Array(64).fill(1);
let advice: AdviceResult | null = null;
let selected = 0; // 候補の選択中インデックス（0=1位）
let glassesReady = false;

// ---------- 盤面画像（グラス用 288x288 をグレースケール化） ----------
const BOARD_PX = 288;
const CELL = BOARD_PX / 8;

function renderBoardGray(): Uint8Array {
  const cv = document.createElement('canvas');
  cv.width = BOARD_PX; cv.height = BOARD_PX;
  const ctx = cv.getContext('2d', { willReadFrequently: true })!;
  ctx.fillStyle = 'rgb(36,36,36)';
  ctx.fillRect(0, 0, BOARD_PX, BOARD_PX);
  // マス目
  ctx.strokeStyle = 'rgb(110,110,110)';
  ctx.lineWidth = 1;
  for (let i = 0; i <= 8; i++) {
    ctx.beginPath(); ctx.moveTo(i * CELL + 0.5, 0); ctx.lineTo(i * CELL + 0.5, BOARD_PX); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(0, i * CELL + 0.5); ctx.lineTo(BOARD_PX, i * CELL + 0.5); ctx.stroke();
  }
  // 合法手ドット
  const turn = currentTurn();
  const legal = O.legalMoves(board, turn);
  ctx.fillStyle = 'rgb(130,130,130)';
  legal.forEach((_f, idx) => {
    const cx = (idx % 8) * CELL + CELL / 2, cy = Math.floor(idx / 8) * CELL + CELL / 2;
    ctx.beginPath(); ctx.arc(cx, cy, 4, 0, Math.PI * 2); ctx.fill();
  });
  // 石
  for (let i = 0; i < 64; i++) {
    if (board[i] === E) continue;
    const cx = (i % 8) * CELL + CELL / 2, cy = Math.floor(i / 8) * CELL + CELL / 2;
    ctx.beginPath(); ctx.arc(cx, cy, 14, 0, Math.PI * 2);
    if (board[i] === W) { ctx.fillStyle = 'rgb(255,255,255)'; ctx.fill(); }
    else { ctx.fillStyle = 'rgb(0,0,0)'; ctx.fill(); ctx.strokeStyle = 'rgb(235,235,235)'; ctx.lineWidth = 3; ctx.stroke(); }
  }
  // 候補マーカー（順位数字＋選択中は太リング）
  if (advice && !advice.pass) {
    advice.moves.forEach((m, r) => {
      const x = (m.idx % 8) * CELL, y = Math.floor(m.idx / 8) * CELL;
      ctx.strokeStyle = r === selected ? 'rgb(255,255,255)' : 'rgb(175,175,175)';
      ctx.lineWidth = r === selected ? 4 : 2;
      ctx.strokeRect(x + 2, y + 2, CELL - 4, CELL - 4);
      ctx.fillStyle = 'rgb(255,255,255)';
      ctx.font = 'bold 15px sans-serif';
      ctx.fillText(String(r + 1), x + 4, y + 16);
    });
  }
  const data = ctx.getImageData(0, 0, BOARD_PX, BOARD_PX).data;
  const gray = new Uint8Array(BOARD_PX * BOARD_PX);
  for (let i = 0; i < BOARD_PX * BOARD_PX; i++) {
    gray[i] = Math.round(0.299 * data[i * 4] + 0.587 * data[i * 4 + 1] + 0.114 * data[i * 4 + 2]);
  }
  return gray;
}

// ---------- グラス表示 ----------
const IMG_TOP = { containerID: 1, containerName: 'board-top' };
const IMG_BTM = { containerID: 2, containerName: 'board-btm' };
const TXT_ADVICE = { containerID: 3, containerName: 'advice' };
const TXT_SUB = { containerID: 4, containerName: 'sub' };
const TXT_STATUS = { containerID: 5, containerName: 'status' };

function currentTurn(): number {
  return parseInt($<HTMLSelectElement>('turnSel').value, 10);
}
const turnLabel = (p: number) => (p === B ? '●黒の番' : '○白の番');

function adviceText(): string {
  const p = currentTurn();
  if (!advice) return '撮影すると\nおすすめを表示します';
  const c = advice.counts;
  if (advice.gameOver) return `終局\n黒 ${c.black} - 白 ${c.white}`;
  if (advice.pass) return `${turnLabel(p)}\n打てる手がありません\n→ パス`;
  const m = advice.moves[selected];
  const lines = [
    `${turnLabel(p)} おすすめ${selected + 1}位`,
    `${m.coord.toUpperCase()}（${m.flipCount}枚返せる）`,
    ...m.reasons.slice(1, 3),
    `黒 ${c.black} - 白 ${c.white}`,
  ];
  return lines.join('\n');
}

function subText(): string {
  if (!advice || advice.pass || !advice.moves.length) {
    return 'クリック: 撮影\nスクロール: 候補切替\nダブルクリック: 終了';
  }
  const list = advice.moves.map((m, i) => `${i + 1}:${m.coord.toUpperCase()}`).join('  ');
  return `候補 ${list}\n全${advice.allCount ?? advice.moves.length}手 / 読み深さ${advice.depth}\nクリック: 撮影 スクロール: 候補`;
}

async function pushToGlasses(status: string) {
  if (!bridge || !glassesReady) return;
  try {
    const gray = renderBoardGray();
    const half = BOARD_PX * (BOARD_PX / 2);
    const top = await bridge.updateImageRawData(new ImageRawDataUpdate({ ...IMG_TOP, imageData: gray.slice(0, half) }));
    const btm = await bridge.updateImageRawData(new ImageRawDataUpdate({ ...IMG_BTM, imageData: gray.slice(half) }));
    if (top !== ImageRawDataUpdateResult.success || btm !== ImageRawDataUpdateResult.success) {
      setStatus(`画像送信に失敗（top=${top} / btm=${btm}）`);
      return;
    }
    await bridge.textContainerUpgrade(new TextContainerUpgrade({ ...TXT_ADVICE, content: adviceText() }));
    await bridge.textContainerUpgrade(new TextContainerUpgrade({ ...TXT_SUB, content: subText() }));
    await bridge.textContainerUpgrade(new TextContainerUpgrade({ ...TXT_STATUS, content: status }));
  } catch (e) {
    setStatus('グラス送信でエラー: ' + String(e));
  }
}

async function initGlasses() {
  if (!bridge) return;
  const result = await bridge.createStartUpPageContainer(new CreateStartUpPageContainer({
    containerTotalNum: 5,
    imageObject: [
      new ImageContainerProperty({ xPosition: 0, yPosition: 0, width: 288, height: 144, ...IMG_TOP }),
      new ImageContainerProperty({ xPosition: 0, yPosition: 144, width: 288, height: 144, ...IMG_BTM }),
    ],
    textObject: [
      new TextContainerProperty({
        xPosition: 296, yPosition: 8, width: 272, height: 128, ...TXT_ADVICE,
        content: 'オセロ・アドバイザー\n撮影すると\nおすすめを表示します', isEventCapture: 1,
      }),
      new TextContainerProperty({
        xPosition: 296, yPosition: 144, width: 272, height: 96, ...TXT_SUB,
        content: 'クリック: 撮影\nスクロール: 候補切替\nダブルクリック: 終了', isEventCapture: 0,
      }),
      new TextContainerProperty({
        xPosition: 296, yPosition: 248, width: 272, height: 36, ...TXT_STATUS,
        content: '待機中', isEventCapture: 0,
      }),
    ],
  }));
  if (result === StartUpPageCreateResult.success) {
    glassesReady = true;
    await pushToGlasses('待機中');
  } else {
    setStatus(`グラス画面の作成に失敗（結果=${result}）`);
  }
}

// ---------- グラス入力 ----------
function handleGlassesEvent(event: any) {
  const item = event?.textEvent ?? event?.listEvent ?? event?.sysEvent;
  if (!item) return;
  const t = OsEventTypeList.fromJson(item.eventType) ?? item.eventType;
  // 公式サンプルの注意点: CLICK は eventType が undefined に化けることがある。
  // text/list イベントで型が取れない場合はクリックとして扱う。
  const asClick = t === OsEventTypeList.CLICK_EVENT ||
    (t === undefined && (event?.textEvent ?? event?.listEvent));
  if (asClick) {
    void captureFromBridge();
  } else if (t === OsEventTypeList.SCROLL_BOTTOM_EVENT || t === OsEventTypeList.SCROLL_TOP_EVENT) {
    if (advice && advice.moves.length > 1) {
      const d = t === OsEventTypeList.SCROLL_BOTTOM_EVENT ? 1 : -1;
      selected = (selected + d + advice.moves.length) % advice.moves.length;
      void pushToGlasses('候補を表示中');
      renderPhoneBoard();
    }
  } else if (t === OsEventTypeList.DOUBLE_CLICK_EVENT) {
    // 個人用ビルドでは即時終了（0）。ストア提出時はルート画面で 1（確認ダイアログ）が必須。
    // なお 1 のダイアログ表示後はそのセッション中 updateImageRawData が失敗する既知不具合がある。
    void bridge?.shutDownPageContainer(0);
  }
}

// ---------- 写真の取得 ----------
const img = $<HTMLImageElement>('photoImg');
let imgReady = false;

function showPhoto(src: string) {
  img.onload = () => {
    imgReady = true;
    $('stage').style.display = 'block';
    $('recogRow').style.display = 'flex';
    layoutHandles();
    setStatus('黄色い丸を盤の四隅に合わせて「盤面を読み取る」を押してね');
  };
  img.src = src;
}

async function captureFromBridge() {
  if (!bridge) { $('fileInput').click(); return; }
  setStatus('カメラ起動中…');
  const asset = await bridge.captureImageFromCamera();
  if (!asset) { setStatus('撮影がキャンセルされました'); return; }
  showPhoto(`data:${asset.mimeType};base64,${asset.base64}`);
}

async function pickFromBridge() {
  if (!bridge) { $('fileInput').click(); return; }
  const asset = await bridge.pickImageFromAlbum();
  if (!asset) { setStatus('画像が選ばれませんでした'); return; }
  showPhoto(`data:${asset.mimeType};base64,${asset.base64}`);
}

$('camBtn').addEventListener('click', () => void captureFromBridge());
$('albBtn').addEventListener('click', () => void pickFromBridge());
$('fileInput').addEventListener('change', (ev) => {
  const f = (ev.target as HTMLInputElement).files?.[0];
  if (f) showPhoto(URL.createObjectURL(f));
});

// ---------- 四隅合わせ ----------
const corners = [{ x: 0.08, y: 0.08 }, { x: 0.92, y: 0.08 }, { x: 0.92, y: 0.92 }, { x: 0.08, y: 0.92 }];
const handles = Array.from(document.querySelectorAll<SVGCircleElement>('.handle'));
const poly = $('poly') as unknown as SVGPolygonElement;

function layoutHandles() {
  handles.forEach((h, i) => {
    h.setAttribute('cx', String(corners[i].x * 100));
    h.setAttribute('cy', String(corners[i].y * 100));
  });
  poly.setAttribute('points', corners.map((p) => `${p.x * 100},${p.y * 100}`).join(' '));
}
window.addEventListener('resize', () => { if (imgReady) layoutHandles(); });
handles.forEach((h, i) => {
  h.addEventListener('pointerdown', (ev) => {
    ev.preventDefault();
    const rect = img.getBoundingClientRect();
    const move = (e: PointerEvent) => {
      corners[i].x = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
      corners[i].y = Math.min(1, Math.max(0, (e.clientY - rect.top) / rect.height));
      layoutHandles();
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  });
});

// ---------- 認識（Web試作と同一アルゴリズム） ----------
function computeHomography(dst: { x: number; y: number }[]): number[] {
  const src = [[0, 0], [1, 0], [1, 1], [0, 1]];
  const A: number[][] = [], bvec: number[] = [];
  for (let i = 0; i < 4; i++) {
    const x = src[i][0], y = src[i][1], X = dst[i].x, Y = dst[i].y;
    A.push([x, y, 1, 0, 0, 0, -X * x, -X * y]); bvec.push(X);
    A.push([0, 0, 0, x, y, 1, -Y * x, -Y * y]); bvec.push(Y);
  }
  const n = 8;
  for (let col = 0, row = 0; col < n && row < n; col++, row++) {
    let piv = row;
    for (let r = row + 1; r < n; r++) if (Math.abs(A[r][col]) > Math.abs(A[piv][col])) piv = r;
    [A[row], A[piv]] = [A[piv], A[row]];
    [bvec[row], bvec[piv]] = [bvec[piv], bvec[row]];
    const div = A[row][col] || 1e-12;
    for (let c = col; c < n; c++) A[row][c] /= div;
    bvec[row] /= div;
    for (let r2 = 0; r2 < n; r2++) {
      if (r2 === row) continue;
      const f = A[r2][col];
      if (f === 0) continue;
      for (let c2 = col; c2 < n; c2++) A[r2][c2] -= f * A[row][c2];
      bvec[r2] -= f * bvec[row];
    }
  }
  return bvec;
}
const applyH = (H: number[], u: number, v: number) => {
  const w = H[6] * u + H[7] * v + 1;
  return { x: (H[0] * u + H[1] * v + H[2]) / w, y: (H[3] * u + H[4] * v + H[5]) / w };
};

function recognize() {
  if (!imgReady || !img.naturalWidth) return;
  const canvas = document.createElement('canvas');
  canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(img, 0, 0);
  const dst = corners.map((p) => ({ x: p.x * img.naturalWidth, y: p.y * img.naturalHeight }));
  const H = computeHomography(dst);
  const p00 = applyH(H, 0, 0), p10 = applyH(H, 1, 0);
  const cellPx = Math.hypot(p10.x - p00.x, p10.y - p00.y) / 8;
  const radius = Math.max(2, Math.floor(cellPx * 0.22));

  const nb = new Array(64).fill(E);
  const nc = new Array(64).fill(1);
  let low = 0;
  for (let r = 0; r < 8; r++) {
    for (let c = 0; c < 8; c++) {
      const pt = applyH(H, (c + 0.5) / 8, (r + 0.5) / 8);
      const x0 = Math.max(0, Math.floor(pt.x - radius)), y0 = Math.max(0, Math.floor(pt.y - radius));
      const size = radius * 2;
      const data = ctx.getImageData(x0, y0, Math.min(size, canvas.width - x0), Math.min(size, canvas.height - y0)).data;
      let sr = 0, sg = 0, sb = 0, cnt = 0;
      for (let k = 0; k < data.length; k += 4) { sr += data[k]; sg += data[k + 1]; sb += data[k + 2]; cnt++; }
      const R = sr / cnt, G = sg / cnt, Bl = sb / cnt;
      const lum = 0.299 * R + 0.587 * G + 0.114 * Bl;
      const greenish = G - (R + Bl) / 2;
      const idx = r * 8 + c;
      let cls: number, conf: number;
      if (lum < 80) { cls = B; conf = Math.min(1, (80 - lum) / 50 + 0.55); }
      else if (lum > 175 && greenish < 25) { cls = W; conf = Math.min(1, (lum - 175) / 50 + 0.55); }
      else if (greenish > 14) { cls = E; conf = Math.min(1, (greenish - 14) / 30 + 0.5); }
      else { cls = E; conf = 0.3; }
      nb[idx] = cls; nc[idx] = conf;
      if (conf < 0.6) low++;
    }
  }
  board = nb; confidence = nc;
  setStatus(`読み取り完了（自信が低いマス: ${low}個）。盤面をタップで直せます`);
  runAdvice();
}
$('recognizeBtn').addEventListener('click', recognize);

// ---------- スマホ側の盤面表示・修正 ----------
function renderPhoneBoard() {
  const grid = $('boardGrid');
  grid.innerHTML = '';
  const rankByIdx = new Map<number, number>();
  if (advice && !advice.pass) advice.moves.forEach((m, i) => rankByIdx.set(m.idx, i + 1));
  for (let i = 0; i < 64; i++) {
    const cell = document.createElement('div');
    cell.className = 'cell' + (confidence[i] < 0.6 ? ' lowconf' : '');
    if (board[i] !== E) {
      const d = document.createElement('div');
      d.className = 'disc ' + (board[i] === B ? 'b' : 'w');
      cell.appendChild(d);
    }
    const rk = rankByIdx.get(i);
    if (rk) {
      const s = document.createElement('span');
      s.className = 'rank';
      s.textContent = String(rk);
      cell.appendChild(s);
    }
    cell.addEventListener('click', () => {
      board[i] = board[i] === E ? B : board[i] === B ? W : E;
      confidence[i] = 1;
      runAdvice(false);
    });
    grid.appendChild(cell);
  }
}

function runAdvice(send = true) {
  advice = O.advise(board, currentTurn());
  selected = 0;
  const box = $('adviceBox');
  box.style.display = 'block';
  box.textContent = adviceText() + '\n' + subText();
  $('sendRow').style.display = 'flex';
  renderPhoneBoard();
  if (send) void pushToGlasses('表示中');
}
$('adviseBtn').addEventListener('click', () => runAdvice(true));
$<HTMLSelectElement>('turnSel').addEventListener('change', () => { if (advice) runAdvice(true); });

// ---------- 起動 ----------
(async () => {
  renderPhoneBoard();
  try {
    bridge = await Promise.race([
      waitForEvenAppBridge(),
      new Promise<null>((res) => setTimeout(() => res(null), 2500)),
    ]);
  } catch {
    bridge = null;
  }
  if (bridge) {
    bridge.onEvenHubEvent(handleGlassesEvent);
    await initGlasses();
    setStatus('グラス接続OK。撮影ボタンか、グラスのクリックで撮影できます');
  } else {
    setStatus('ブラウザモード（グラス未接続）: ファイル選択で動作確認できます');
  }
})();
