// othello.js（Web試作と共通のエンジン）の最小型定義
export interface MoveAdvice {
  idx: number;
  coord: string;
  flips: number[];
  flipCount: number;
  score: number;
  reasons: string[];
}

export interface AdviceResult {
  pass: boolean;
  gameOver?: boolean;
  moves: MoveAdvice[];
  allCount?: number;
  depth: number;
  counts: { black: number; white: number; empty: number };
}

export interface OthelloApi {
  EMPTY: number;
  BLACK: number;
  WHITE: number;
  initialBoard(): number[];
  idxToCoord(i: number): string;
  legalMoves(board: number[], player: number): Map<number, number[]>;
  applyMove(board: number[], player: number, idx: number, flips?: number[]): number[];
  countDiscs(board: number[]): { black: number; white: number; empty: number };
  advise(board: number[], player: number, opts?: { depth?: number; topN?: number }): AdviceResult;
}

declare global {
  interface Window {
    Othello: OthelloApi;
  }
}
