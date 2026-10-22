import { api, errorMessage } from "./api";
import { showUser } from "./banner";
import { esc } from "./html";
import { formatDate, initI18n, t } from "./i18n";
import { projectsHtml } from "./project";
import type { ProjectList } from "./project";

// The list of the server's projects, at the base path: each opens its own pages under `p/<name>/`.

(async () => {
  await initI18n();
  showUser(document.querySelector<HTMLElement>("header.top .push")!);
  const target = document.getElementById("projects")!;
  try {
    const list = await api<ProjectList>("api/projects");
    target.innerHTML = projectsHtml(list.projects, { t, formatDate }, location.search);
  } catch (e) {
    target.innerHTML = `<p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p>`;
  }
})();
