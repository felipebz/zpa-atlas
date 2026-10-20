import { api, errorMessage } from "./api";
import type { Answer, RunSummary, SourceSnippet } from "./api";
import { esc } from "./html";
import { formatDate, t } from "./i18n";

/** Fills the run line and the parse warning; returns the run summary, or null when the index is unavailable. */
export async function showBanner() {
  const header = document.querySelector<HTMLElement>("header.top .run")!;
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
