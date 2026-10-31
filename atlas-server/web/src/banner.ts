import type {Answer, RunAnswer, RunStamp, RunSummary, SourceSnippet} from "./api";
import {api, errorMessage} from "./api";
import {parseLabel} from "./entity-html";
import {setupGoto} from "./goto";
import {esc, langSuffix} from "./html";
import {formatDate, formatShortDate, t} from "./i18n";
import type {ProjectList} from "./project";
import {
    baseUrl,
    currentProject,
    currentProjectTitle,
    langQuery,
    otherProjectHref,
    projectLabel,
    projectTitle,
    stateMark,
    toBase
} from "./project";
import {signInState} from "./session";
import {setProjectTabWidth} from "./tab-width-control";

/**
 * Fills the top bar: the current page, "Go to object…", the index date with its panel, the parse note with its panel
 * when files failed or were recovered, and on a project's page its picker and tab title (call it after `initI18n` filled
 * the title); returns the run summary, or null when the index is unavailable.
 */
export async function showBanner() {
  const date = document.querySelector<HTMLButtonElement>("header.top .run")!;
  markCurrentPage();
  trackBarHeight();
  setupGoto();
  const project = currentProject();
  if (project) {
    document.title = projectTitle(currentProjectTitle(), document.title);
    showProject(project);
  }
  showUser(date);
  try {
    const answer = await api<RunAnswer>("api/run");
    setProjectTabWidth(answer.tabWidth);
    date.textContent = formatShortDate(answer.run.finishedAt);
    date.title = t("banner.date", { date: formatDate(answer.run.finishedAt) });
    date.setAttribute("aria-label", date.title);
    panel(date, indexPanelHtml(answer.run, answer.result));
    if (answer.result.parseProblemTotal > 0) showParseNote(date, answer.result);
    return answer.result;
  } catch (e) {
    date.textContent = t("banner.unavailable", { error: errorMessage(e) });
    date.disabled = true;
    return null;
  }
}

/** What the index holds and where its names were checked: for whoever wants to know, off the pages people use. */
function indexPanelHtml(run: RunStamp, summary: RunSummary) {
  const sum = (counts: { count: number }[]) => counts.reduce((n, c) => n + c.count, 0);
  return `<b>${esc(t("banner.index"))}</b>
    <div>${esc(t("banner.date", { date: formatDate(run.finishedAt) }))}</div>
    <div>${esc(t("home.files", { count: sum(summary.filesByParseStatus) }))} · ${esc(t("home.occurrences", { count: sum(summary.occurrencesByStatus) }))}</div>
    <div>${esc(t("banner.schema", { schema: run.compilationSchema ?? t("banner.none") }))}</div>
    <div>${esc(t("banner.metadata", { metadata: run.metadataOrigin ?? t("banner.none") }))}</div>`;
}

/** After the date: one short item for the files whose parse failed or was recovered, opening their list. */
function showParseNote(date: HTMLElement, summary: RunSummary) {
  const total = summary.parseProblemTotal;
  const note = document.createElement("button");
  note.type = "button";
  note.className = "parse-note";
  note.title = t("banner.parse", { count: total });
  note.setAttribute("aria-label", note.title);
  note.innerHTML = `<span class="long">${esc(note.title)}</span><span class="short" aria-hidden="true">${esc(t("banner.parseShort", { count: total }))}</span>`;
  date.after(note);
  const rest = total - summary.parseProblems.length;
  panel(note, `<b>${esc(note.title)}</b><div class="muted">${esc(t("banner.parseHint"))}</div>
    <ul class="parse-files">${summary.parseProblems.map(f =>
      `<li><a class="mono" href="file.html?path=${encodeURIComponent(f.path)}${langSuffix()}">${esc(f.path)}</a> <span class="badge parse-${esc(f.parseStatus)}" title="${esc(f.parseStatus)}">${esc(parseLabel(f.parseStatus))}</span></li>`).join("")}</ul>` +
    (rest > 0 ? `<div class="muted">${esc(t("banner.parseMore", { count: rest }))}</div>` : ""));
}

/** A panel under the top bar that [button] opens and closes; one panel is open at a time, and Esc or a click elsewhere closes it. */
function panel(button: HTMLElement, html: string) {
  const pop = document.createElement("div");
  pop.className = "bar-pop";
  pop.hidden = true;
  pop.id = "bar-pop-" + document.querySelectorAll(".bar-pop").length;
  pop.innerHTML = html;
  document.querySelector("header.top")!.append(pop);
  button.setAttribute("aria-controls", pop.id);
  button.setAttribute("aria-expanded", "false");
  const show = (open: boolean) => {
    if (open) for (const other of document.querySelectorAll<HTMLElement>(".bar-pop:not([hidden])")) other.hidden = true;
    for (const b of document.querySelectorAll<HTMLElement>("header.top [aria-controls^=bar-pop]")) b.setAttribute("aria-expanded", String(open && b === button));
    pop.hidden = !open;
  };
  button.addEventListener("click", () => show(pop.hidden !== false));
  document.addEventListener("click", e => { if (!pop.hidden && !pop.contains(e.target as Node) && !button.contains(e.target as Node)) show(false); });
  document.addEventListener("keydown", e => { if (e.key === "Escape" && !pop.hidden) { show(false); button.focus(); } });
}

/** Marks the top bar's link to the page shown, by its file name (the home page is `index.html`). */
function markCurrentPage() {
  const file = (path: string) => path.split("/").pop() || "index.html";
  const page = file(location.pathname);
  for (const link of document.querySelectorAll<HTMLAnchorElement>("header.top a.nav")) {
    if (file(new URL(link.href).pathname) === page) link.setAttribute("aria-current", "page");
  }
}

/** Keeps `--bar-h` at the top bar's height as it wraps, for the headers that stick under it. */
function trackBarHeight() {
  const bar = document.querySelector<HTMLElement>("header.top");
  if (!bar) return;
  new ResizeObserver(() => document.documentElement.style.setProperty("--bar-h", bar.offsetHeight + "px")).observe(bar);
}

/**
 * After the home link: [project]'s name, which becomes a picker of the server's projects (with the list of projects as
 * the last choice) once they load. Choosing one opens its home page, keeping the language.
 */
async function showProject(project: string) {
  const name = document.createElement("span");
  name.className = "project";
  name.textContent = currentProjectTitle() ?? project;
  document.querySelector("header.top > a")!.after(name);
  let projects;
  try {
    projects = (await api<ProjectList>(toBase(project) + "api/projects")).projects;
  } catch {
    return;
  }
  const choices = projects.some(p => p.name === project) ? projects : [{ name: project, state: "READY" }, ...projects];
  const options = choices.map(p => {
    const mark = stateMark(p, { t, formatDate });
    return `<option value="${esc(otherProjectHref(p.name, location.search))}"${p.name === project ? " selected" : ""}>${esc(projectLabel(p))}${mark ? esc(" (" + mark + ")") : ""}</option>`;
  });
  options.push(`<option value="${esc(toBase(project) + langQuery(location.search))}">${esc(t("project.all"))}</option>`);
  const select = document.createElement("select");
  select.className = "project";
  select.setAttribute("aria-label", t("project.label"));
  select.title = t("project.label");
  select.innerHTML = options.join("");
  select.autocomplete = "off";
  select.addEventListener("change", () => location.assign(select.value));
  // Back to this page from the history, the browser shows the choice that left it: show this project again.
  const current = select.selectedIndex;
  window.addEventListener("pageshow", () => { select.selectedIndex = current; });
  name.replaceWith(select);
}

/** After [after]: the signed-in user and the sign-out, when the server signs in. */
export async function showUser(after: HTMLElement) {
  const state = await signInState();
  if (state.state !== "in") return;
  const user = document.createElement("span");
  user.className = "user";
  user.innerHTML = `<span class="user-name" title="${esc(t("header.signedIn", { name: state.name }))}">${esc(state.name)}</span>` +
    `<form method="post" action="${esc(baseUrl("auth/logout"))}"><button type="submit">${esc(t("header.signOut"))}</button></form>`;
  after.after(user);
}

export async function sourceLine(path: string, line: number, target: HTMLElement, context?: number) {
  try {
    const answer = await api<Answer<SourceSnippet>>("api/source", { path, line, context: context ?? 0 });
    const s = answer.result;
    target.innerHTML = `<pre class="src">${s.lines.map(l =>
      `<span class="${l.number === line ? "hl" : ""}">${String(l.number).padStart(5)}  ${esc(l.text)}</span>`).join("\n")}</pre>` +
      (s.stale ? `<div class="muted">${esc(t("source.stale"))}</div>` : "");
  } catch (e) {
    target.innerHTML = `<span class="muted">${esc(t("source.unavailable", { error: errorMessage(e) }))}</span>`;
  }
}
