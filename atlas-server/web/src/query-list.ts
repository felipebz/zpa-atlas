import type {Answer, Count, Facets, OccurrencePage, QuerySummary} from "./api";
import {api, errorMessage} from "./api";
import {esc} from "./html";
import {formatNumber, t} from "./i18n";
import {labelOf} from "./query-form";
import {modeBar} from "./query-mode";
import type {QueryParams} from "./query-params";
import {filtersOf} from "./query-params";
import {answer, state} from "./query-state";
import {renderChips, usesBlock} from "./uses";

/** The facets of a list answer and the filter each one sets. */
const FACETS: [Exclude<keyof Facets, "bySide">, string][] = [["byRole", "roles"], ["byStatus", "statuses"], ["byReason", "reasons"], ["byOrigin", "origins"], ["byEntityKind", "entityKinds"],
  ["byUnitKind", "unitKinds"]];
const PAGE = 100;
const FACET_SHOWN = 8;

// ---- list mode ------------------------------------------------------------------------------------------------------------

export async function runList(params: QueryParams, ran: number) {
  const filters = filtersOf(params);
  // The rows come first; the total and the facets, which count the whole set, follow in a request of their own.
  const summary = api<Answer<QuerySummary>>("api/query/summary", filters);
  summary.catch(() => {});
  const page = (await api<Answer<OccurrencePage>>("api/query", { ...filters, summary: false, limit: PAGE })).result;
  if (ran !== state.generation) return;
  answer.innerHTML = `<div class="card">${modeBar(params)}<div id="facets"></div><p id="total" class="muted">${esc(t("banner.loading"))}</p>
    <div id="rows"></div><button id="more-rows" hidden>${esc(t("usage.more"))}</button></div>`;
  const block = usesBlock({ listing: true });
  document.getElementById("rows")!.append(block.element);
  let after = page.nextAfter;
  block.append(page.rows);
  const more = document.getElementById("more-rows") as HTMLButtonElement;
  more.hidden = after === null;
  more.addEventListener("click", async () => {
    more.disabled = true;
    try {
      const next = (await api<Answer<OccurrencePage>>("api/query", { ...filters, summary: false, after, limit: PAGE })).result;
      block.append(next.rows);
      after = next.nextAfter;
      more.hidden = after === null;
    } catch (e) {
      more.insertAdjacentHTML("afterend", `<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p>`);
    } finally {
      more.disabled = false;
    }
  });
  summary.then(s => {
    if (ran !== state.generation) return;
    const { total, facets } = s.result;
    document.getElementById("total")!.textContent = t("query.total", { count: total });
    document.getElementById("facets")!.innerHTML = FACETS.map(([, filter]) =>
      `<div class="facet"><span class="muted">${esc(t("query." + filter))}:</span> <span id="facet-${filter}"></span></div>`).join("");
    for (const [facet, filter] of FACETS) {
      const selected = new Set((params[filter] ?? "").split(",").filter(Boolean));
      renderFacet(filter, facets[facet], selected, false);
      const target = document.getElementById("facet-" + filter)!;
      if (!target.innerHTML) target.parentElement!.remove();
    }
  }, e => {
    if (ran === state.generation) document.getElementById("total")!.textContent = t("error.message", { error: errorMessage(e) });
  });
}

/**
 * A facet row: its values by count, descending; past [FACET_SHOWN], a "+k" chip that shows the rest ([all]). The
 * selected values are always shown.
 */
function renderFacet(filter: string, counts: Count[], selected: Set<string>, all: boolean) {
  const sorted = [...counts].sort((a, b) => b.count - a.count || (a.value < b.value ? -1 : 1));
  const shown = all ? sorted : sorted.filter((c, i) => i < FACET_SHOWN || selected.has(c.value));
  renderChips("facet-" + filter, shown, selected, "value", labelOf(filter));
  const hidden = sorted.length - shown.length;
  if (hidden > 0) {
    const target = document.getElementById("facet-" + filter)!;
    target.insertAdjacentHTML("beforeend", `<button type="button" class="chip more" title="${esc(t("query.facet.more"))}">+${esc(formatNumber(hidden))}</button>`);
    target.querySelector("button.more")!.addEventListener("click", () => renderFacet(filter, counts, selected, true));
  }
}
