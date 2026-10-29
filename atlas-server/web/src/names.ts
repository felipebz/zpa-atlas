// ---- entity names ---------------------------------------------------------------------------------------------------

/** A space after each comma of a parameter list (`(P_A IN NUMBER, P_B IN DATE)`), never inside a quoted identifier. */
export function spacedSignature(text: string) {
  let out = "", depth = 0, quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') quoted = !quoted;
    else if (!quoted && c === "(") depth++;
    else if (!quoted && c === ")") depth--;
    out += c;
    if (c === "," && !quoted && depth > 0 && text[i + 1] !== " ") out += " ";
  }
  return out;
}

/** About how many characters of parameter names a short signature shows. */
export const SHORT_PARAMETERS = 40;

/**
 * How much of a parameter list a name shows: `short`, names cut as in [shortParameterNames]; `names`, every name;
 * `types`, every name with its type, without the mode (`P_MSG VARCHAR2, P_VALUE BOOLEAN`).
 */
export type ParameterDetail = "short" | "names" | "types";

/**
 * Parameter [names] as a short list: names while they fit in [SHORT_PARAMETERS] characters (always the first), then
 * `…` and the count of the rest, kept together by a no-break space: `P_MSG, P_VALUE1, P_VALUE2, P_VALUE3, … +5`.
 */
export function shortParameterNames(names: string[]) {
  let shown = Math.min(1, names.length);
  let length = names[0]?.length ?? 0;
  while (shown < names.length && length + 2 + names[shown].length <= SHORT_PARAMETERS) length += 2 + names[shown++].length;
  const rest = names.length - shown;
  return names.slice(0, shown).join(", ") + (rest > 0 ? `, …\u00a0+${rest}` : "");
}

/** [params] as a list in [detail]. */
function parameterList(params: { name: string; type: string }[], detail: ParameterDetail) {
  if (detail === "types") return params.map(p => p.type ? `${p.name} ${p.type}` : p.name).join(", ");
  const names = params.map(p => p.name);
  return detail === "short" ? shortParameterNames(names) : names.join(", ");
}

/**
 * Every top-level `(...)` of [text] as its parameter list in [detail], outside quoted identifiers:
 * `PKG.PROC(P_A IN NUMBER, P_B IN DATE).` → `PKG.PROC(P_A, P_B).` with the default `names`.
 */
export function parameterNamesOnly(text: string, detail: ParameterDetail = "names") {
  let out = "", inside = "", depth = 0, quoted = false;
  for (const c of text) {
    if (c === '"') quoted = !quoted;
    if (!quoted && c === "(" && depth++ === 0) continue;
    if (!quoted && c === ")" && depth > 0 && --depth === 0) {
      out += "(" + parameterList(inside.trim() === "" ? [] : splitTopLevel(inside).map(parameterParts), detail) + ")";
      inside = "";
      continue;
    }
    if (depth > 0) inside += c;
    else out += c;
  }
  return out;
}

/** The parts of [text] split at its top-level commas, outside quoted identifiers and nested parentheses. */
export function splitTopLevel(text: string) {
  const items: string[] = [];
  let current = "", depth = 0, quoted = false;
  for (const c of text) {
    if (c === '"') quoted = !quoted;
    else if (!quoted && c === "(") depth++;
    else if (!quoted && c === ")") depth--;
    if (c === "," && !quoted && depth === 0) {
      items.push(current);
      current = "";
    } else {
      current += c;
    }
  }
  items.push(current);
  return items;
}

/** One parameter of a signature, `P_A IN OUT NUMBER` or `"p a" IN DATE`: its name, mode and type. */
export function parameterParts(item: string) {
  let rest = item.trim();
  let name: string;
  if (rest.startsWith('"')) {
    const close = rest.indexOf('"', 1);
    name = close < 0 ? rest : rest.slice(0, close + 1);
    rest = close < 0 ? "" : rest.slice(close + 1);
  } else {
    name = rest.split(/\s+/)[0];
    rest = rest.slice(name.length);
  }
  const tokens = rest.split(/\s+/).filter(Boolean);
  let mode: string | undefined = "";
  if (tokens[0] === "IN" && tokens[1] === "OUT") mode = tokens.splice(0, 2).join(" ");
  else if (tokens[0] === "IN" || tokens[0] === "OUT") mode = tokens.shift();
  return { name, mode, type: tokens.join(" ") };
}

/**
 * The parts of a [display] for reading, with the entity's own [name]:
 * - `qualifier`: before the name (`HR.PACK_PROCESS.`); `shortQualifier`: the same with parameter names only and
 *   without `@BODY` (`HR.PACK_PROCESS.RUN_JOB(P_A, P_B).`), `qualifierBody` telling whether it had one;
 * - `text`: the name with its signature (`RUN_JOB(P_A IN NUMBER, …)`, a key collision's `#2` kept);
 * - `base`: the name without its parameter list (`RUN_JOB#2`);
 * - `params`: the parameters (`{ name, mode, type }`), or null without a parameter list;
 * - `body`: whether it ends with `@BODY`, which is dropped from the text.
 * The name is looked for at a segment boundary only (a remote link may hold dots); without one, the whole display is
 * the text.
 */
export function displayParts(display: string, name?: string) {
  let text = display;
  let body = false;
  const bodyMatch = /@BODY(#\d+)?$/.exec(text);
  if (bodyMatch) {
    text = text.slice(0, bodyMatch.index) + (bodyMatch[1] ?? "");
    body = true;
  }
  let qualifier = "";
  if (name) {
    for (let at = text.lastIndexOf(name); at >= 0; at = at === 0 ? -1 : text.lastIndexOf(name, at - 1)) {
      const rest = text.slice(at + name.length);
      if ((at === 0 || text[at - 1] === ".") && (rest === "" || rest.startsWith("(") || rest.startsWith("#"))) {
        qualifier = text.slice(0, at);
        text = text.slice(at);
        break;
      }
    }
  }
  let base = text, params: ReturnType<typeof parameterParts>[] | null = null;
  const open = firstOpenParenthesis(text);
  if (open >= 0) {
    const close = matchingParenthesis(text, open);
    const inside = text.slice(open + 1, close);
    base = text.slice(0, open) + text.slice(close + 1);
    params = inside.trim() === "" ? [] : splitTopLevel(inside).map(parameterParts);
  }
  const qualifierBody = /@BODY(?=[#.]|$)/.test(qualifier);
  const shortQualifier = parameterNamesOnly(qualifier.replace(/@BODY(?=[#.]|$)/g, ""));
  return { qualifier, shortQualifier, qualifierBody, text: spacedSignature(text), base, params, body };
}

export function firstOpenParenthesis(text: string) {
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '"') quoted = !quoted;
    else if (!quoted && text[i] === "(") return i;
  }
  return -1;
}

export function matchingParenthesis(text: string, open: number) {
  let depth = 0, quoted = false;
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    if (c === '"') quoted = !quoted;
    else if (!quoted && c === "(") depth++;
    else if (!quoted && c === ")" && --depth === 0) return i;
  }
  return text.length;
}

/** The key that groups a spec with its body: the key without `@BODY`. */
export function parentKeyOf(key: string) {
  return key.replace(/@BODY(?=#\d+$|$)/, "");
}

/**
 * How a name is shown: [relative] leaves out the qualifier (the name sits under its parent); [within], an entity's
 * display, keeps only the part of the qualifier below that entity (none for the entity itself or its body);
 * [parameters] is how much of each parameter list it shows, `short` by default.
 */
export interface NameShape {
  relative?: boolean;
  within?: { display: string };
  parameters?: ParameterDetail;
}

/** An entity, as far as its shown name goes. */
export interface Named {
  key: string;
  display: string;
  name?: string;
}

/**
 * The pieces of a shown name: the `qualifier` (empty when left out), the `bare` name, its parameter list `params`
 * (`(P_A, P_B, … +3)`, null without one), and a key collision's `#2`, which stays after the list as in the display.
 */
export function shownName(display: string, name: string | undefined, shape?: NameShape) {
  const parts = displayParts(display, name);
  const detail = shape?.parameters ?? "short";
  const withoutBody = (text: string) => text.replace(/@BODY(?=[#.]|$)/g, "");
  const written = withoutBody(parts.qualifier);
  let qualifier = parameterNamesOnly(written, detail);
  if (shape?.within) {
    const base = withoutBody(shape.within.display);
    if (withoutBody(display) === base) qualifier = "";
    else if (written.startsWith(base + ".")) qualifier = parameterNamesOnly(written.slice(base.length + 1), detail);
  }
  if (shape?.relative) qualifier = "";
  const collision = /#\d+$/.exec(parts.base);
  const bare = collision ? parts.base.slice(0, collision.index) : parts.base;
  const params = parts.params === null ? null : "(" + parameterList(parts.params, detail) + ")";
  return { qualifier, bare, params, collision: collision?.[0] ?? "", parts };
}

/** A shown name as plain text. */
export function shownNameText(entity: Named, shape?: NameShape) {
  const shown = shownName(entity.display, entity.name, shape);
  return shown.qualifier + shown.bare + (shown.params ?? "") + shown.collision;
}

/**
 * The keys of the [entities] of one list whose name, shown as [shape] says, reads like another entity's (a spec and
 * its body count as one).
 */
export function readAlike(entities: Named[], shape?: NameShape) {
  const owners = new Map<string, Set<string>>();
  const texts = entities.map(entity => shownNameText(entity, shape));
  entities.forEach((entity, i) => {
    const keys = owners.get(texts[i]) ?? new Set<string>();
    keys.add(parentKeyOf(entity.key));
    owners.set(texts[i], keys);
  });
  return new Set(entities.filter((_, i) => owners.get(texts[i])!.size > 1).map(entity => entity.key));
}

/**
 * How much of its parameter lists each entity of one list shows, so that overloads stay apart: [base] (`short` by
 * default) where that reads apart, every name where names alone tell it from the others, and names with their types
 * where even every name reads the same. [shape] is the rest of how the list is read.
 */
export function listParameterDetail(entities: Named[], shape?: NameShape, base: ParameterDetail = "short") {
  const alike = readAlike(entities, { ...shape, parameters: base });
  const sameNames = readAlike(entities.filter(entity => alike.has(entity.key)), { ...shape, parameters: "names" });
  return (entity: Named): ParameterDetail => sameNames.has(entity.key) ? "types" : alike.has(entity.key) ? "names" : base;
}
