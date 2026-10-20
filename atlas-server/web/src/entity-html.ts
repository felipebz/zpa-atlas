import type { EntitySummary } from "./api";
import { entityHref, esc, qualifierHtml } from "./html";
import { t } from "./i18n";
import { displayParts, parameterNamesOnly } from "./names";

export interface NameOptions {
  relative?: boolean;
  within?: EntitySummary;
  noBadge?: boolean;
  signature?: string;
}

/**
 * An entity's name as HTML, with the full display as its tooltip: the qualifier muted with parameter names only
 * (left out with [relative], under its parent; with [within], an entity, only the part below that entity, or none for
 * the entity itself or its body), the name, its signature, and a "body" badge for a body or a member of a body (left
 * out with [noBadge] for the entity itself, where the list already says spec or body). [signature] is `"names"`
 * (`RUN_JOB(P_A, P_B)`, the default: overloads stay apart) or `"full"` (every mode and type).
 */
export function nameHtml(entity: EntitySummary, options?: NameOptions) {
  const parts = displayParts(entity.display, entity.name);
  let shortQualifier = parts.shortQualifier;
  if (options?.within) {
    const withoutBody = (text: string) => text.replace(/@BODY(?=[#.]|$)/g, "");
    const base = withoutBody(options.within.display);
    const qualifier = withoutBody(parts.qualifier);
    if (withoutBody(entity.display) === base) shortQualifier = "";
    else if (qualifier.startsWith(base + ".")) shortQualifier = parameterNamesOnly(qualifier.slice(base.length + 1));
  }
  const qualifier = shortQualifier && !options?.relative ? `<span class="qualifier">${qualifierHtml(shortQualifier)}</span>` : "";
  let text;
  if (options?.signature === "full" || parts.params === null) {
    text = options?.signature === "full" ? parts.text : parts.base;
  } else {
    // A key collision's `#2` stays after the parameter list, where the display has it.
    const collision = /#\d+$/.exec(parts.base);
    const bare = collision ? parts.base.slice(0, collision.index) : parts.base;
    text = `${bare}(${parts.params.map(p => p.name).join(", ")})${collision ? collision[0] : ""}`;
  }
  const body = (parts.body && !options?.noBadge) || (parts.qualifierBody && !options?.relative);
  const badge = body ? ` <span class="badge">${esc(t("entity.overload.body"))}</span>` : "";
  return `<span class="ename" title="${esc(entity.display)}">${qualifier}<span class="name">${esc(text)}</span>${badge}</span>`;
}

/** The same rendering as DOM nodes, for pages that build their DOM from text nodes. */
export function nameNodes(entity: EntitySummary, options?: NameOptions) {
  const holder = document.createElement("span");
  holder.innerHTML = nameHtml(entity, options);
  return holder;
}

export function entityLink(entity: EntitySummary | null | undefined, options?: NameOptions) {
  if (!entity) return '<span class="muted">—</span>';
  const ext = entity.external ? ` <span class="badge external">${esc(t("entity.external"))}</span>` : "";
  return `<a href="${entityHref(entity.key)}">${nameHtml(entity, options)}</a>${ext}`;
}

/** A catalog label (`<prefix>.<NAME>`) when the catalog has one, else the stored name. */
function storedLabel(prefix: string, name: string) {
  const key = prefix + "." + name;
  const label = t(key);
  return label === key ? name : label;
}

/** A role as shown. */
export const roleLabel = (role: string) => storedLabel("role", role);

/** An entity kind as shown. */
export const kindLabel = (kind: string) => storedLabel("kind", kind);
