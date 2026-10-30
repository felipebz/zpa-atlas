import type {CallArgument, Count, EntitySummary, OccurrenceRow} from "./api";
import {sourceLine} from "./banner";
import {entityLink, kindBadge, nameHtml, roleLabel, statusBadge, unitKindLabel, valueKindLabel} from "./entity-html";
import {entityHref, esc, langSuffix, lineLink, literalText} from "./html";
import {formatNumber, t} from "./i18n";
import type {UsesContext, UsesGroup} from "./uses-view";
import {
    argumentChip,
    ARGUMENTS_SHOWN,
    enclosingRoutine,
    groupOf,
    programWithin,
    rowNaming,
    shownStatement
} from "./uses-view";

// ---- "who uses" rows, grouped by the program they are in ----------------------------------------------------------

/** One argument as a chip: the parameter's name in bold, then the value; the whole value is the tooltip. */
function argumentHtml(argument: CallArgument, hidden: boolean) {
  const chip = argumentChip(argument);
  const value = chip.look === "expression" ? `<span class="muted">${esc(valueKindLabel("EXPRESSION"))}</span>`
    : chip.look === "string" ? `<span class="s">${esc(chip.text)}</span>` : esc(chip.text);
  const title = (chip.name ? chip.name + " => " : "") + (chip.look === "expression" ? valueKindLabel("EXPRESSION") : chip.whole);
  return `<span class="arg"${hidden ? " hidden" : ""} title="${esc(title)}">${chip.name ? `<b>${esc(chip.name)}</b> ` : ""}${value}</span>`;
}

/** The arguments of a call as chips; past [ARGUMENTS_SHOWN], a `+N` chip opens the rest. */
function argumentsHtml(args: CallArgument[] | null | undefined) {
  if (!args || args.length === 0) return "";
  const rest = args.length - ARGUMENTS_SHOWN;
  const more = rest > 0
    ? `<button type="button" class="arg more-args" title="${esc(t("usage.moreArguments", { count: rest }))}">+${esc(formatNumber(rest))}</button>` : "";
  return `<div class="args">${args.map((a, i) => argumentHtml(a, i >= ARGUMENTS_SHOWN)).join("")}${more}</div>`;
}

export interface UsesBlock {
  element: HTMLDivElement;
  append(rows: OccurrenceRow[]): void;
}

/** One group of a block: its element, its count of rows so far, and the files they are in. */
interface GroupState {
  element: HTMLElement;
  count: number;
  paths: string[];
  /** For a script group: the kinds of its units (a file may hold SQL statements and anonymous blocks). */
  units: string[];
}

/**
 * A "who uses" list: every row under a header naming the program it is in ([UsesContext.grouping]) and its files, with
 * the group's count of rows on the right. The groups are shared across [append] calls, so later pages fill the same
 * groups. [context] also says how each row names what it hit ([rowNaming]).
 */
export function usesBlock(context: UsesContext = {}): UsesBlock {
  const element = document.createElement("div");
  element.className = "uses";
  element.hidden = true;
  const groups = new Map<string, GroupState>();
  return {
    element,
    append(rows) {
      for (const row of rows) {
        const group = groupOf(row, context.grouping);
        let state = groups.get(group.key);
        if (!state) {
          const holder = document.createElement("div");
          holder.className = "grp";
          holder.innerHTML = groupHeaderHtml(group, context);
          element.appendChild(holder);
          state = { element: holder, count: 0, paths: [], units: [] };
          groups.set(group.key, state);
        }
        state.element.insertAdjacentHTML("beforeend", useRowHtml(row, context));
        state.count++;
        if (!state.paths.includes(row.path)) {
          state.paths.push(row.path);
          state.element.querySelector(".grp-files")!.innerHTML = state.paths.map(fileLink).join(", ");
        }
        if (!group.program && !state.units.includes(row.unit.kind)) {
          state.units.push(row.unit.kind);
          const units = state.element.querySelector<HTMLElement>(".grp-units")!;
          units.textContent = state.units.map(unitKindLabel).join(", ");
          units.title = state.units.join(", ");
        }
        const count = state.element.querySelector<HTMLElement>(".grp-n")!;
        count.textContent = formatNumber(state.count);
        count.title = t("usage.listed", { count: state.count });
      }
      if (rows.length) element.hidden = false;
    }
  };
}

const fileLink = (path: string) =>
  `<a class="mono" href="file.html?path=${encodeURIComponent(path)}${langSuffix()}">${esc(path).replace(/\//g, "/<wbr>")}</a>`;

/**
 * A program's short name: without its schema, its owner package kept (dropped too when the owner is the page's own
 * entity), no "body" badge; the tooltip keeps the full display.
 */
function programHtml(program: EntitySummary, context: UsesContext) {
  const within = programWithin(program, context.page);
  return `<a href="${entityHref(program.key)}">${nameHtml(program, { within: within ? { display: within } : undefined, noBodyBadge: true })}</a>`;
}

/** A group's header: the program (kind square, short name), else the kinds of its script units; then its files. */
function groupHeaderHtml(group: UsesGroup, context: UsesContext) {
  const title = group.program ? `${kindBadge(group.program.kind, true)} ${programHtml(group.program, context)}` : `<span class="grp-units"></span>`;
  return `<div class="grp-h"><div class="grp-name"><div>${title}</div><div class="grp-files muted"></div></div><span class="grp-n muted"></span></div>`;
}

function useRowHtml(row: OccurrenceRow, context: UsesContext) {
  // A column write with literal values: `= 'S'`, one per row of a multi-row insert.
  const literals = row.values.map(v => literalText(v.valueKind, v.value)).filter(v => v !== null);
  const written = literals.length === 0 ? "" : `<div class="muted mono">= ${esc(literals.join(", "))}</div>`;
  const naming = rowNaming(row, context);
  const name = naming === "entity" ? entityLink(row.matched)
    : naming === "member" ? `<a class="member mono" href="${entityHref(row.matched!.key)}" title="${esc(row.matched!.display)}">${esc(row.matched!.name ?? row.written)}</a>`
    : naming === "written" ? `<span class="mono">${esc(row.written)}</span>` : "";
  // Grouped by program, a row in one of the program's routines says which.
  const routine = context.grouping === "program" ? enclosingRoutine(row) : null;
  const inRoutine = routine
    ? ` <span class="muted">${esc(t("usage.in"))}</span> <a class="mono" href="${entityHref(routine.key)}" title="${esc(routine.display)}">${esc(routine.name ?? routine.display)}</a>` : "";
  const head = name || inRoutine ? `<div class="w">${name}${inRoutine}</div>` : "";
  // The view column or view the row was reached through.
  const via = row.viaView ? `<div class="muted">${esc(t("usage.via"))} ${entityLink(row.matched)}</div>` : "";
  const others = row.viaCandidate
    ? `<div class="muted">${esc(t("usage.candidates"))} ${row.candidates.map(c => entityLink(c)).join(", ")}</div>` : "";
  const statement = shownStatement(row);
  const statementHtml = statement
    ? ` <span class="muted" title="${esc(statement)}">${esc(t("usage.inStatement." + statement))}</span>` : "";
  const status = row.status === "RESOLVED" ? "" : " " + statusBadge(row.status, row.reason);
  const at = lineLink(row.path, row.span.startLine, t("usage.at", { line: row.span.startLine, column: row.span.startCol }));
  return `<div class="use" data-path="${esc(row.path)}" data-line="${row.span.startLine}"><span class="at">${at}</span>
    <div class="what">${head}${argumentsHtml(row.arguments)}${written}${via}${others}</div>
    <span class="how" title="${esc(row.roles.join(", "))}">${esc(row.roles.map(roleLabel).join(", "))}${statementHtml}${status}</span>
    <button type="button" class="snip" title="${esc(t("source.show"))}">▸</button></div>`;
}

/**
 * The clicks of "who uses" rows, registered on the document by the page before its own listeners: a row's source
 * opens under it and closes with the same button, and a `+N` chip shows the rest of the arguments.
 */
export function setupUseRows() {
  document.addEventListener("click", e => {
    const target = e.target as Element;
    const moreArgs = target.closest("button.more-args");
    if (moreArgs) {
      moreArgs.parentElement!.querySelectorAll(".arg[hidden]").forEach(arg => arg.removeAttribute("hidden"));
      moreArgs.remove();
      return;
    }
    const button = target.closest("button.snip");
    if (!button) return;
    const row = button.closest<HTMLElement>(".use")!;
    const next = row.nextElementSibling;
    if (next?.classList.contains("snippet")) {
      next.remove();
      button.textContent = "▸";
      return;
    }
    row.insertAdjacentHTML("afterend", `<div class="snippet"></div>`);
    sourceLine(row.dataset.path!, Number(row.dataset.line), row.nextElementSibling as HTMLElement, 2);
    button.textContent = "▾";
  });
}

/**
 * Facet chips into the element [id]: one per counted value in words ([label]), `data-<attribute>` holding the value
 * and the tooltip the code, the [selected] ones on.
 */
export function renderChips(id: string, counts: Count[], selected: Set<string>, attribute: string, label: (value: string) => string) {
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
    `<span class="chip ${selected.has(c.value) ? "on" : ""}" data-${attribute}="${esc(c.value)}" title="${esc(c.value)}">${esc(label(c.value))} (${esc(formatNumber(c.count))})</span>`).join("");
}
