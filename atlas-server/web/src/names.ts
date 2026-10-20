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

/**
 * Every top-level `(...)` of [text] as its parameter names, outside quoted identifiers:
 * `PKG.PROC(P_A IN NUMBER, P_B IN DATE).` → `PKG.PROC(P_A, P_B).`, so overloads stay apart.
 */
export function parameterNamesOnly(text: string) {
  let out = "", inside = "", depth = 0, quoted = false;
  for (const c of text) {
    if (c === '"') quoted = !quoted;
    if (!quoted && c === "(" && depth++ === 0) continue;
    if (!quoted && c === ")" && depth > 0 && --depth === 0) {
      out += "(" + (inside.trim() === "" ? "" : splitTopLevel(inside).map(item => parameterParts(item).name).join(", ")) + ")";
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
