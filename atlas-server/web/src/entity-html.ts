import type {EntitySummary} from "./api";
import type {Place} from "./entity-view";
import type {FormsName} from "./forms-view";
import {codeUnitName} from "./forms-view";
import {isBodyOwn} from "./entity-view";
import {entityHref, esc, fillMarks, lineHref, lineTitle, nameBreaksHtml, partBreaksHtml, qualifierHtml} from "./html";
import {t} from "./i18n";
import {kindMark} from "./kinds";
import type {NameShape} from "./names";
import {listParameterDetail, shownName} from "./names";

export interface NameOptions extends NameShape {
  noBadge?: boolean;
  /** No "body" badge at all, not even for a member of a body (a header that names the program, not its place). */
  noBodyBadge?: boolean;
  /** Break only between the name's parts (after a dot, a comma or a parenthesis), never inside an identifier. */
  partBreaks?: boolean;
}

/**
 * An entity's name as HTML, with the full display as its tooltip: the qualifier muted (see [NameShape]), the name, its
 * parameter list muted in parentheses (a short list unless [NameShape.parameters] asks for more), a key collision's `#2`, and a
 * "body" badge for a body or a member of a body (left out with [noBadge] for the entity itself, where the list already
 * says spec or body). A long name may break after each underscore.
 */
export function nameHtml(entity: EntitySummary, options?: NameOptions) {
  const shown = shownName(entity.display, entity.name, options);
  const parts = options?.partBreaks === true;
  const qualifier = shown.qualifier ? `<span class="qualifier">${parts ? partBreaksHtml(shown.qualifier) : qualifierHtml(shown.qualifier)}</span>` : "";
  // A parameter list may break at its spaces and after an underscore, as the name does, never inside a word; with
  // [NameOptions.partBreaks], only after a comma or a parenthesis.
  const word = (text: string) => parts ? partBreaksHtml(text) : esc(text).replace(/_/g, "_<wbr>");
  const params = shown.params === null ? "" : `<span class="params">${word(shown.params)}</span>`;
  const name = `<span class="name">${word(shown.bare)}${params}${esc(shown.collision)}</span>`;
  const body = !options?.noBodyBadge && ((shown.parts.body && !options?.noBadge) || (shown.parts.qualifierBody && !options?.relative));
  const badge = body ? ` <span class="badge">${esc(t("entity.overload.body"))}</span>` : "";
  return `<span class="ename" title="${esc(entity.display)}">${qualifier}${name}${badge}</span>`;
}

/**
 * The options for each entity of one list: [options], with as much of each parameter list as keeps overloads apart
 * ([listParameterDetail], from the detail [options] asks for).
 */
export function listNameOptions(entities: EntitySummary[], options?: NameOptions) {
  const detail = listParameterDetail(entities, options, options?.parameters);
  return (entity: EntitySummary): NameOptions => ({ ...options, parameters: detail(entity) });
}

/** A spec's and its body's links (`spec · body`) when both exist, the "body" badge as a link when only the body does. */
export function specBodyLinks(spec: EntitySummary | null, body: EntitySummary | null) {
  if (!body) return "";
  const bodyLink = (className: string) => `<a${className} href="${entityHref(body.key)}">${esc(t("entity.overload.body"))}</a>`;
  if (!spec) return ` ${bodyLink(' class="badge"')}`;
  return ` <span class="specbody"><a href="${entityHref(spec.key)}">${esc(t("entity.overload.spec"))}</a> · ${bodyLink("")}</span>`;
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
  const parts = options?.partBreaks ? ' class="parts"' : "";
  return `<a${parts} href="${entityHref(entity.key)}">${nameHtml(entity, options)}</a>${ext}`;
}

/**
 * A link to a Forms object as a page inside its module names it ([FormsName]): its own name (`ID1`), the full path
 * (`DEMO0004/V$LOCK/ID1`) as its tooltip, leading to [href] (its page unless told). The name may break after an
 * underscore or a dot, never inside a word.
 */
export function formsNameLink(name: FormsName, href = entityHref(name.key)) {
  return `<a href="${href}"><span class="ename" title="${esc(name.title)}"><span class="name">${nameBreaksHtml(name.label)}</span></span></a>`;
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

/** Other stored codes as shown, by the code list they come from. */
export const statusLabel = (status: string) => storedLabel("status", status);
export const reasonLabel = (reason: string) => storedLabel("reason", reason);
export const originLabel = (origin: string) => storedLabel("origin", origin);
export const unitKindLabel = (kind: string) => storedLabel("unitKind", kind);
export const statementLabel = (kind: string) => storedLabel("statement", kind);
export const valueKindLabel = (kind: string) => storedLabel("valueKind", kind);
export const parseLabel = (status: string) => storedLabel("parse", status);

/**
 * A status in words, colored by its meaning, with its reason in words unless it repeats the status; the codes are
 * the tooltip. A status and reason that together mean something else than the status alone read as one phrase, with
 * the neutral color: a call to a local subprogram is `UNRESOLVED` only because the index does not list it.
 */
export function statusBadge(status: string, reason?: string | null) {
  const withReason = reason && reason !== status;
  const codes = withReason ? status + " · " + reason : status;
  const pairKey = withReason ? `statusReason.${status}.${reason}` : "";
  const pair = pairKey ? t(pairKey) : "";
  if (pair && pair !== pairKey) return `<span class="badge" title="${esc(codes)}">${esc(pair)}</span>`;
  return `<span class="badge st-${esc(status)}" title="${esc(codes)}">${esc(statusLabel(status))}${withReason ? " · " + esc(reasonLabel(reason)) : ""}</span>`;
}

/** The message [key] as HTML, its placeholders filled with the HTML of [html] (the rest of the text escaped). */
export function fillHtml(key: string, html: Record<string, string>) {
  const names = Object.keys(html);
  return fillMarks(esc(t(key, Object.fromEntries(names.map((name, i) => [name, `\u0001${i}\u0001`])))), names.map(name => html[name]));
}

/** Kinds whose body holds code: their body place reads "implemented". */
const CODE_KINDS = ["PROCEDURE", "FUNCTION", "PACKAGE", "TYPE", "TRIGGER", "FORMS_PROGRAM_UNIT"];

/**
 * A place of an entity as HTML: the half it is in (a link to the other half's page) and its line, linking to the file
 * (`Declared in the spec, pkg/a.pks, line 1`), or inside Forms code to the code page of its unit at its own line, named
 * by the unit (`Implemented in the body, PK_ACCORDION, line 1`); with [status] other than resolved, its badge. A
 * package body's own type is declared there, like its variables, not implemented.
 */
export function placeHtml(place: Place, entity: Pick<EntitySummary, "key" | "kind">, status: string | null) {
  const lineLink = `<a href="${lineHref(place.path, place.line, place.code)}" ` +
    `title="${esc(`${lineTitle(place.path, place.line, place.code)}:${place.col}`)}">${esc(t("entity.line", { line: place.code?.line ?? place.line }))}</a>`;
  // A path may break after each slash, so a long one wraps on a phone; a unit's name after an underscore or a dot.
  const where = place.code ? nameBreaksHtml(codeUnitName(place.code.key)) : place.file ? esc(place.path).replace(/\//g, "/<wbr>") : null;
  const line = where !== null ? fillHtml("entity.where.file", { path: `<span class="mono">${where}</span>`, line: lineLink }) : lineLink;
  const badge = status && status !== "RESOLVED" ? " " + statusBadge(status) : "";
  if (place.half === null) return line + badge;
  const word = esc(t(place.half === "spec" ? "entity.where.theSpec" : "entity.where.theBody"));
  const part = place.partner ? `<a class="half" href="${entityHref(place.partner.key)}">${word}</a>` : word;
  const implemented = CODE_KINDS.includes(entity.kind) && !isBodyOwn(entity);
  const message = place.half === "spec" ? "entity.where.declared" : implemented ? "entity.where.implemented" : "entity.where.declaredBody";
  return fillHtml(message, { part, line }) + badge;
}

/** A kind as a colored square with its letters, then its label; [compact] shows the square alone, the label as its tooltip. */
export function kindBadge(kind: string, compact = false) {
  const [letters, group] = kindMark(kind);
  const label = esc(kindLabel(kind));
  return `<span class="kind k-${group}${compact ? " only" : ""}" title="${label}"><i aria-hidden="true">${esc(letters)}</i><span>${label}</span></span>`;
}

/** The same badge as a DOM node. */
export function kindBadgeNode(kind: string, compact = false) {
  const holder = document.createElement("span");
  holder.innerHTML = kindBadge(kind, compact);
  return holder.firstElementChild as HTMLElement;
}
