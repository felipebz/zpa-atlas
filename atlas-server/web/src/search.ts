import { api, errorMessage } from "./api";
import type { Answer, Count, RunSummary, SearchHit } from "./api";
import { showBanner } from "./banner";
import { KINDS } from "./codes";
import { entityLink, kindLabel } from "./entity-html";
import { entityHref, esc, getOrPut, qualifierHtml } from "./html";
import { initI18n, t } from "./i18n";
import { displayParts, parentKeyOf } from "./names";

const LIMIT = 200;

const kindSelect = document.getElementById("kind") as HTMLSelectElement;
let runSummary: Promise<RunSummary | null> | null = null;

interface HitGroup {
  spec: SearchHit | null;
  body: SearchHit | null;
  usages: number;
}

/** The hits in order, a spec and its body as one group whose uses are the sum of both. */
function groupHits(hits: SearchHit[]) {
  const groups = new Map<string, HitGroup>();
  for (const h of hits) {
    const parent = parentKeyOf(h.key);
    const group = getOrPut(groups, parent, () => ({ spec: null, body: null, usages: 0 }));
    if (h.key === parent) group.spec = h;
    else group.body = h;
    group.usages += h.usages;
  }
  return [...groups.values()];
}

/** A hit as two lines: its name, kind and uses, then the program it is in. */
function hitRow(group: HitGroup) {
  const h = (group.spec ?? group.body)!;
  const body = group.body ? ` <a class="badge" href="${entityHref(group.body.key)}">${esc(t("entity.overload.body"))}</a>` : "";
  const parts = displayParts(h.display, h.name);
  const container = parts.shortQualifier.replace(/\.$/, "");
  const containerBody = parts.qualifierBody ? ` <span class="badge">${esc(t("entity.overload.body"))}</span>` : "";
  return `<li>
    <div class="hit-head">
      <span class="hit-name">${entityLink(h, { relative: true, noBadge: true })}${body}</span>
      <span class="badge">${esc(kindLabel(h.kind))}</span>
      <span class="hit-uses">${esc(t("search.usages", { count: group.usages }))}</span>
    </div>
    ${container ? `<div class="hit-in muted">${qualifierHtml(container)}${containerBody}</div>` : ""}
  </li>`;
}

async function home(results: HTMLElement) {
  const run = await runSummary;
  const sum = (list: Count[]) => list.reduce((n, c) => n + c.count, 0);
  results.innerHTML = `<div class="card">` +
    (run ? `<p>${esc(t("home.files", { count: sum(run.filesByParseStatus) }))} · ${esc(t("home.occurrences", { count: sum(run.occurrencesByStatus) }))}</p>` : "") +
    `<p class="muted">${esc(t("home.tips"))}</p></div>`;
}

async function search() {
  const q = (document.getElementById("q") as HTMLInputElement).value.trim();
  const results = document.getElementById("results")!;
  if (!q) { await home(results); return; }
  const external = (document.getElementById("external") as HTMLInputElement).checked;
  const params = new URLSearchParams({ q, kind: kindSelect.value, external: String(external) });
  const lang = new URLSearchParams(location.search).get("lang");
  if (lang) params.set("lang", lang);
  history.replaceState(null, "", "?" + params);
  try {
    const answer = await api<Answer<SearchHit[]>>("api/entities", { q, kind: kindSelect.value, external, limit: LIMIT });
    const hits = answer.result;
    results.innerHTML = hits.length === 0 ? `<p class="muted">${esc(t("search.empty"))}</p>` :
      `<ol class="hits">${groupHits(hits).map(hitRow).join("")}</ol>` +
      (hits.length === LIMIT ? `<p class="muted">${esc(t("search.limited", { count: LIMIT }))}</p>` : "");
  } catch (e) {
    results.innerHTML = `<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p>`;
  }
}

document.getElementById("search")!.addEventListener("submit", e => { e.preventDefault(); search(); });

(async () => {
  await initI18n();
  for (const kind of KINDS) kindSelect.insertAdjacentHTML("beforeend", `<option value="${esc(kind)}">${esc(kindLabel(kind))}</option>`);
  runSummary = showBanner();
  const initial = new URLSearchParams(location.search);
  if (initial.get("q")) {
    (document.getElementById("q") as HTMLInputElement).value = initial.get("q")!;
    kindSelect.value = initial.get("kind") || "";
    (document.getElementById("external") as HTMLInputElement).checked = initial.get("external") !== "false";
  }
  search();
})();
