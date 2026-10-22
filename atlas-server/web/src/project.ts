// The project a page belongs to when one server serves several projects. The server marks a project's pages with
// `<meta name="atlas-project">`; those pages live two folders below the base path (`<base>/p/<name>/`), where the
// sign-in and the list of projects are. Without the mark the page is at the base path and nothing changes.

import { esc } from "./html";

/** One project of `api/projects`, as the server writes it; [path] is relative to the base path. */
export interface ProjectInfo {
  name: string;
  path: string;
  state: string;
  finishedAt: string | null;
  files: number | null;
  error: string | null;
}

export interface ProjectList {
  projects: ProjectInfo[];
}

/** The texts the project list needs, so that it can be built without the page's catalogs. */
export interface ProjectTexts {
  t(key: string, params?: { count?: number; [name: string]: string | number | null | undefined }): string;
  formatDate(iso: string): string;
}

/** The project the server marked this page with; null at the base path. */
export function currentProject(): string | null {
  return document.querySelector<HTMLMetaElement>('meta[name="atlas-project"]')?.content || null;
}

/** From the folder of a page of [project] to the base path. */
export function toBase(project: string | null): string {
  return project ? "../../" : "";
}

/** [path], relative to the base path, as seen from this page. */
export function baseUrl(path: string): string {
  return toBase(currentProject()) + path;
}

/** The tab title of a page of [project]: the project first, so that tabs of several projects tell apart. */
export function projectTitle(project: string | null, title: string): string {
  return project ? `${project} · ${title}` : title;
}

/** Sets the tab title to [title], after the project of the page. */
export function setPageTitle(title: string) {
  document.title = projectTitle(currentProject(), title);
}

/** `?lang=…` when [search] has one, so that a link to another page keeps the language. */
export function langQuery(search: string) {
  const lang = new URLSearchParams(search).get("lang");
  return lang ? `?lang=${encodeURIComponent(lang)}` : "";
}

/** The home page of project [name], from a page of another project, keeping the language of [search]. */
export function otherProjectHref(name: string, search: string): string {
  return `../${encodeURIComponent(name)}/` + langQuery(search);
}

/** The mark of a project that has no index to answer from; null for a ready one. */
export function stateMark(project: Pick<ProjectInfo, "state">, texts: ProjectTexts): string | null {
  switch (project.state) {
    case "READY": return null;
    case "NOT_INDEXED": return texts.t("project.notIndexed");
    case "UNAVAILABLE": return texts.t("project.unavailable");
    default: return project.state;
  }
}

/** The list page's projects as HTML: each name links to its home page (keeping the language of [search]). */
export function projectsHtml(projects: ProjectInfo[], texts: ProjectTexts, search: string): string {
  if (!projects.length) return `<p class="muted">${esc(texts.t("projects.empty"))}</p>`;
  const lang = langQuery(search);
  const items = projects.map(p => {
    const facts = [];
    if (p.state === "READY") {
      if (p.finishedAt) facts.push(`<span class="project-date">${esc(texts.t("projects.indexed", { date: texts.formatDate(p.finishedAt) }))}</span>`);
      if (p.files !== null) facts.push(`<span class="project-files">${esc(texts.t("home.files", { count: p.files }))}</span>`);
    } else if (p.state === "UNAVAILABLE") {
      facts.push(`<span class="project-state">${esc(texts.t("projects.unavailable", { error: p.error ?? "" }))}</span>`);
    } else if (p.state === "NOT_INDEXED") {
      facts.push(`<span class="project-state">${esc(texts.t("projects.notIndexed"))}</span>`);
    } else {
      facts.push(`<span class="project-state">${esc(p.state)}</span>`);
    }
    return `<li><a class="project-name" href="${esc(p.path + lang)}">${esc(p.name)}</a>${facts.join("")}</li>`;
  });
  return `<ul class="projects">${items.join("")}</ul>`;
}
