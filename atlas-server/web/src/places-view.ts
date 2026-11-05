// A column's places (where PL/SQL reads its value into a target) in words, without the DOM. Each function that says
// something takes the translator ([Say]) so that it reads the same from the page and from a test.

import type {CodeAt, ColumnPlace, Count, EntitySummary, PlaceGroup, PlaceGroupsPage, PlaceSource, SizeUnit, Verdict, VerdictCount} from "./api";
import {codeUnitName} from "./forms-view";
import type {Params, Say} from "./i18n-format";

/** The verdicts in the order the page shows their buttons (OWN_SIZE only comes when the size compared is not known). */
export const VERDICTS: readonly Verdict[] = ["SMALLER", "CALLER_DECIDES", "UNKNOWN", "FITS", "FOLLOWS", "OWN_SIZE"];

/** The verdicts listed until the reader says otherwise: all but the targets that follow the column. */
export const DEFAULT_VERDICTS: readonly Verdict[] = VERDICTS.filter(v => v !== "FOLLOWS");

/** The ways a value arrives, in the order the filter lists them. */
export const PLACE_SOURCES: readonly PlaceSource[] = ["SELECT_INTO", "FETCH", "RETURNING", "ASSIGNMENT", "IN_ARGUMENT", "OUT_ARGUMENT", "RETURNED"];

/** The catalog's text for [key] with [params], else [fallback]: a stored code the catalog does not know reads as the code. */
function sayOr(say: Say, key: string, fallback: string, params?: Params) {
  const text = say(key, params);
  return text === key ? fallback : text;
}

/** What a class means for the column growing, for a tooltip; an unknown class as its code. */
export const classTitle = (say: Say, placeClass: string) => sayOr(say, "places.classTitle." + placeClass, placeClass);

/** Why the index could not tell a target's declaration, in words; a reason the catalog does not know as its code. */
export const reasonText = (say: Say, reason: string) => sayOr(say, "places.reason." + reason, reason);

/** Why a target's size does not compare with the column's, in words; a reason the catalog does not know as its code. */
export const verdictReasonText = (say: Say, reason: string) => sayOr(say, "places.verdictReason." + reason, reason);

/** A verdict's button: `smaller than 10`, `caller decides`; [size] is the S compared (`?` when not known). */
export const verdictLabel = (say: Say, verdict: string, size: number | null) =>
  sayOr(say, "places.verdict." + verdict, verdict, { size: size ?? "?" });

/** The class whose meaning a verdict's button says as its tooltip. */
const VERDICT_CLASSES: Record<Verdict, string> = {
  SMALLER: "FIXED_SIZE", CALLER_DECIDES: "CALLER_DECIDES", UNKNOWN: "UNKNOWN", FITS: "FIXED_SIZE", FOLLOWS: "ANCHORED_TO_COLUMN", OWN_SIZE: "FIXED_SIZE"
};

/** What a verdict means, for its button's tooltip: SMALLER and FITS their own words with [size], the others their class's. */
export function verdictTitle(say: Say, verdict: string, size: number | null) {
  if (verdict === "SMALLER" || verdict === "FITS") return say("places.verdictTitle." + verdict, { size: size ?? "?" });
  const placeClass = VERDICT_CLASSES[verdict as Verdict];
  return placeClass === undefined ? verdict : classTitle(say, placeClass);
}

/** The verdict buttons in the page's order (a verdict the page does not know last, as its code), each with its words. */
export function verdictButtons(say: Say, counts: VerdictCount[], size: number | null) {
  const rank = (name: string) => {
    const index = VERDICTS.indexOf(name as Verdict);
    return index < 0 ? VERDICTS.length : index;
  };
  return [...counts].sort((a, b) => rank(a.name) - rank(b.name))
    .map(c => ({ verdict: c.name, count: c.count, label: verdictLabel(say, c.name, size), title: verdictTitle(say, c.name, size) }));
}

/** How the value is read, as SQL writes it: `SELECT INTO`, `FETCH`, `RETURNING INTO`, with `BULK COLLECT`; the filter's words for the others. */
export function sourceText(say: Say, place: Pick<ColumnPlace, "source" | "bulk">) {
  const source = sayOr(say, "places.source." + place.source, place.source);
  return place.bulk ? say("places.bulk", { source }) : source;
}

/**
 * How the value arrives at a place, in a few words: as SQL writes the statement (`FETCH · BULK COLLECT`), else the
 * parameter it is passed to and how many calls do (`in P_CODE, from 3 calls`), the routine whose `OUT` parameter takes
 * it (`OUT of LOAD`), or the function whose result it is (`result of GET_CODE`).
 */
export function howText(say: Say, place: Pick<ColumnPlace, "source" | "bulk" | "argumentOf" | "routine" | "callCount">) {
  if (place.source === "IN_ARGUMENT" && place.argumentOf !== null) {
    const parameter = place.argumentOf.name;
    return place.callCount === null ? say("places.how.inParameter", { parameter }) : say("places.how.IN_ARGUMENT", { parameter, count: place.callCount });
  }
  if (place.source === "OUT_ARGUMENT" && place.routine !== null) return say("places.how.OUT_ARGUMENT", { routine: place.routine.name });
  if (place.source === "RETURNED" && place.routine !== null) return say("places.how.RETURNED", { function: place.routine.name });
  return sourceText(say, place);
}

/** The value functions as one short code (`UPPER·TRIM`), outer first; null when read directly. */
export const functionsCode = (place: Pick<ColumnPlace, "functions">) => place.functions.length === 0 ? null : place.functions.join("·");

/**
 * A size in words: `12 CHAR`, `20 BYTE`, `10 digits`; a number with its scale, `8 digits before the point, scale 2`; a
 * length without a unit alone. Null when the length is not known.
 */
export function sizeText(say: Say, place: { length: number | null; unit: SizeUnit | null; scale: number | null }): string | null {
  if (place.length === null) return null;
  if (place.unit === "DIGITS" && place.scale !== null) {
    return say("places.size.NUMBER", { count: place.length, length: place.length, scale: place.scale });
  }
  return say("places.size." + (place.unit ?? "NONE"), { count: place.length, length: place.length });
}

/**
 * What a row shows of a place's size against the size compared [size] (S): `%TYPE` for a target that follows the
 * column, its mode for an `OUT` parameter, `?` when not known; else the size compared, with its unit only when it is
 * not the column's ([columnUnit]), and how much of S it holds (0 to 1, full at S or more; null when S is not known or
 * the target has no size). A target with no size of its own reads `—`.
 */
export function sizeShown(say: Say, place: Pick<ColumnPlace, "verdict" | "parameterMode" | "size" | "sizeUnit">, size: number | null,
                          columnUnit: SizeUnit | null): { text: string; fill: number | null } {
  if (place.verdict === "FOLLOWS") return { text: "%TYPE", fill: null };
  if (place.verdict === "CALLER_DECIDES") return { text: place.parameterMode ?? "OUT", fill: null };
  if (place.verdict === "UNKNOWN") return { text: "?", fill: null };
  if (place.size === null) return { text: "—", fill: null };
  const text = place.sizeUnit !== null && place.sizeUnit !== columnUnit
    ? sizeText(say, { length: place.size, unit: place.sizeUnit, scale: null })!
    : say("places.size.NONE", { length: place.size });
  return { text, fill: size === null || size <= 0 ? null : Math.min(1, Math.max(0, place.size / size)) };
}

/** The unit a column's size counts in, for "Compare with [ ] bytes"; empty when the column has none. */
export const compareUnitText = (say: Say, unit: SizeUnit | null) => unit === null ? "" : say("places.compareUnit." + unit);

/**
 * What the size compared is, beside its field: the column's today, the one typed (`if the column becomes 20`) with a
 * way back to the column's (`back to 10`, null when the column's is not known), or that the column's is not known.
 */
export function compareNote(say: Say, page: Pick<PlaceGroupsPage, "column" | "size" | "sizeGiven">): { text: string; back: string | null } {
  if (page.sizeGiven) {
    return {
      text: say("places.compare.if", { size: page.size }),
      back: page.column.length === null ? null : say("places.compare.back", { size: page.column.length })
    };
  }
  return { text: say(page.column.length === null ? "places.compare.unknown" : "places.compare.today"), back: null };
}

/** How many sizes took the index's default unit for want of a written one; null when none did. */
export function optionSizedText(say: Say, page: Pick<PlaceGroupsPage, "optionSized" | "optionUnit">): string | null {
  return page.optionSized > 0 ? say("places.optionSized", { count: page.optionSized, unit: page.optionUnit }) : null;
}

/** How many places and routines the list shows of those the filters but the verdicts leave: `3 of 8 places, in 2 of 5 routines`. */
export function shownText(say: Say, page: Pick<PlaceGroupsPage, "places" | "totalPlaces" | "routines" | "totalRoutines">) {
  return say("places.shown", {
    places: say("places.shown.places", { places: page.places, count: page.totalPlaces }),
    routines: say("places.shown.routines", { groups: page.routines, count: page.totalRoutines })
  });
}

/** A group's counts, for its header: `1 smaller · 3 places`, the places alone when none is smaller. */
export const groupCountsText = (say: Say, group: Pick<PlaceGroup, "smaller" | "places">) =>
  (group.smaller === 0 ? "" : say("places.group.smaller", { count: group.smaller }) + " · ") + say("places.group.places", { count: group.places });

/** The button that shows the next routines, at most [limit] of the [left] still to show. */
export const moreText = (say: Say, left: number, limit: number) => say("places.more", { count: Math.min(limit, left), left });

/** The last segment of a path: the file's name. */
export const fileName = (path: string) => path.slice(Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\")) + 1);

/** The unit a setting was compiled in, as the reader knows it: `PK_LV body` for a package or type body, else its name. */
export function compiledUnitName(say: Say, unit: { name: string; type: string }) {
  return unit.type.endsWith(" BODY") ? say("places.unitName.body", { name: unit.name }) : unit.name;
}

/**
 * Where a size's unit came from when none is written: the setting its unit was compiled with (`no unit written: CHAR,
 * as PK_LV body was compiled`) or the index's default; null when the unit is written or the size has none.
 */
export function unitSourceText(say: Say, place: Pick<ColumnPlace, "unitSource" | "unitCompiledIn" | "unit" | "sizeUnit">): string | null {
  const unit = place.sizeUnit ?? place.unit;
  if (unit === null) return null;
  if (place.unitSource === "OPTION") return say("places.unit.OPTION", { unit });
  if (place.unitSource !== "COMPILED") return null;
  return place.unitCompiledIn === null
    ? say("places.unit.compiled", { unit })
    : say("places.unit.COMPILED", { unit, unitName: compiledUnitName(say, place.unitCompiledIn) });
}

/** The room a concatenated place that fits leaves for the rest of the text (`4 left for the rest of the text`); else null. */
export function roomText(say: Say, place: Pick<ColumnPlace, "concatenated" | "room">): string | null {
  return place.concatenated && place.room !== null ? say("places.room", { count: place.room }) : null;
}

/** What an `OUT` or `IN OUT` parameter target leaves to its callers, in words; null for any other target. */
export function parameterText(say: Say, place: Pick<ColumnPlace, "parameterMode">): string | null {
  return place.parameterMode === null ? null : say("places.parameter", { mode: place.parameterMode });
}

/** That the column's value was concatenated into the target with other text; null when it lands alone. */
export function concatenatedText(say: Say, place: Pick<ColumnPlace, "concatenated">): string | null {
  return place.concatenated ? say("places.concatenated") : null;
}

/** The value functions a place reads its column through, outer first, in words (`through UPPER(TRIM(…))`); null when read directly. */
export function functionsText(say: Say, place: Pick<ColumnPlace, "functions">): string | null {
  if (place.functions.length === 0) return null;
  const functions = place.functions.map(f => f + "(").join("") + "…" + ")".repeat(place.functions.length);
  // NVL, NVL2 and COALESCE may give back another argument; REPLACE and REGEXP_REPLACE take text out or put it in; the
  // others give back the column's value, or it trimmed or in another case.
  const other = place.functions.some(f => f === "NVL" || f === "NVL2" || f === "COALESCE");
  const length = place.functions.some(f => f === "REPLACE" || f === "REGEXP_REPLACE");
  const key = other ? (length ? "places.functionsOtherLength" : "places.functionsOther") : (length ? "places.functionsLength" : "places.functions");
  return say(key, { functions });
}

/** How many calls pass a column to a parameter, before the one shown (`by 3 calls, one at`); null for other sources. */
export function callsText(say: Say, place: Pick<ColumnPlace, "callCount">): string | null {
  return place.callCount === null ? null : say("places.calls", { count: place.callCount });
}

/**
 * The entity whose declaration decides a target's type, when that is not the target itself, with how the detail says
 * it: a column an anchor ends at (`anchored to SHOP.ORDERS.NOTE`), the declaration that writes a fixed size (`size
 * written in PK_ORD.T_CODE`), else the one that declares the type. Null when there is none, or it is the target, or it
 * is the very column read (anchored to it: nothing to add).
 */
export function typeSourcePhrase(place: Pick<ColumnPlace, "typeSource" | "target" | "read" | "placeClass">): { key: string; entity: EntitySummary } | null {
  const source = place.typeSource;
  if (source === null || source.key === place.target?.key || source.key === place.read.key) return null;
  const key = source.kind === "COLUMN" ? "places.typeSource.anchor" : place.placeClass === "FIXED_SIZE" ? "places.typeSource.size" : "places.typeSource.type";
  return { key, entity: source };
}

/**
 * The view a view column belongs to, from its display and key (`SHOP.V_ORDERS.CODE` → `SHOP.V_ORDERS`), and the
 * column's name; [viewKey] is null when the key does not end with the column's name.
 */
export function viewOf(read: EntitySummary): { view: string; viewKey: string | null; column: string } {
  const suffix = "." + read.name;
  const view = read.display.endsWith(suffix) ? read.display.slice(0, -suffix.length) : read.display;
  const viewKey = read.key.endsWith(suffix) ? read.key.slice(0, -suffix.length) : null;
  return { view, viewKey, column: read.name };
}

/** How many places the counts add up to. */
export const placesTotal = (byClass: Count[]) => byClass.reduce((sum, c) => sum + c.count, 0);

/**
 * A piece of a row detail's line: text, code, a word with a hint as its tooltip, a short link to an entity, a file's
 * line (inside Forms code, [codeAt] the unit and its own line), a view.
 */
export type DetailPart =
  | { text: string }
  | { code: string }
  | { hint: string; title: string }
  | { entity: EntitySummary; label: string }
  | { path: string; line: number; codeAt: CodeAt | null }
  | { view: EntitySummary };

/** A line of a row's detail, with its class (`muted`, `uses-gap`) and the code it says, for a tooltip. */
export interface DetailLine {
  parts: DetailPart[];
  className?: string;
  title?: string;
}

/** Whether a declared type writes its length (`varchar2(5)`, `number(6,2)`), not only a name (`t_codes`, `orders.code%type`). */
const writesLength = (declared: string | null) => declared !== null && /\(\s*[\d*]/.test(declared);

/**
 * The short name an entity is known by in a sentence: a column with its relation (`ORDERS.LABEL`), anything else by its
 * own name (`CODE_OF`), never with its schema, its parameters or its body.
 */
export function shortName(entity: EntitySummary): string {
  if (entity.kind !== "COLUMN") return entity.name;
  const parts = entity.display.split(".");
  return parts.length >= 2 ? parts.slice(-2).join(".") : entity.name;
}

/** A short link to [entity]. */
const link = (entity: EntitySummary): DetailPart => ({ entity, label: shortName(entity) });

/**
 * [key]'s message with [parts] in its placeholders: the text between them as text parts. The message is asked with
 * markers in place of the placeholders, so a translation may put them in any order.
 */
function fill(say: Say, key: string, parts: Record<string, DetailPart>, params: Params = {}): DetailPart[] {
  const names = Object.keys(parts);
  const text = say(key, { ...params, ...Object.fromEntries(names.map((name, i) => [name, `\u0001${i}\u0001`])) });
  return text.split("\u0001").map((piece, i) => i % 2 === 0 ? { text: piece } : parts[names[Number(piece)]]).filter(p => !("text" in p) || p.text !== "");
}

/**
 * The declared type as written, and what it does not say: nothing more when it writes its length and unit; the unit
 * (`· BYTE`) when it writes no unit; the size (`· 5 BYTE`) when it writes no length. An unwritten unit carries where it
 * came from as its hint.
 */
function declaredLine(say: Say, p: ColumnPlace): DetailLine | null {
  const length = p.length ?? p.size;
  const unit = p.length !== null ? p.unit : p.sizeUnit;
  const hint = unitSourceText(say, p);
  const unitPart = (u: string): DetailPart => hint === null ? { text: u } : { hint: u, title: hint };
  const parts: DetailPart[] = [{ text: say("places.declaredType") }];
  if (p.declaredType !== null) parts.push({ text: " " }, { code: p.declaredType });
  if (writesLength(p.declaredType)) {
    if (hint !== null && unit !== null) parts.push({ text: " · " }, unitPart(unit));
  } else if (length !== null) {
    if (unit === "BYTE" || unit === "CHAR") parts.push({ text: ` · ${length} ` }, unitPart(unit));
    else parts.push({ text: " · " + sizeText(say, { length, unit, scale: p.length !== null ? p.scale : null }) });
  }
  return parts.length === 1 ? null : { parts };
}

/** Whether the line a row shows is another than its target's: then the detail gives the target's. */
const showsOtherLine = (p: Pick<ColumnPlace, "path" | "line" | "shown">) => p.shown !== null && (p.shown.path !== p.path || p.shown.line !== p.line);

/**
 * Where a row's line link goes: the line it shows (for a parameter, the call that passes the column; else where its
 * statement reads the column), else the target's; with the file when not the group's. Inside Forms code ([code]),
 * the unit's own line, with the unit's name when it is another than the target's (a call elsewhere in the module).
 */
export function rowAt(say: Say, p: Pick<ColumnPlace, "path" | "line" | "shown" | "code">, groupPath: string) {
  const path = p.shown?.path ?? p.path;
  const line = p.shown?.line ?? p.line;
  const code = (p.shown !== null ? p.shown.code : p.code) ?? null;
  const shownLine = code?.line ?? line;
  const elsewhere = path !== groupPath || (code !== null && code.key !== p.code?.key);
  const text = !elsewhere ? say("places.at", { line: shownLine })
    : say("places.atFile", { file: code ? codeUnitName(code.key) : fileName(path), line: shownLine });
  return { path, line, code, text };
}

/**
 * What a place's row leaves out, line by line: the declared type (with an unwritten unit or length), the declaration
 * that decides it, the functions, the concatenation and the room it leaves, the function whose result it is, the OUT
 * parameter it is passed for, the parameter it receives and the call that passes the column, what an OUT parameter
 * leaves to its callers, why it is not known, the view read through, and the target's file and line. The group's
 * routine is not said again.
 */
export function placeDetail(say: Say, p: ColumnPlace): DetailLine[] {
  const lines: DetailLine[] = [];
  const declared = declaredLine(say, p);
  if (declared !== null) lines.push(declared);
  const source = typeSourcePhrase(p);
  if (source !== null) lines.push({ parts: fill(say, source.key, { source: link(source.entity) }) });
  const functions = functionsText(say, p);
  if (functions !== null) lines.push({ parts: [{ text: functions }] });
  if (p.concatenated) {
    const room = roomText(say, p);
    lines.push({ parts: [{ text: concatenatedText(say, p)! + (room === null ? "" : "; " + room) }] });
  }
  if (p.source === "RETURNED" && p.routine !== null) {
    lines.push({ parts: fill(say, "places.returnedBy", { routine: link(p.routine) }) });
    // The call on the row's own line says nothing more.
    if (p.callCount !== null && p.callPath !== null && p.callLine !== null && !(p.callPath === p.path && p.callLine === p.line)) {
      lines.push({ parts: [{ text: callsText(say, p)! + " " }, { path: p.callPath, line: p.callLine, codeAt: p.callCode ?? null }], className: "muted" });
    }
  }
  if (p.source === "OUT_ARGUMENT" && p.argumentOf !== null && p.routine !== null) {
    lines.push({ parts: fill(say, "places.argumentOf", { parameter: { entity: p.argumentOf, label: p.argumentOf.name }, routine: link(p.routine) }) });
  }
  const call = p.source === "IN_ARGUMENT" && p.callPath !== null && p.callLine !== null;
  if (p.source === "IN_ARGUMENT" && p.argumentOf !== null) {
    const parameter: DetailPart = { entity: p.argumentOf, label: p.argumentOf.name };
    lines.push({ parts: p.callCount === null || !call ? fill(say, "places.receivesParameter", { parameter })
      : [...fill(say, "places.receives", { parameter }, { count: p.callCount }), { text: " " }, { path: p.callPath!, line: p.callLine!, codeAt: p.callCode ?? null }] });
  }
  const mode = parameterText(say, p);
  if (mode !== null) lines.push({ parts: [{ text: mode }], className: "uses-gap" });
  if (p.reason !== null) lines.push({ parts: [{ text: reasonText(say, p.reason) }], className: "uses-gap", title: p.reason });
  if (p.verdictReason !== null) lines.push({ parts: [{ text: verdictReasonText(say, p.verdictReason) }], className: "uses-gap", title: p.verdictReason });
  if (p.viaView) lines.push({ parts: [{ view: p.read }], className: "muted" });
  // On a phone the group's header has no file: the target's file and line, said to be the target's when the row shows another line.
  const at: DetailPart = { path: p.path, line: p.line, codeAt: p.code ?? null };
  lines.push({ parts: showsOtherLine(p) ? [{ text: say("places.target") + " " }, at] : [at], className: "muted" });
  return lines;
}
