import { errorMessage } from "./api";
import { showBanner } from "./banner";
import { esc, queryHref } from "./html";
import { initI18n, t } from "./i18n";
import { buildForm, readFilters, writeForm } from "./query-form";
import { runGroups } from "./query-groups";
import { runList } from "./query-list";
import { modeBar } from "./query-mode";
import { filtersOf, hasFilter } from "./query-params";
import type { QueryParams } from "./query-params";
import { answer, form, state } from "./query-state";
import { setupSourceToggle } from "./uses";

setupSourceToggle();

// ---- running ------------------------------------------------------------------------------------------------------------

/** Runs the query of [params]; the page URL becomes the query. The form stays as it is, the record of the query. */
function run(params: QueryParams) {
  state.current = params;
  history.replaceState(null, "", queryHref(params));
  const ran = ++state.generation;
  answer.innerHTML = `<div class="card">${modeBar(params)}<p class="muted">${esc(t("banner.loading"))}</p></div>`;
  const done = params.mode === "groups" ? runGroups(params, ran) : runList(params, ran);
  done.catch(e => {
    if (ran === state.generation) answer.innerHTML = `<div class="card">${modeBar(params)}<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p></div>`;
  });
}

/** Empties the answer and the URL's query; nothing runs. */
function clearAnswer(params?: QueryParams) {
  state.current = null;
  state.generation++;
  answer.innerHTML = "";
  history.replaceState(null, "", queryHref(params ?? {}));
}

/**
 * Writes [params] into the form and runs it, for a change made on the answer (a facet). A change that leaves no
 * filter does not run the whole index: only Run does that, when the user asks for it.
 */
function rerun(params: QueryParams) {
  writeForm(params);
  if (hasFilter(params)) run(params);
  else clearAnswer(params);
}

answer.addEventListener("change", e => {
  if ((e.target as HTMLInputElement).name !== "view" && (e.target as HTMLInputElement).name !== "groupBy") return;
  const bar = (e.target as Element).closest(".mode")!;
  const params = filtersOf(state.current!);
  if (bar.querySelector<HTMLInputElement>("input[name=view]:checked")!.value === "groups") {
    params.mode = "groups";
    params.groupBy = [...new Set([...bar.querySelectorAll<HTMLSelectElement>("select[name=groupBy]")].map(s => s.value).filter(Boolean))].join(",");
  }
  run(params);
});

// A facet chip adds its value to the filter, or takes it out.
answer.addEventListener("click", e => {
  const chip = (e.target as Element).closest<HTMLElement>(".chip");
  const facet = chip?.closest("[id^=facet-]");
  if (!chip || !facet || !chip.dataset.value) return;
  const filter = facet.id.slice("facet-".length);
  const params = { ...state.current };
  const chosen = new Set((params[filter] ?? "").split(",").filter(Boolean));
  if (chosen.has(chip.dataset.value)) chosen.delete(chip.dataset.value);
  else chosen.add(chip.dataset.value);
  if (chosen.size) params[filter] = [...chosen].join(",");
  else delete params[filter];
  rerun(params);
});

// ---- the page ----------------------------------------------------------------------------------------------------------------

form.addEventListener("submit", e => {
  e.preventDefault();
  // Run is the user's choice: an empty form runs the whole set.
  const params = readFilters();
  if (state.current?.mode) {
    params.mode = state.current.mode;
    params.groupBy = state.current.groupBy;
  }
  run(params);
});

document.getElementById("reset")!.addEventListener("click", e => {
  e.preventDefault();
  writeForm({});
  clearAnswer({});
});

(async () => {
  await initI18n();
  buildForm();
  showBanner();
  const initial = Object.fromEntries(new URLSearchParams(location.search));
  delete initial.lang;
  if (initial.mode === "groups" && !initial.groupBy) initial.groupBy = "ENTITY";
  writeForm(initial);
  // A URL with filters runs; one without (or with only the mode) never runs the whole index by itself.
  if (hasFilter(initial)) run(initial);
})();
