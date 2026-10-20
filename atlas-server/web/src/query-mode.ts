import { esc } from "./html";
import { t } from "./i18n";
import type { QueryParams } from "./query-params";
import { state } from "./query-state";

// ---- the mode, above the answer ---------------------------------------------------------------------------------------

export function modeBar(params: QueryParams) {
  const grouped = params.mode === "groups";
  if (grouped) state.lastGroupBy = params.groupBy;
  const chosen = (params.groupBy || state.lastGroupBy).split(",");
  const selects = [0, 1, 2].map(i => `<select name="groupBy" aria-label="${esc(t("query.mode.groups"))}">` +
    (i === 0 ? "" : `<option value="">${esc(t("query.none"))}</option>`) +
    state.dimensions.map(d => `<option value="${d}" ${chosen[i] === d ? "selected" : ""}>${esc(t("query.dimension." + d))}</option>`).join("") +
    `</select>`).join(" ");
  // Without dimensions (the code lists could not be read) there is nothing to group by: the bar offers the list only.
  const groups = state.dimensions.length === 0 ? "" :
    `<label class="check"><input type="radio" name="view" value="groups" ${grouped ? "checked" : ""}> ${esc(t("query.mode.groups"))}</label>
    <span class="dims" ${grouped ? "" : "hidden"}>${selects}</span>`;
  return `<div class="mode">
    <label class="check"><input type="radio" name="view" value="" ${grouped && groups ? "" : "checked"}> ${esc(t("query.mode.list"))}</label>
    ${groups}</div>`;
}

/** The bar while no answer is shown: the mode of [params] that the next Run uses, outside any answer card. */
export function modeAhead(params: QueryParams) {
  return `<div class="mode-ahead">${modeBar(params)}<p class="muted">${esc(t("query.mode.next"))}</p></div>`;
}
