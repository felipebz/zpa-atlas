import type {Answer, Count, RunSummary, SearchHit} from "./api";
import {api, errorMessage} from "./api";
import {showBanner} from "./banner";
import {loadCodes, searchKinds} from "./codes";
import type {NameOptions} from "./entity-html";
import {entityLink, kindBadge, kindLabel, listNameOptions, specBodyLinks} from "./entity-html";
import {esc, getOrPut, qualifierHtml} from "./html";
import {initI18n, t} from "./i18n";
import {parentKeyOf, shownName} from "./names";

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
function hitRow(group: HitGroup, options?: NameOptions) {
  const h = (group.spec ?? group.body)!;
  const shown = shownName(h.display, h.name, options);
  const container = shown.qualifier.replace(/\.$/, "");
  const containerBody = shown.parts.qualifierBody ? ` <span class="badge">${esc(t("entity.overload.body"))}</span>` : "";
  return `<li>
    <div class="hit-head">
      <span class="hit-name">${entityLink(h, { ...options, relative: true, noBadge: true })}${specBodyLinks(group.spec, group.body)}</span>
      ${kindBadge(h.kind)}
      <span class="hit-uses">${esc(t("search.usages", { count: group.usages }))}</span>
    </div>
    ${container ? `<div class="hit-in muted">${qualifierHtml(container)}${containerBody}</div>` : ""}
  </li>`;
}

/** The rows of [hits]; two rows whose name and container would read the same show every parameter name. */
function hitRows(hits: SearchHit[]) {
  const groups = groupHits(hits);
  const options = listNameOptions(groups.map(g => (g.spec ?? g.body)!));
  return groups.map(g => hitRow(g, options((g.spec ?? g.body)!))).join("");
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
      `<ol class="hits">${hitRows(hits)}</ol>` +
      (hits.length === LIMIT ? `<p class="muted">${esc(t("search.limited", { count: LIMIT }))}</p>` : "");
  } catch (e) {
    results.innerHTML = `<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p>`;
  }
}

document.getElementById("search")!.addEventListener("submit", e => { e.preventDefault(); search(); });

(async () => {
  const codes = loadCodes();
  codes.catch(() => {});
  await initI18n();
  // Without the code lists the kind select offers "all kinds" only, and the results say why.
  let failure: string | null = null;
  const kinds = await codes.then(searchKinds, e => { failure = errorMessage(e); return []; });
  for (const kind of kinds) kindSelect.insertAdjacentHTML("beforeend", `<option value="${esc(kind)}">${esc(kindLabel(kind))}</option>`);
  runSummary = showBanner();
  const initial = new URLSearchParams(location.search);
  if (initial.get("q")) {
    (document.getElementById("q") as HTMLInputElement).value = initial.get("q")!;
    kindSelect.value = initial.get("kind") || "";
    (document.getElementById("external") as HTMLInputElement).checked = initial.get("external") !== "false";
  }
  await search();
  if (failure !== null) {
    document.getElementById("results")!.insertAdjacentHTML("afterbegin", `<p class="muted">${esc(t("error.message", { error: failure }))}</p>`);
  }
})();
