import type {Codes} from "./api";
import {codesOf, offeredCodes, pickerCodes} from "./codes";
import {
    kindLabel,
    originLabel,
    reasonLabel,
    roleLabel,
    statementLabel,
    statusLabel,
    unitKindLabel,
    valueKindLabel
} from "./entity-html";
import {esc} from "./html";
import {t} from "./i18n";
import type {QueryParams} from "./query-params";
import {moreSet} from "./query-params";
import {field, form, picks, state} from "./query-state";
import {entityOf, pick, setKept, setupSuggest, suggestBox, unpick} from "./query-suggest";

const code = (value: string) => value;
/** The multi-value filters picked from a list: their code list and how a code is shown. */
const PICKS: Record<string, [keyof Codes, (code: string) => string]> = {
  entityKinds: ["entityKinds", kindLabel], unitKinds: ["unitKinds", unitKindLabel], roles: ["roles", roleLabel],
  statuses: ["statuses", statusLabel], origins: ["origins", originLabel], statementKinds: ["statementKinds", statementLabel]
};
/** The text and select filters of "more filters". */
const TEXTS = ["schema", "path", "reasons", "value", "external", "valueKind"];
/** How a filter's codes are shown: a picker's, and the reasons typed or picked from a facet. */
export const labelOf = (filter: string) => filter === "reasons" ? reasonLabel : PICKS[filter]?.[1] ?? code;

// ---- the form ---------------------------------------------------------------------------------------------------------

/** One option of a picker; [extra] marks a code the picker shows only because the query has it. */
function optionHtml(code: string, label: (code: string) => string, extra: boolean) {
  return `<label class="check"${extra ? " data-extra" : ""} title="${esc(code)}"><input type="checkbox" value="${esc(code)}"> ${esc(label(code))}</label>`;
}

/** Fills the pickers with the codes the index produces and sets up the form's fields. */
export function buildForm(codes: Codes) {
  for (const pick of form.querySelectorAll<HTMLElement>("details.pick")) {
    const [list, label] = PICKS[pick.dataset.name!];
    pick.querySelector(".opts")!.innerHTML = offeredCodes(codes[list]).map(c => optionHtml(c, label, false)).join("");
  }
  field("valueKind").insertAdjacentHTML("beforeend", codesOf(codes.valueKinds).map(k => `<option value="${esc(k)}" title="${esc(k)}">${esc(valueKindLabel(k))}</option>`).join(""));
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
    // A code the picker does not offer (reserved, or unknown) is added after the others, so the form keeps it.
    const opts = pick.querySelector(".opts")!;
    opts.querySelectorAll("label[data-extra]").forEach(extra => extra.remove());
    const offered = [...opts.querySelectorAll<HTMLInputElement>("input")].map(i => i.value);
    for (const code of pickerCodes(offered, [...chosen]).slice(offered.length)) opts.insertAdjacentHTML("beforeend", optionHtml(code, labelOf(pick.dataset.name!), true));
    for (const input of pick.querySelectorAll<HTMLInputElement>("input")) input.checked = chosen.has(input.value);
    pickSummary(pick);
  }
  field("containerInside").checked = params.containerInside !== "false";
  field("candidates").checked = params.candidates !== "false";
  (document.getElementById("more") as HTMLDetailsElement).open = moreSet(params).length > 0;
  moreSummary();
}
