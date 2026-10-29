import type {Answer, PackageUses, SearchHit} from "./api";
import {api, errorMessage} from "./api";
import {showBanner} from "./banner";
import {loadCodes, searchKinds} from "./codes";
import type {NameOptions} from "./entity-html";
import {entityLink, kindBadge, kindLabel, listNameOptions, specBodyLinks} from "./entity-html";
import {esc, langSuffix, qualifierHtml, queryHref} from "./html";
import {initI18n, t} from "./i18n";
import type {HitGroup} from "./names";
import {groupHits, shownName} from "./names";
import {currentProject} from "./project";
import {browserStorage, openedLast} from "./recent";

const LIMIT = 200;

const kindSelect = document.getElementById("kind") as HTMLSelectElement;

/** A hit as two lines: its name, kind and uses, then the program it is in. */
function hitRow(group: HitGroup<SearchHit>, options?: NameOptions) {
  const h = (group.spec ?? group.body)!;
  const shown = shownName(h.display, h.name, options);
  const container = shown.qualifier.replace(/\.$/, "");
  const containerBody = shown.parts.qualifierBody ? ` <span class="badge">${esc(t("entity.overload.body"))}</span>` : "";
  return `<li>
    <div class="hit-head">
      <span class="hit-name">${entityLink(h, { ...options, relative: true, noBadge: true })}${specBodyLinks(group.spec, group.body)}</span>
      ${kindBadge(h.kind)}
      <span class="hit-uses">${esc(t("search.usages", { count: group.usages }))}</span>
    </div>
    ${container ? `<div class="hit-in muted">${qualifierHtml(container)}${containerBody}</div>` : ""}
  </li>`;
}

/** The rows of [hits]; two rows whose name and container would read the same show more of their parameters. */
function hitRows(hits: SearchHit[]) {
  const groups = groupHits(hits);
  const options = listNameOptions(groups.map(g => (g.spec ?? g.body)!));
  return groups.map(g => hitRow(g, options((g.spec ?? g.body)!))).join("");
}

/**
 * The home panels: the objects this viewer opened last (left out when there are none), the project's most used
 * packages, and one line per page with an example taken from the index, so that following it gives results.
 */
async function home(panels: HTMLElement) {
  const recent = openedLast(browserStorage(), currentProject());
  let packages: PackageUses[] = [];
  let failure: string | null = null;
  try {
    packages = (await api<Answer<PackageUses[]>>("api/top-packages")).result;
  } catch (e) {
    failure = errorMessage(e);
  }
  const recentHtml = recent.length === 0 ? "" : (() => {
    const entities = recent.map(o => ({ ...o, external: false }));
    const options = listNameOptions(entities);
    return `<section class="card home-panel"><h2>${esc(t("home.recent"))}</h2><ul class="plain">${entities.map(e =>
      `<li>${kindBadge(e.kind, true)}<span class="grow">${entityLink(e, options(e))}</span></li>`).join("")}</ul></section>`;
  })();
  const packagesHtml = `<section class="card home-panel"><h2>${esc(t("home.packages"))}</h2>` + (failure !== null
    ? `<p class="muted">${esc(t("error.message", { error: failure }))}</p>`
    : packages.length === 0 ? `<p class="muted">${esc(t("search.empty"))}</p>`
    : `<ul class="plain">${packages.map(p =>
      `<li><span class="grow">${entityLink(p.entity)}</span><span class="muted num">${esc(t("search.usages", { count: p.uses }))}</span></li>`).join("")}</ul>`) +
    `</section>`;
  panels.innerHTML = recentHtml + packagesHtml + pagesHtml(packages.find(p => p.topMember !== null) ?? null);
  panels.hidden = false;
}

/** One line per page, with an example from [top], the most used package that has a used member: none without one. */
function pagesHtml(top: PackageUses | null) {
  const member = top?.topMember ?? null;
  const example = (href: string, text: string) =>
    `<span class="ex">${esc(t("home.example"))} <a class="mono" href="${esc(href)}">${esc(text)}</a></span>`;
  const dotted = member ? `${top!.entity.name}.${member.name}`.toLowerCase() : null;
  const objects = dotted ? example(`./?q=${encodeURIComponent(dotted)}${langSuffix()}`, dotted) : "";
  const uses = member ? example(queryHref({ entity: member.key }), member.display.replace(/\(.*$/s, "")) : "";
  const code = member ? example(`text.html?text=${encodeURIComponent(member.name.toLowerCase())}${langSuffix()}`, member.name.toLowerCase()) : "";
  const page = (name: string, href: string, what: string, ex: string) =>
    `<div><a href="${esc(href)}"><b>${esc(t(name))}</b></a><span class="muted">${esc(t(what))}</span>${ex}</div>`;
  return `<section class="card home-panel ways"><h2>${esc(t("home.pages"))}</h2>` +
    page("header.objects", "./" + langSuffix().replace(/^&/, "?"), "home.objects", objects) +
    page("header.uses", queryHref({}), "query.intro", uses) +
    page("header.code", "text.html" + langSuffix().replace(/^&/, "?"), "text.intro", code) +
    `</section>`;
}

async function search() {
  const q = (document.getElementById("q") as HTMLInputElement).value.trim();
  const results = document.getElementById("results")!;
  const panels = document.getElementById("home")!;
  const lang = new URLSearchParams(location.search).get("lang");
  if (!q) {
    history.replaceState(null, "", lang ? "?lang=" + encodeURIComponent(lang) : location.pathname);
    results.innerHTML = "";
    await home(panels);
    return;
  }
  panels.hidden = true;
  const external = (document.getElementById("external") as HTMLInputElement).checked;
  const params = new URLSearchParams({ q, kind: kindSelect.value, external: String(external) });
  if (lang) params.set("lang", lang);
  history.replaceState(null, "", "?" + params);
  try {
    const answer = await api<Answer<SearchHit[]>>("api/entities", { q, kind: kindSelect.value, external, limit: LIMIT });
    const hits = answer.result;
    results.innerHTML = hits.length === 0 ? `<p class="muted">${esc(t("search.empty"))}</p>` :
      `<ol class="hits">${hitRows(hits)}</ol>` +
      (hits.length === LIMIT ? `<p class="muted">${esc(t("search.limited", { count: LIMIT }))}</p>` : "");
  } catch (e) {
    results.innerHTML = `<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p>`;
  }
}

document.getElementById("search")!.addEventListener("submit", e => { e.preventDefault(); search(); });

(async () => {
  const codes = loadCodes();
  codes.catch(() => {});
  await initI18n();
  // Without the code lists the kind select offers "all kinds" only, and the results say why.
  let failure: string | null = null;
  const kinds = await codes.then(searchKinds, e => { failure = errorMessage(e); return []; });
  for (const kind of kinds) kindSelect.insertAdjacentHTML("beforeend", `<option value="${esc(kind)}">${esc(kindLabel(kind))}</option>`);
  showBanner();
  const initial = new URLSearchParams(location.search);
  if (initial.get("q")) {
    (document.getElementById("q") as HTMLInputElement).value = initial.get("q")!;
    kindSelect.value = initial.get("kind") || "";
    (document.getElementById("external") as HTMLInputElement).checked = initial.get("external") !== "false";
  }
  await search();
  if (failure !== null) {
    document.getElementById("results")!.insertAdjacentHTML("afterbegin", `<p class="muted">${esc(t("error.message", { error: failure }))}</p>`);
  }
})();
