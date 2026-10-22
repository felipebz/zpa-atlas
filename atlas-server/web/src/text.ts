import { api, errorMessage } from "./api";
import type { Answer, TextHit, TextPage } from "./api";
import { showBanner } from "./banner";
import { entityLink } from "./entity-html";
import { esc, langSuffix } from "./html";
import { initI18n, t } from "./i18n";
import { entityOf, pick, setupSuggest, suggestBox, unpick } from "./query-suggest";
import { field, form, picks } from "./query-state";
import { longEnough, matchesIn, MIN_TEXT, textHref, textParamsOf } from "./text-search";
import type { TextParams } from "./text-search";

const PAGE = 100;
const answer = document.getElementById("answer")!;

/** Only the latest search fills the answer. */
let generation = 0;

/** The search the form sets: a picked container gives its key, a typed one its dotted name (or the key, typed as one). */
function readForm(): TextParams {
  const params: TextParams = {};
  const text = field("text").value;
  if (text.trim()) params.text = text;
  const path = field("path").value.trim();
  if (path) params.path = path;
  const within = field("within").value.trim();
  if (picks.container) params.container = picks.container.key;
  else if (within.startsWith("db:")) params.container = within;
  else if (within) params.containerName = within;
  return params;
}

function writeForm(params: TextParams) {
  field("text").value = params.text ?? "";
  field("path").value = params.path ?? "";
  const box = suggestBox("container");
  unpick(box, false);
  field("within").value = params.containerName ?? "";
  const key = params.container;
  if (key) {
    pick(box, { key, kind: "", display: key, name: key, external: false });
    entityOf(key).then(e => { if (picks.container?.key === key) pick(box, e); });
  }
}

function message(text: string) {
  answer.innerHTML = `<div class="card"><p class="muted">${esc(text)}</p></div>`;
}

/** [line] as text nodes, each place [text] matches inside a `<mark>`; unmarked when the text cannot be placed. */
function lineNodes(line: string, text: string): HTMLElement {
  const code = document.createElement("code");
  const ranges = matchesIn(line, text);
  let at = 0;
  for (const [start, end] of ranges ?? []) {
    code.append(document.createTextNode(line.slice(at, start)));
    const mark = document.createElement("mark");
    mark.textContent = line.slice(start, end);
    code.append(mark);
    at = end;
  }
  code.append(document.createTextNode(line.slice(at)));
  return code;
}

function rowOf(hit: TextHit, text: string): HTMLElement {
  const row = document.createElement("li");
  row.className = "text-hit";
  const where = document.createElement("div");
  where.className = "where";
  const link = document.createElement("a");
  link.className = "mono path";
  link.href = `file.html?path=${encodeURIComponent(hit.path)}&line=${hit.line}${langSuffix()}#L${hit.line}`;
  link.textContent = `${hit.path}:${hit.line}`;
  where.append(link);
  if (hit.entity) {
    const entity = document.createElement("span");
    entity.className = "in";
    entity.innerHTML = entityLink(hit.entity);
    where.append(entity);
  }
  if (hit.stale) {
    const stale = document.createElement("span");
    stale.className = "badge warn-badge";
    stale.textContent = t("text.stale");
    where.append(stale);
  }
  const pre = document.createElement("pre");
  pre.className = "text-line";
  pre.append(lineNodes(hit.text, text));
  row.append(where, pre);
  return row;
}

/** Fetches a page of [params] after [after] and shows it, below the rows already there when [after] is set. */
async function load(params: TextParams, after: number | null, ran: number) {
  let page: TextPage;
  try {
    page = (await api<Answer<TextPage>>("api/text", { ...params, after, limit: PAGE })).result;
  } catch (e) {
    if (ran === generation) {
      // A fetch that never reached the server fails with a TypeError, whose text the browser chooses.
      const text = e instanceof TypeError ? t("text.unreachable") : t("error.message", { error: errorMessage(e) });
      if (after === null) message(text);
      else answer.querySelector(".more-rows")?.replaceWith(Object.assign(document.createElement("p"), { className: "muted", textContent: text }));
    }
    return;
  }
  if (ran !== generation) return;
  if (after === null) {
    answer.innerHTML = `<div class="card"><p class="total"></p><ul class="text-hits"></ul></div>`;
    answer.querySelector(".total")!.textContent = page.total === 0 ? t("text.none")
      : t("text.total", { count: page.total }) + (page.wholeIndex ? " " + t("text.whole") : "");
  }
  const list = answer.querySelector(".text-hits")!;
  for (const hit of page.rows) list.append(rowOf(hit, params.text!));
  answer.querySelector(".more-rows")?.remove();
  if (page.nextAfter !== null) {
    const more = document.createElement("button");
    more.type = "button";
    more.className = "more-rows";
    more.textContent = t("text.more", { shown: list.children.length, total: page.total });
    more.addEventListener("click", () => {
      more.disabled = true;
      load(params, page.nextAfter, ran);
    });
    list.after(more);
  }
}

/** Searches [params]: the URL becomes the search; a text too short is refused here, without a request. */
function run(params: TextParams, push: boolean) {
  const href = textHref(params, new URLSearchParams(location.search).get("lang"));
  if (push && location.pathname.endsWith("text.html") && location.search !== href.slice("text.html".length)) history.pushState(null, "", href);
  else history.replaceState(null, "", href);
  const ran = ++generation;
  if (!params.text) {
    answer.innerHTML = "";
    return;
  }
  if (!longEnough(params.text)) {
    message(t("text.short", { min: MIN_TEXT }));
    return;
  }
  message(t("banner.loading"));
  load(params, null, ran);
}

form.addEventListener("submit", e => {
  e.preventDefault();
  run(readForm(), true);
});

window.addEventListener("popstate", () => {
  const params = textParamsOf(location.search);
  writeForm(params);
  run(params, false);
});

(async () => {
  await initI18n();
  form.querySelectorAll<HTMLElement>(".suggest").forEach(setupSuggest);
  showBanner();
  const initial = textParamsOf(location.search);
  writeForm(initial);
  run(initial, false);
})();
