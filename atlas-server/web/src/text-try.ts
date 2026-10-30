import type {Answer, GroupPage, PackageUses, TextPage} from "./api";
import {api} from "./api";
import {esc} from "./html";
import {t} from "./i18n";
import {textHref} from "./text-search";

// ---- "Try": texts taken from the index served, each a link whose URL is the search ---------------------------------

/** One example: the text searched, and its count of lines and files. */
interface Example {
  text: string;
  lines: number;
  files: number;
}

const ROUTINES = ["PROCEDURE", "FUNCTION"];
const WRITES = ["SQL_INSERT", "SQL_UPDATE", "SQL_DELETE", "SQL_MERGE"];
const TABLES = ["TABLE", "OBJECT_TABLE", "UNKNOWN_RELATION"];

/**
 * The candidate texts of each example, each example a different kind: the package most used from outside as a prefix
 * (`pkg.`), the call of the most used package routine as written (`pkg.routine`), and a statement on the most written
 * table (`update table`, else `into table`, as code writes it).
 */
async function candidates(): Promise<string[][]> {
  const packages = (await api<Answer<PackageUses[]>>("api/top-packages")).result;
  const found: string[][] = [];
  const top = packages[0];
  if (top) found.push([top.entity.name.toLowerCase() + "."]);
  const routine = packages.filter(p => p.topMember && ROUTINES.includes(p.topMember.kind)).sort((a, b) => b.topMemberUses - a.topMemberUses)[0];
  if (routine) found.push([`${routine.entity.name}.${routine.topMember!.name}`.toLowerCase()]);
  const tables = (await api<Answer<GroupPage>>("api/query/groups", { roles: WRITES.join(","), entityKinds: TABLES.join(","), groupBy: "ENTITY", limit: 1 })).result;
  const table = tables.groups[0]?.values[0]?.entity?.name.toLowerCase();
  if (table) found.push(["update " + table, "into " + table]);
  return found;
}

/** [text] with its count of lines and files; null when it finds no line or cannot be read. */
async function counted(text: string): Promise<Example | null> {
  try {
    const page = (await api<Answer<TextPage>>("api/text", { text, limit: 1 })).result;
    return page.total > 0 ? { text, lines: page.total, files: page.files } : null;
  } catch {
    return null;
  }
}

/** The examples: for each, its first candidate that finds a line; one whose candidates find none is left out. */
async function examples(): Promise<Example[]> {
  const found = await Promise.all((await candidates()).map(async texts => {
    for (const text of texts) {
      const example = await counted(text);
      if (example) return example;
    }
    return null;
  }));
  return found.filter((e): e is Example => e !== null);
}

let loading: Promise<Example[]> | null = null;

/** Fills [target] with the examples, read once per page, while [wanted] (no search ran meanwhile); hidden when none. */
export async function showExamples(target: HTMLElement, wanted: () => boolean) {
  loading ??= examples();
  const list = await loading.catch(() => []);
  if (!wanted()) return;
  if (list.length === 0) {
    target.hidden = true;
    return;
  }
  const lang = new URLSearchParams(location.search).get("lang");
  const item = (e: Example) => `<li><a href="${esc(textHref({ text: e.text }, lang))}"><span class="mono">${esc(e.text)}</span> · ` +
    `<span class="muted">${esc(t("text.total", { count: e.lines, files: t("text.files", { count: e.files }) }))}</span></a></li>`;
  target.innerHTML = `<h2>${esc(t("query.try"))}</h2><ul class="try">${list.map(item).join("")}</ul>`;
  target.hidden = false;
}
