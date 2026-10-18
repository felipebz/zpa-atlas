"use strict";

// The entity kinds the index produces: the stored codes, shown through the catalog.
const KINDS = ["TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "COLUMN", "SEQUENCE", "TRIGGER", "PACKAGE", "PROCEDURE", "FUNCTION", "PARAMETER",
  "TYPE", "RECORD_FIELD", "PACKAGE_VARIABLE", "PACKAGE_CONSTANT", "EXCEPTION", "CURSOR", "UNKNOWN_RELATION"];

const kindSelect = document.getElementById("kind");

async function search() {
  const q = document.getElementById("q").value.trim();
  const results = document.getElementById("results");
  if (!q) { results.innerHTML = ""; return; }
  const external = document.getElementById("external").checked;
  const params = new URLSearchParams({ q, kind: kindSelect.value, external });
  const lang = new URLSearchParams(location.search).get("lang");
  if (lang) params.set("lang", lang);
  history.replaceState(null, "", "?" + params);
  try {
    const answer = await api("api/entities", { q, kind: kindSelect.value, external, limit: 200 });
    const hits = answer.result;
    results.innerHTML = hits.length === 0 ? `<p class="muted">${esc(t("search.empty"))}</p>` :
      `<table><tr><th>${esc(t("search.column.entity"))}</th><th>${esc(t("search.column.kind"))}</th><th>${esc(t("search.column.usages"))}</th></tr>${hits.map(h =>
        `<tr><td>${entityLink(h)}</td><td>${esc(kindLabel(h.kind))}</td><td>${esc(formatNumber(h.usages))}</td></tr>`).join("")}</table>`;
  } catch (e) {
    results.innerHTML = `<p class="muted">${esc(t("error.message", { error: e.message }))}</p>`;
  }
}

document.getElementById("search").addEventListener("submit", e => { e.preventDefault(); search(); });

(async () => {
  await initI18n();
  for (const kind of KINDS) kindSelect.insertAdjacentHTML("beforeend", `<option value="${esc(kind)}">${esc(kindLabel(kind))}</option>`);
  showBanner();
  const initial = new URLSearchParams(location.search);
  if (initial.get("q")) {
    document.getElementById("q").value = initial.get("q");
    kindSelect.value = initial.get("kind") || "";
    document.getElementById("external").checked = initial.get("external") !== "false";
    search();
  }
})();
