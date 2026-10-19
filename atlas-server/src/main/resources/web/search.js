"use strict";

// The entity kinds the index produces: the stored codes, shown through the catalog.
const KINDS = ["TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "COLUMN", "SEQUENCE", "TRIGGER", "PACKAGE", "PROCEDURE", "FUNCTION", "PARAMETER",
  "TYPE", "RECORD_FIELD", "PACKAGE_VARIABLE", "PACKAGE_CONSTANT", "EXCEPTION", "CURSOR", "UNKNOWN_RELATION"];
const LIMIT = 200;

const kindSelect = document.getElementById("kind");
let runSummary = null;

/** The hits in order, a spec and its body as one group whose uses are the sum of both. */
function groupHits(hits) {
  const groups = new Map();
  for (const h of hits) {
    const parent = parentKeyOf(h.key);
    const group = groups.get(parent) ?? groups.set(parent, { spec: null, body: null, usages: 0 }).get(parent);
    if (h.key === parent) group.spec = h;
    else group.body = h;
    group.usages += h.usages;
  }
  return [...groups.values()];
}

/** A hit as two lines: its name, kind and uses, then the program it is in. */
function hitRow(group) {
  const h = group.spec ?? group.body;
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

async function home(results) {
  const run = await runSummary;
  const sum = list => list.reduce((n, c) => n + c.count, 0);
  results.innerHTML = `<div class="card">` +
    (run ? `<p>${esc(t("home.files", { count: sum(run.filesByParseStatus) }))} · ${esc(t("home.occurrences", { count: sum(run.occurrencesByStatus) }))}</p>` : "") +
    `<p class="muted">${esc(t("home.tips"))}</p></div>`;
}

async function search() {
  const q = document.getElementById("q").value.trim();
  const results = document.getElementById("results");
  if (!q) { await home(results); return; }
  const external = document.getElementById("external").checked;
  const params = new URLSearchParams({ q, kind: kindSelect.value, external });
  const lang = new URLSearchParams(location.search).get("lang");
  if (lang) params.set("lang", lang);
  history.replaceState(null, "", "?" + params);
  try {
    const answer = await api("api/entities", { q, kind: kindSelect.value, external, limit: LIMIT });
    const hits = answer.result;
    results.innerHTML = hits.length === 0 ? `<p class="muted">${esc(t("search.empty"))}</p>` :
      `<ol class="hits">${groupHits(hits).map(hitRow).join("")}</ol>` +
      (hits.length === LIMIT ? `<p class="muted">${esc(t("search.limited", { count: LIMIT }))}</p>` : "");
  } catch (e) {
    results.innerHTML = `<p class="muted">${esc(t("error.message", { error: e.message }))}</p>`;
  }
}

document.getElementById("search").addEventListener("submit", e => { e.preventDefault(); search(); });

(async () => {
  await initI18n();
  for (const kind of KINDS) kindSelect.insertAdjacentHTML("beforeend", `<option value="${esc(kind)}">${esc(kindLabel(kind))}</option>`);
  runSummary = showBanner();
  const initial = new URLSearchParams(location.search);
  if (initial.get("q")) {
    document.getElementById("q").value = initial.get("q");
    kindSelect.value = initial.get("kind") || "";
    document.getElementById("external").checked = initial.get("external") !== "false";
  }
  search();
})();
