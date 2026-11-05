import type {Answer, FormsGroup, FormsNode, FormsTree} from "./api";
import {api, errorMessage} from "./api";
import {formsNameLink, kindBadge} from "./entity-html";
import type {CodeUnit, FormsFilter} from "./forms-view";
import {allOpen, filterTree, formsName, groupId, initialOpen, nodeId, nodeUnit, openTo, unitKindWord, visibleRows} from "./forms-view";
import {esc} from "./html";
import {formatNumber, t} from "./i18n";

// A Forms module's, block's or item's objects as a tree, as the Form Builder's Object Navigator shows them: on the
// page of the module, block or item, and beside a piece of the module's code on the code page.

/** What the code page asks of its tree: where a piece of code leads, and what choosing one does instead of following it. */
export interface FormsTreeCode {
  href: (unit: CodeUnit) => string;
  choose: (unit: CodeUnit) => void;
}

/** A tree on the page: [mark] marks the node [id] as the one shown (null: none), its branch opened. */
export interface FormsTreeView {
  mark: (id: string | null) => void;
}

/**
 * The objects of the Forms module, block or item [key] into [section], under its heading ([renderFormsTree]); nothing
 * when it holds none. The tree, for what else the page says of it; null when it could not be read.
 */
export async function showFormsTree(section: HTMLElement, key: string): Promise<FormsTree | null> {
  let tree: FormsTree;
  try {
    tree = (await api<Answer<FormsTree>>("api/forms-tree", { key })).result;
  } catch (e) {
    section.innerHTML = `<h2>${esc(t("forms.objects"))}</h2><p class="muted">${esc(t("error.message", { error: errorMessage(e) }))}</p>`;
    return null;
  }
  if (tree.groups.length === 0) {
    section.innerHTML = "";
    return tree;
  }
  section.innerHTML = `<h2>${esc(t("forms.objects"))}</h2><p class="sec-line">${esc(t("forms.objects.line"))}</p>`;
  const holder = document.createElement("div");
  section.appendChild(holder);
  renderFormsTree(holder, tree);
  return tree;
}

/**
 * [tree] into [holder]: each group and object on its line (an object by its kind's square and its own name, a
 * package's specification and body each a node, the word after it telling which; the full path as the tooltip),
 * groups open and objects closed at first; "Expand all", "Collapse all", and a filter by name that opens the branches
 * to what it keeps and, once cleared, puts back the open state it found. An object links to its page; with [code], a
 * piece of code links to the code page instead, and choosing it calls [FormsTreeCode.choose].
 */
export function renderFormsTree(holder: HTMLElement, tree: FormsTree, code?: FormsTreeCode): FormsTreeView {
  // Each group and object by its place in [ids]: its id in the open state, its node, its line, its toggle and what it opens.
  const ids: string[] = [];
  const nodes: (FormsNode | null)[] = [];
  const groupHtml = (parent: string, group: FormsGroup): string => {
    const i = ids.push(groupId(parent, group.kind)) - 1;
    nodes.push(null);
    return `<li class="fgroup" data-i="${i}"><button type="button" class="ftoggle fhead" aria-expanded="true" aria-controls="ftree-${i}">` +
      `<span>${esc(t("forms.group." + group.kind))}</span> <span class="count">${esc(formatNumber(group.nodes.length))}</span></button>` +
      `<ul id="ftree-${i}">${group.nodes.map(nodeHtml).join("")}</ul></li>`;
  };
  const nodeHtml = (node: FormsNode): string => {
    const { kind, name } = node.entity;
    const id = nodeId(node.entity.key, node.unitKind);
    const i = ids.push(id) - 1;
    nodes.push(node);
    const openable = node.groups.length > 0;
    const toggle = openable
      ? `<button type="button" class="ftoggle" aria-expanded="false" aria-controls="ftree-${i}" aria-label="${esc(t("forms.toggle", { name }))}"></button>`
      : `<span class="fgap" aria-hidden="true"></span>`;
    const unit = nodeUnit(node);
    const link = code && unit ? formsNameLink(formsName(node.entity, null), code.href(unit)) : formsNameLink(formsName(node.entity, null));
    const word = unitKindWord(node.unitKind);
    const half = word === null ? "" : `<span class="funit muted">${esc(t(word))}</span>`;
    return `<li class="fnode" data-i="${i}"><div class="fline">${toggle}${kindBadge(kind, true)}${link}${half}</div>` +
      (openable ? `<ul id="ftree-${i}">${node.groups.map(g => groupHtml(id, g)).join("")}</ul>` : "") + `</li>`;
  };
  const list = tree.groups.map(g => groupHtml(tree.root.key, g)).join("");
  holder.innerHTML = `<div class="tools"><input type="search" class="filter" placeholder="${esc(t("forms.filter"))}" aria-label="${esc(t("forms.filter"))}">` +
    `<button type="button" data-tree="expand">${esc(t("forms.expandAll"))}</button><button type="button" data-tree="collapse">${esc(t("forms.collapseAll"))}</button></div>
    <p class="muted fnone" hidden>${esc(t("forms.noMatch"))}</p><ul class="ftree">${list}</ul>`;
  const items = [...holder.querySelectorAll<HTMLElement>("li[data-i]")].sort((a, b) => Number(a.dataset.i) - Number(b.dataset.i));
  const toggles = items.map(li => li.querySelector<HTMLButtonElement>(":scope > .ftoggle, :scope > .fline > .ftoggle"));
  const lists = items.map(li => li.querySelector<HTMLElement>(":scope > ul"));
  const none = holder.querySelector<HTMLElement>(".fnone")!;
  const treeList = holder.querySelector<HTMLElement>("ul.ftree")!;

  let open = initialOpen(tree);
  let filter: FormsFilter | null = null;
  /** The open state the filter found, put back when it is cleared; null while no filter is on. */
  let saved: Set<string> | null = null;
  const apply = () => {
    const rows = new Map(visibleRows(tree, open, filter).map(row => [row.id, row]));
    ids.forEach((id, i) => {
      const row = rows.get(id);
      items[i].hidden = !row;
      if (!row) return;
      toggles[i]?.setAttribute("aria-expanded", String(row.open));
      if (lists[i]) lists[i]!.hidden = !row.open;
    });
    none.hidden = filter === null || rows.size > 0;
  };
  holder.addEventListener("click", e => {
    const target = e.target as Element;
    const action = target.closest<HTMLElement>("button[data-tree]")?.dataset.tree;
    if (action) {
      open = action === "expand" ? allOpen(tree) : new Set();
      apply();
      return;
    }
    const toggle = target.closest<HTMLButtonElement>("button.ftoggle");
    if (toggle) {
      const id = ids[Number(toggle.closest<HTMLElement>("li[data-i]")!.dataset.i)];
      if (!open.delete(id)) open.add(id);
      apply();
      return;
    }
    // A piece of code chosen on the code page opens there; a click that asks for another tab or window follows the link.
    const link = target.closest<HTMLAnchorElement>(".fline > a");
    if (!code || !link || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    const node = nodes[Number(link.closest<HTMLElement>("li[data-i]")!.dataset.i)];
    const unit = node && nodeUnit(node);
    if (!unit) return;
    e.preventDefault();
    code.choose(unit);
  });
  holder.querySelector("input.filter")!.addEventListener("input", e => {
    filter = filterTree(tree, (e.target as HTMLInputElement).value);
    if (filter !== null) {
      saved ??= open;
      open = new Set(filter.open);
    } else {
      open = saved ?? initialOpen(tree);
      saved = null;
    }
    apply();
  });
  apply();
  return {
    mark(id) {
      holder.querySelector(".fline > a[aria-current]")?.removeAttribute("aria-current");
      const i = id === null ? -1 : ids.indexOf(id);
      if (id === null || i < 0) return;
      open = openTo(tree, open, id);
      if (saved !== null) saved = openTo(tree, saved, id);
      apply();
      const link = items[i].querySelector<HTMLElement>(":scope > .fline > a")!;
      link.setAttribute("aria-current", "page");
      // The list scrolls itself to keep the mark in view; the page does not move.
      const box = link.getBoundingClientRect(), frame = treeList.getBoundingClientRect();
      if (box.top < frame.top || box.bottom > frame.bottom) treeList.scrollTop += box.top - frame.top - frame.height / 3;
    }
  };
}
