import type {Answer, EntitySummary, TextHit, TextPage} from "./api";
import {api, CODE_COLORS, errorMessage} from "./api";
import {showBanner} from "./banner";
import {nameHtml} from "./entity-html";
import {entityHref, esc, langSuffix, lineLink} from "./html";
import {formatNumber, initI18n, t} from "./i18n";
import {fileTitleParts} from "./project";
import {entityOf, pick, setupSuggest, suggestBox, unpick} from "./query-suggest";
import {field, form, picks} from "./query-state";
import type {TextParams} from "./text-search";
import {longEnough, matchesIn, MIN_TEXT, placeRows, shownLine, textHref, textParamsOf} from "./text-search";
import {showExamples} from "./text-try";
import {tabWidthControl} from "./tab-width-control";

const PAGE = 100;
const answer = document.getElementById("answer")!;
/** The tab width control, made once (after the texts load): every search puts it beside its total. */
let tabControl: HTMLElement | null = null;

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

/** [hit]'s line as shown: without its leading whitespace, colored, each place the text matches inside a `<mark>`. */
function lineNodes(hit: TextHit, text: string): HTMLElement {
  const code = document.createElement("code");
  // One inline box around the text: its left padding, repeated on every row it wraps to, indents the continuation rows.
  const flow = code.appendChild(Object.assign(document.createElement("span"), { className: "flow" }));
  const shown = shownLine(hit.text, matchesIn(hit.text, text), hit.colors);
  let mark: HTMLElement | null = null;
  for (const piece of shown.pieces) {
    const part = piece.color === null ? document.createTextNode(shown.text.slice(piece.from, piece.to))
      : Object.assign(document.createElement("span"), { className: "tok-" + CODE_COLORS[piece.color], textContent: shown.text.slice(piece.from, piece.to) });
    // Consecutive marked pieces share one mark, whatever their colors.
    if (piece.mark) {
      if (!mark) flow.append(mark = document.createElement("mark"));
      mark.append(part);
    } else {
      mark = null;
      flow.append(part);
    }
  }
  return code;
}

/** A path as HTML that may break only after a slash or an underscore. */
const breakable = (text: string) => esc(text).replace(/([/_])/g, "$1<wbr>");

/** A file's group: its header (the name, the folder muted, its count of lines, the stale badge) and its rows. */
function groupOf(hit: TextHit, lines: number | undefined): HTMLElement {
  const group = document.createElement("section");
  group.className = "tgrp";
  const { name, folder } = fileTitleParts(hit.path);
  const stale = hit.stale ? ` <span class="badge warn-badge">${esc(t("text.stale"))}</span>` : "";
  const count = lines === undefined ? "" : `<span class="grp-n muted" title="${esc(t("text.fileLines", { count: lines }))}">${esc(formatNumber(lines))}</span>`;
  group.innerHTML = `<div class="tgrp-h"><a href="file.html?path=${encodeURIComponent(hit.path)}${langSuffix()}">` +
    (folder ? `<span class="folder">${breakable(folder + "/")}</span>` : "") + `<span class="name">${breakable(name)}</span></a>${stale}${count}</div>`;
  return group;
}

/** The container a run of rows is in: its short name, without the schema, linking to its page. */
function containerOf(entity: EntitySummary): HTMLElement {
  const row = document.createElement("div");
  row.className = "tin";
  const dot = entity.display.indexOf(".");
  const within = dot > 0 ? { display: entity.display.slice(0, dot) } : undefined;
  row.innerHTML = `<a href="${entityHref(entity.key)}">${nameHtml(entity, { within, noBodyBadge: true })}</a>`;
  return row;
}

function rowOf(hit: TextHit, text: string): HTMLElement {
  const row = document.createElement("div");
  row.className = "tline";
  row.innerHTML = lineLink(hit.path, hit.line, t("entity.line", { line: hit.line }));
  row.append(lineNodes(hit, text));
  return row;
}

/** The groups of the search shown, by path, and each file's last container (see [placeRows]). */
let groups = new Map<string, HTMLElement>();
let containers = new Map<string, string | null>();

/** The total: lines and files, and, for a search of the whole index, that a container or a path narrows it. */
function totalText(page: TextPage) {
  if (page.total === 0) return t("text.none");
  const total = t("text.total", { count: page.total, files: t("text.files", { count: page.files }) }) + ".";
  return page.wholeIndex ? total + " " + t("text.whole") : total;
}

/** Fetches a page of [params] after [after] and shows it, below the rows already there when [after] is set. */
async function load(params: TextParams, after: number | null, ran: number) {
  let page: TextPage;
  try {
    page = (await api<Answer<TextPage>>("api/text", { ...params, after, limit: PAGE, colors: true })).result;
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
    answer.innerHTML = `<div class="card"><div class="total-bar"><p class="total"></p></div><div class="text-groups"></div></div>`;
    answer.querySelector(".total")!.textContent = totalText(page);
    if (page.total > 0) answer.querySelector(".total-bar")!.appendChild(tabControl ??= tabWidthControl());
    groups = new Map();
    containers = new Map();
  }
  const list = answer.querySelector(".text-groups")!;
  const places = placeRows(page.rows, containers);
  page.rows.forEach((hit, i) => {
    let group = groups.get(hit.path);
    if (places[i].newGroup || !group) {
      group = groupOf(hit, page.fileLines[hit.path]);
      groups.set(hit.path, group);
      list.append(group);
    }
    const container = places[i].container;
    if (container) group.append(containerOf(container));
    group.append(rowOf(hit, params.text!));
  });
  answer.querySelector(".more-rows")?.remove();
  if (page.nextAfter !== null) {
    const more = document.createElement("button");
    more.type = "button";
    more.className = "more-rows";
    more.textContent = t("text.more", { shown: list.querySelectorAll(".tline").length, total: page.total });
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
  const tryList = document.getElementById("try")!;
  if (!params.text) {
    answer.innerHTML = "";
    // No search: the examples, while no other search starts.
    showExamples(tryList, () => ran === generation);
    return;
  }
  tryList.hidden = true;
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
