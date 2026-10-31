import type {Declared, Member} from "./api";

/** What a header fact says: a cursor's return type, a type, a parameter's default, or an initial value. */
export type DeclaredLabel = "returns" | "type" | "default" | "initial";

/** One fact of a declaration as the page shows it. */
export interface DeclaredFact {
  label: DeclaredLabel;
  text: string;
  /** The text was cut: the whole one is in the file. */
  cut: boolean;
  /** The text is the spec's, which this body's parameter takes. */
  fromSpec: boolean;
  /** What the spec says instead, on a body that differs from it; null when they agree. */
  spec: string | null;
}

const ROUTINE_KINDS = ["PROCEDURE", "FUNCTION"];

/** A function's return type, for its signature line; null for anything else or when not written. */
export function returnType(kind: string, declared: Declared | null): string | null {
  return kind === "FUNCTION" ? declared?.type ?? null : null;
}

/**
 * The header facts of an entity of [kind]: a cursor's return type; else its type and its default (a parameter) or
 * initial value. A routine has none (its return type is on its signature line) except, on a body, the spec's return
 * type when it differs.
 */
export function declaredFacts(kind: string, declared: Declared | null): DeclaredFact[] {
  if (!declared) return [];
  const spec = declared.specText;
  const differs = (own: string | null, other: string | null | undefined) =>
    spec && (own ?? "").toLowerCase() !== (other ?? "").toLowerCase() ? other ?? "" : null;
  const facts: DeclaredFact[] = [];
  const typeSpec = differs(declared.type, spec?.type);
  if (ROUTINE_KINDS.includes(kind)) {
    if (typeSpec !== null) facts.push({ label: "returns", text: declared.type ?? "", cut: false, fromSpec: false, spec: typeSpec });
    return facts;
  }
  if (declared.type !== null) facts.push({ label: kind === "CURSOR" ? "returns" : "type", text: declared.type, cut: false, fromSpec: false, spec: typeSpec });
  if (declared.value !== null) {
    const fromSpec = declared.valueFrom === "SPEC";
    facts.push({
      label: kind === "PARAMETER" ? "default" : "initial", text: declared.value, cut: declared.valueCut, fromSpec,
      spec: fromSpec ? null : differs(declared.value, spec?.value)
    });
  }
  return facts;
}

/** The defaults of a routine's [params], in order, from its parameter children; null for the column when none has one. */
export function parameterDefaults(params: { name: string }[], children: Member[]): (DeclaredFact | null)[] | null {
  const byName = new Map(children.filter(m => m.entity.kind === "PARAMETER").map((m): [string, Member] => [m.entity.name, m]));
  const unquoted = (name: string) => name.startsWith('"') && name.endsWith('"') ? name.slice(1, -1) : name;
  const defaults = params.map(p => {
    const child = byName.get(p.name) ?? byName.get(unquoted(p.name));
    return declaredFacts("PARAMETER", child?.declared ?? null).find(f => f.label === "default") ?? null;
  });
  return defaults.some(d => d !== null) ? defaults : null;
}

/** The type shown beside a package variable or constant in the contents list; null for other members. */
export function memberType(member: Member): string | null {
  return ["PACKAGE_VARIABLE", "PACKAGE_CONSTANT"].includes(member.entity.kind) ? member.declared?.type ?? null : null;
}
