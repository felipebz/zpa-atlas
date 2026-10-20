import { api } from "./api";
import type { Answer, EntityDetail, EntitySummary, SearchHit } from "./api";
import { kindLabel, nameHtml } from "./entity-html";
import { esc, getOrPut, qualifierHtml } from "./html";
import { t } from "./i18n";
import { displayParts } from "./names";
import { field, form, picks, state, typed } from "./query-state";

const SUGGESTIONS = 10;

// ---- entities by key -----------------------------------------------------------------------------------------------

const entities = new Map<string, Promise<EntitySummary>>();

/** The entity of [key] (key, kind, display, name, external), or a stand-in showing the key when it cannot be read. */
export function entityOf(key: string) {
  return getOrPut(entities, key, () => api<Answer<EntityDetail>>("api/entity", { key }).then(a => a.result, () => null)
    .then(e => e ?? { key, kind: "", display: key, name: key, external: false }));
}

// ---- the suggest fields ----------------------------------------------------------------------------------------------

export function setupSuggest(box: HTMLElement) {
  const input = box.querySelector<HTMLInputElement>("input[type=text]")!;
  const list = box.querySelector<HTMLElement>("ul.suggestions")!;
  let timer: number | undefined;
  let hits: SearchHit[] = [];
  let active = -1;
  let asked = 0;
  const close = () => {
    clearTimeout(timer);
    asked++;
    list.hidden = true;
    input.setAttribute("aria-expanded", "false");
    active = -1;
  };
  const highlight = (index: number) => {
    active = index;
    list.querySelectorAll("li").forEach((li, i) => li.classList.toggle("active", i === active));
  };
  input.addEventListener("input", () => {
    clearTimeout(timer);
    const text = input.value.trim();
    // A pattern or a key is a filter as typed, not a search.
    if (!text || text.includes("*") || text.startsWith("db:")) { close(); return; }
    timer = setTimeout(async () => {
      const ask = ++asked;
      try {
        const found = (await api<Answer<SearchHit[]>>("api/entities", { q: text, limit: SUGGESTIONS })).result;
        if (ask !== asked || input.value.trim() !== text) return;
        hits = found;
        list.innerHTML = hits.length === 0 ? `<li class="muted">${esc(t("search.empty"))}</li>` : hits.map((h, i) => {
          const parts = displayParts(h.display, h.name);
          const qualifier = parts.shortQualifier.replace(/\.$/, "");
          return `<li role="option" data-index="${i}"><span class="hit-name">${nameHtml(h, { relative: true })}</span>
            <span class="badge">${esc(kindLabel(h.kind))}</span>${qualifier ? ` <span class="muted">${qualifierHtml(qualifier)}</span>` : ""}</li>`;
        }).join("");
        list.hidden = false;
        input.setAttribute("aria-expanded", "true");
        highlight(-1);
      } catch {
        close();
      }
    }, 250);
  });
  input.addEventListener("keydown", e => {
    // Enter without a highlighted suggestion closes the list and runs the typed text (the form submits).
    if (e.key === "Enter" && active < 0) { close(); return; }
    if (list.hidden) return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (hits.length) highlight(e.key === "ArrowDown" ? (active + 1) % hits.length : active <= 0 ? hits.length - 1 : active - 1);
    } else if (e.key === "Enter") {
      e.preventDefault();
      pick(box, hits[active]);
      close();
    } else if (e.key === "Escape") {
      e.preventDefault();
      close();
    }
  });
  input.addEventListener("blur", () => setTimeout(close, 150));
  // Mouse down, not click: the input's blur would close the list first.
  list.addEventListener("mousedown", e => {
    const li = (e.target as Element).closest<HTMLElement>("li[data-index]");
    if (!li) return;
    e.preventDefault();
    pick(box, hits[Number(li.dataset.index)]);
    close();
  });
  box.querySelector(".picked button.x")!.addEventListener("click", () => {
    unpick(box, true);
    input.focus();
  });
}

/** Shows [entity] picked in [box]'s own field box; the typed text is kept for when the pick is removed. */
export function pick(box: HTMLElement, entity: EntitySummary) {
  const param = box.dataset.param!;
  const input = box.querySelector<HTMLInputElement>("input[type=text]")!;
  if (!picks[param]) typed[param] = input.value;
  picks[param] = entity;
  input.value = "";
  input.hidden = true;
  const picked = box.querySelector<HTMLElement>(".picked")!;
  picked.querySelector(".pick-name")!.innerHTML = nameHtml(entity);
  const kind = picked.querySelector<HTMLElement>(".pick-kind")!;
  kind.textContent = entity.kind ? kindLabel(entity.kind) : "";
  kind.hidden = !entity.kind;
  picked.hidden = false;
  box.querySelector(".inside")?.removeAttribute("hidden");
}

/** Takes the pick out of [box]; with [restore], the text typed before it goes back into the field. */
export function unpick(box: HTMLElement, restore: boolean) {
  const param = box.dataset.param!;
  const input = box.querySelector<HTMLInputElement>("input[type=text]")!;
  picks[param] = null;
  box.querySelector<HTMLElement>(".picked")!.hidden = true;
  input.hidden = false;
  if (restore) input.value = typed[param];
  typed[param] = "";
  const inside = box.querySelector<HTMLElement>(".inside");
  if (inside) {
    inside.hidden = true;
    field("inside").checked = false;
  }
  if (param === "entity") setKept(null);
}

/** A `name` kept beside a picked entity, shown under it with an × of its own. */
export function setKept(name: string | null) {
  state.keptName = name;
  const kept = suggestBox("entity").querySelector<HTMLElement>(".kept")!;
  kept.hidden = !name;
  kept.querySelector(".kept-text")!.textContent = name ? t("query.name") + ": " + name : "";
}

export const suggestBox = (param: string) => form.querySelector<HTMLElement>(`.suggest[data-param="${param}"]`)!;
