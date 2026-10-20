import type { CallArgument, Count, OccurrenceRow } from "./api";
import { sourceLine } from "./banner";
import { entityLink, roleLabel } from "./entity-html";
import { esc, langSuffix, lineLink, literalText, statusBadge } from "./html";
import { formatNumber, t } from "./i18n";

// ---- "who uses" rows, grouped by the program they are in ----------------------------------------------------------

/** The arguments of a call as `NAME => VALUE`; past four, the rest behind a summary that opens. */
function argumentsHtml(args: CallArgument[] | null | undefined) {
  if (!args || args.length === 0) return "";
  const items = args.map(a => {
    const name = a.label ?? a.parameter?.name ?? "#" + a.ordinal;
    const value = literalText(a.valueKind, a.value) ?? (a.valueKind === "NAME" || a.valueKind === "BIND" ? a.value : "…");
    return esc(name) + " =&gt; " + esc(value);
  });
  if (items.length <= 4) return `<div class="args mono muted">(${items.join(", ")})</div>`;
  return `<details class="args mono muted"><summary>(${items.slice(0, 4).join(", ")}, … +${esc(formatNumber(items.length - 4))})</summary>(${items.join(", ")})</details>`;
}

export interface UsesBlock {
  element: HTMLDivElement;
  append(rows: OccurrenceRow[]): void;
}

/**
 * A "who uses" table: every row under a header naming the program it is in (its container, else its unit) and its
 * file. The groups and their columns are shared across [append] calls, so later pages fill the same groups. With
 * `options.entity`, each row also names the entity it hit.
 */
export function usesBlock(options?: { entity?: boolean }): UsesBlock {
  const element = document.createElement("div");
  element.className = "scroll";
  element.hidden = true;
  element.innerHTML = `<table class="uses"><colgroup><col class="c-at"><col class="c-what"><col class="c-role"><col class="c-status"><col class="c-toggle"></colgroup></table>`;
  const table = element.firstElementChild!;
  const groups = new Map<string, HTMLTableSectionElement>();
  return {
    element,
    append(rows) {
      for (const row of rows) {
        const groupKey = row.path + "|" + (row.container?.key ?? row.unit.kind + " " + row.unit.name);
        let tbody = groups.get(groupKey);
        if (!tbody) {
          tbody = document.createElement("tbody");
          tbody.innerHTML = groupHeaderHtml(row);
          table.appendChild(tbody);
          groups.set(groupKey, tbody);
        }
        tbody.insertAdjacentHTML("beforeend", useRowHtml(row, options));
      }
      if (rows.length) element.hidden = false;
    }
  };
}

function groupHeaderHtml(row: OccurrenceRow) {
  const program = row.container ? entityLink(row.container) : `<span class="muted">${esc(row.unit.kind)} ${esc(row.unit.name)}</span>`;
  return `<tr class="group"><th colspan="5">${program} <span class="muted">·</span>
    <a class="mono muted" href="file.html?path=${encodeURIComponent(row.path)}${langSuffix()}">${esc(row.path)}</a></th></tr>`;
}

function useRowHtml(row: OccurrenceRow, options?: { entity?: boolean }) {
  // A column write with literal values: `= 'S'`, one per row of a multi-row insert.
  const literals = row.values.map(v => literalText(v.valueKind, v.value)).filter(v => v !== null);
  const written = literals.length === 0 ? "" : `<div class="muted mono">= ${esc(literals.join(", "))}</div>`;
  // The view column or view the row was reached through.
  const via = row.viaView ? `<div class="muted">${esc(t("usage.via"))} ${entityLink(row.matched)}</div>` : "";
  const hit = options?.entity && !row.viaCandidate ? `<div>${entityLink(row.matched)}</div>` : "";
  const others = row.viaCandidate
    ? `<div class="muted">${esc(t("usage.candidates"))} ${row.candidates.map(c => entityLink(c)).join(", ")}</div>` : "";
  const statement = row.statementKind && !row.roles.includes("SQL_" + row.statementKind)
    ? ` <span class="muted">${esc(row.statementKind)}</span>` : "";
  return `<tr data-path="${esc(row.path)}" data-line="${row.span.startLine}"><td>${lineLink(row.path, row.span.startLine, row.span.startCol)}</td>
    <td><span class="mono">${esc(row.written)}</span>${hit}${argumentsHtml(row.arguments)}${written}${via}${others}</td>
    <td>${esc(row.roles.map(roleLabel).join(", "))}${statement}</td>
    <td>${row.status === "RESOLVED" ? "" : statusBadge(row.status, row.reason)}</td>
    <td><button class="snip" title="${esc(t("source.show"))}">▸</button></td></tr>`;
}

/**
 * The source around a "who uses" row, opened under it and closed by the same button: registered on the document, by
 * the page before its own listeners.
 */
export function setupSourceToggle() {
  document.addEventListener("click", e => {
    const button = (e.target as Element).closest("button.snip");
    if (!button) return;
    const row = button.closest("tr")!;
    const next = row.nextElementSibling;
    if (next?.classList.contains("snippet")) {
      next.remove();
      button.textContent = "▸";
      return;
    }
    row.insertAdjacentHTML("afterend", `<tr class="snippet"><td colspan="5"><div></div></td></tr>`);
    sourceLine(row.dataset.path!, Number(row.dataset.line), row.nextElementSibling!.querySelector("div")!, 2);
    button.textContent = "▾";
  });
}

/**
 * Facet chips into the element [id]: one per counted value, `data-<attribute>` holding the value, the [selected]
 * ones on.
 */
export function renderChips(id: string, counts: Count[], selected: Set<string>, attribute: string, label?: (value: string) => string) {
  const target = document.getElementById(id)!;
  // A single value filters nothing; it shows only when it is selected.
  if (counts.length <= 1 && selected.size === 0) {
    target.innerHTML = "";
    return;
  }
  // A selected value stays visible (count 0) even when the other filter leaves it out of the facet.
  const listed = new Set(counts.map(c => c.value));
  counts = counts.concat([...selected].filter(v => !listed.has(v)).map(v => ({ value: v, count: 0 })));
  target.innerHTML = counts.map(c =>
    `<span class="chip ${selected.has(c.value) ? "on" : ""}" data-${attribute}="${esc(c.value)}">${esc(label ? label(c.value) : c.value)} (${esc(formatNumber(c.count))})</span>`).join("");
}
