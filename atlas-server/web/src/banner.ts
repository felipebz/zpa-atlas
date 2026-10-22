import { api, errorMessage } from "./api";
import type { Answer, RunSummary, SourceSnippet } from "./api";
import { esc } from "./html";
import { formatDate, t } from "./i18n";
import { baseUrl, currentProject, langQuery, otherProjectHref, projectTitle, stateMark, toBase } from "./project";
import type { ProjectList } from "./project";
import { signInState } from "./session";

/**
 * Fills the run line and the parse warning, and on a project's page its picker and tab title (call it after `initI18n`
 * filled the title); returns the run summary, or null when the index is unavailable.
 */
export async function showBanner() {
  const header = document.querySelector<HTMLElement>("header.top .run")!;
  const project = currentProject();
  if (project) {
    document.title = projectTitle(project, document.title);
    showProject(project);
  }
  showUser(header);
  const warn = document.getElementById("warn")!;
  try {
    const answer = await api<Answer<RunSummary>>("api/run");
    const run = answer.run;
    header.textContent = t("banner.run", {
      date: formatDate(run.finishedAt),
      schema: run.compilationSchema ?? t("banner.none")
    });
    header.title = t("banner.metadata", { metadata: run.metadataOrigin ?? t("banner.none") });
    const byStatus: Record<string, number> = Object.fromEntries(answer.result.filesByParseStatus.map(c => [c.value, c.count]));
    const problems = [];
    if (byStatus.FAILED) problems.push(t("banner.failed", { count: byStatus.FAILED }));
    if (byStatus.RECOVERED) problems.push(t("banner.recovered", { count: byStatus.RECOVERED }));
    if (problems.length) {
      warn.textContent = t("banner.warning", { problems: problems.join(t("banner.separator")) });
      warn.hidden = false;
    }
    return answer.result;
  } catch (e) {
    header.textContent = t("banner.unavailable", { error: errorMessage(e) });
    return null;
  }
}

/**
 * After the home link: [project]'s name, which becomes a picker of the server's projects (with the list of projects as
 * the last choice) once they load. Choosing one opens its home page, keeping the language.
 */
async function showProject(project: string) {
  const name = document.createElement("span");
  name.className = "project";
  name.textContent = project;
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
    return `<option value="${esc(otherProjectHref(p.name, location.search))}"${p.name === project ? " selected" : ""}>${esc(p.name)}${mark ? esc(" (" + mark + ")") : ""}</option>`;
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
