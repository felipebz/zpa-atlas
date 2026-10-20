import { errorMessage } from "./api";
import { showBanner } from "./banner";
import { codesOf, loadCodes, NO_CODES } from "./codes";
import { esc, queryHref } from "./html";
import { initI18n, t } from "./i18n";
import { buildForm, readFilters, writeForm } from "./query-form";
import { runGroups } from "./query-groups";
import { runList } from "./query-list";
import { modeAhead, modeBar } from "./query-mode";
import { filtersOf, hasFilter, modeChosen, modeOf, paramsOf } from "./query-params";
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

/** Empties the answer and the URL's query; nothing runs. The bar stays, with the mode of [params]. */
function clearAnswer(params?: QueryParams) {
  state.current = null;
  state.opened = modeOf(params ?? {});
  state.generation++;
  answer.innerHTML = modeAhead(state.opened);
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
  const mode = modeChosen(bar.querySelector<HTMLInputElement>("input[name=view]:checked")!.value,
    [...bar.querySelectorAll<HTMLSelectElement>("select[name=groupBy]")].map(s => s.value));
  if (state.current) {
    run({ ...filtersOf(state.current), ...mode });
    return;
  }
  // No answer: the choice is the mode of the next Run, and the URL follows it; nothing runs.
  state.opened = mode;
  answer.innerHTML = modeAhead(mode);
  history.replaceState(null, "", queryHref({ ...filtersOf(paramsOf(location.search)), ...mode }));
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
  // The mode of the answer shown, else of the URL the page opened with when nothing ran yet.
  run({ ...params, ...modeOf(state.current ?? state.opened) });
});

document.getElementById("reset")!.addEventListener("click", e => {
  e.preventDefault();
  writeForm({});
  clearAnswer({});
});

(async () => {
  const codes = loadCodes();
  codes.catch(() => {});
  await initI18n();
  // Without the code lists the pickers stay empty and the answer says why; the URL's query does not run.
  let failure: string | null = null;
  const loaded = await codes.catch(e => { failure = errorMessage(e); return NO_CODES; });
  state.dimensions = codesOf(loaded.groupDimensions);
  buildForm(loaded);
  showBanner();
  const initial = paramsOf(location.search);
  state.opened = initial;
  writeForm(initial);
  if (failure !== null) {
    // Without dimensions the bar offers the list only, and the next Run is a list.
    state.opened = {};
    answer.innerHTML = modeAhead(initial) + `<div class="card"><p class="muted">${esc(t("error.message", { error: failure }))}</p></div>`;
    return;
  }
  // A URL with filters runs; one without (or with only the mode) never runs the whole index by itself, and shows the
  // mode the next Run uses.
  if (hasFilter(initial)) run(initial);
  else answer.innerHTML = modeAhead(initial);
})();
