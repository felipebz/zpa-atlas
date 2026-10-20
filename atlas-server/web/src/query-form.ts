import { ENTITY_KINDS, ORIGINS, ROLES, STATEMENT_KINDS, STATUSES, UNIT_KINDS, VALUE_KINDS } from "./codes";
import { kindLabel, roleLabel } from "./entity-html";
import { esc } from "./html";
import { t } from "./i18n";
import { moreSet } from "./query-params";
import type { QueryParams } from "./query-params";
import { field, form, picks, state } from "./query-state";
import { entityOf, pick, setKept, setupSuggest, suggestBox, unpick } from "./query-suggest";

const code = (value: string) => value;
/** The multi-value filters picked from a list: their codes and how a code is shown. */
const PICKS: Record<string, [string[], (code: string) => string]> = {
  entityKinds: [ENTITY_KINDS, kindLabel], unitKinds: [UNIT_KINDS, code], roles: [ROLES, roleLabel],
  statuses: [STATUSES, code], origins: [ORIGINS, code], statementKinds: [STATEMENT_KINDS, code]
};
/** The text and select filters of "more filters". */
const TEXTS = ["schema", "path", "reasons", "value", "external", "valueKind"];
export const labelOf = (filter: string) => PICKS[filter]?.[1] ?? code;

// ---- the form ---------------------------------------------------------------------------------------------------------

export function buildForm() {
  for (const pick of form.querySelectorAll<HTMLElement>("details.pick")) {
    const [codes, label] = PICKS[pick.dataset.name!];
    pick.querySelector(".opts")!.innerHTML = codes.map(c =>
      `<label class="check"><input type="checkbox" value="${esc(c)}"> ${esc(label(c))}</label>`).join("");
  }
  field("valueKind").insertAdjacentHTML("beforeend", VALUE_KINDS.map(k => `<option value="${k}">${esc(k)}</option>`).join(""));
  form.querySelectorAll<HTMLElement>(".suggest").forEach(setupSuggest);
  suggestBox("entity").querySelector(".kept button.x")!.addEventListener("click", () => setKept(null));
  form.addEventListener("change", e => {
    const pick = (e.target as Element).closest<HTMLElement>("details.pick");
    if (pick) pickSummary(pick);
    moreSummary();
  });
  form.addEventListener("input", moreSummary);
}

function pickSummary(pick: HTMLElement) {
  const chosen = [...pick.querySelectorAll<HTMLInputElement>("input:checked")].map(i => i.value);
  const values = chosen.length ? chosen.map(labelOf(pick.dataset.name!)).join(", ") : t("query.any");
  // A boxed picker has its label above it.
  pick.querySelector("summary")!.textContent = pick.classList.contains("boxed") ? values : t("query." + pick.dataset.name) + ": " + values;
}

function moreSummary() {
  const count = moreSet(readFilters()).length;
  document.querySelector("#more > summary")!.textContent = count ? t("query.more.count", { count }) : t("query.more");
}

/**
 * The filters the form sets, empty and default values left out. A suggest field gives its pick's key; typed, it gives
 * the dotted name (`name`, `containerName`), or the key when the text is one (`db:…`).
 */
export function readFilters() {
  const params: QueryParams = {};
  const what = field("what").value.trim();
  if (picks.entity) params.entity = picks.entity.key;
  else if (what.startsWith("db:")) params.entity = what;
  else if (what) params.name = what;
  if (params.entity && field("inside").checked) params.inside = "true";
  if (params.entity && state.keptName) params.name = state.keptName;
  const within = field("within").value.trim();
  if (picks.container) params.container = picks.container.key;
  else if (within.startsWith("db:")) params.container = within;
  else if (within) params.containerName = within;
  for (const name of TEXTS) {
    const value = name === "value" ? field(name).value : field(name).value.trim();
    if (value) params[name] = value;
  }
  for (const pick of form.querySelectorAll<HTMLElement>("details.pick")) {
    const chosen = [...pick.querySelectorAll<HTMLInputElement>("input:checked")].map(i => i.value);
    if (chosen.length) params[pick.dataset.name!] = chosen.join(",");
  }
  if (!field("containerInside").checked) params.containerInside = "false";
  if (!field("candidates").checked) params.candidates = "false";
  return params;
}

export function writeForm(params: QueryParams) {
  for (const param of ["entity", "container"]) {
    const box = suggestBox(param);
    unpick(box, false);
    field(param === "entity" ? "what" : "within").value = (param === "entity" ? (params.entity ? "" : params.name) : params.containerName) ?? "";
    if (params[param]) {
      const key = params[param];
      pick(box, { key, kind: "", display: key, name: key, external: false });
      entityOf(key).then(e => { if (picks[param]?.key === key) pick(box, e); });
    }
  }
  field("inside").checked = params.inside === "true";
  setKept(params.entity && params.name ? params.name : null);
  for (const name of TEXTS) field(name).value = params[name] ?? "";
  for (const pick of form.querySelectorAll<HTMLElement>("details.pick")) {
    const chosen = new Set((params[pick.dataset.name!] ?? "").split(",").filter(Boolean));
    for (const input of pick.querySelectorAll<HTMLInputElement>("input")) input.checked = chosen.has(input.value);
    pickSummary(pick);
  }
  field("containerInside").checked = params.containerInside !== "false";
  field("candidates").checked = params.candidates !== "false";
  (document.getElementById("more") as HTMLDetailsElement).open = moreSet(params).length > 0;
  moreSummary();
}
