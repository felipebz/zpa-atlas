import {CODE_COLORS} from "./api";
import type {LinePiece} from "./text-search";

/**
 * A line of code as shown ([shownLine]'s answer): colored, each marked piece inside a `<mark>`, in one inline box that
 * wraps with its continuation rows indented (the `code.cl` style). The code search's rows and the "Used by" rows show
 * their lines this way.
 */
export function codeLineNode(shown: { text: string; pieces: LinePiece[] }): HTMLElement {
  const code = document.createElement("code");
  code.className = "cl";
  const flow = code.appendChild(Object.assign(document.createElement("span"), { className: "flow" }));
  appendPieces(flow, shown);
  return code;
}

/** The pieces of [shown] into [target]: consecutive marked pieces share one mark, whatever their colors. */
export function appendPieces(target: HTMLElement, shown: { text: string; pieces: LinePiece[] }) {
  let mark: HTMLElement | null = null;
  for (const piece of shown.pieces) {
    const part = piece.color === null ? document.createTextNode(shown.text.slice(piece.from, piece.to))
      : Object.assign(document.createElement("span"), { className: "tok-" + CODE_COLORS[piece.color], textContent: shown.text.slice(piece.from, piece.to) });
    if (piece.mark) {
      if (!mark) target.append(mark = document.createElement("mark"));
      mark.append(part);
    } else {
      mark = null;
      target.append(part);
    }
  }
}
