import type {Documentation} from "./api";

/** How many lines of a documentation show before "more". */
export const DOC_FOLD_LINES = 6;

/** The catalog key of the words saying where a documentation comment is, by its shape. */
export function docPlaceKey(doc: Documentation): string {
  switch (doc.shape) {
    case "AFTER_IS": return "doc.place.afterIs";
    case "AFTER_BEGIN": return "doc.place.afterBegin";
    case "END_OF_LINE": return "doc.place.endOfLine";
    default: return "doc.place.above";
  }
}

/** The catalog key of the mark of the other half's documentation, null for the declaration's own. */
export function docFromKey(doc: { from: string | null }): string | null {
  return doc.from === "SPEC" ? "doc.fromSpec" : doc.from === "BODY" ? "doc.fromBody" : null;
}

/**
 * What a parameter's row shows of its documentation: the first line of the first one, and whether there is more (more
 * lines, a cut text, or the other half's too), which its own page shows.
 */
export function docSummary(docs: Documentation[]): { text: string; more: boolean; from: string | null } | null {
  const doc = docs[0];
  if (!doc) return null;
  const lines = doc.text.split("\n");
  const first = lines.find(line => line.trim() !== "")?.trim() ?? "";
  return { text: first, more: lines.filter(line => line.trim() !== "").length > 1 || doc.textCut || docs.length > 1, from: doc.from };
}
