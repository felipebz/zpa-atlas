// A column's places (where PL/SQL reads its value into a target) in words, without the DOM. Each function that says
// something takes the translator ([Say]) so that it reads the same from the page and from a test.

import type {ColumnPlace, Count, EntitySummary, PlaceClass} from "./api";
import type {Say} from "./i18n-format";

/** The classes of a place in the order the page shows them. */
export const PLACE_CLASSES: readonly PlaceClass[] = ["FIXED_SIZE", "UNKNOWN", "CALLER_DECIDES", "ANCHORED_ELSEWHERE", "NO_SIZE", "ANCHORED_TO_COLUMN"];

/**
 * Whether a place's badge stands out: its target has a size of its own (FIXED_SIZE), the index could not tell how it
 * is declared (UNKNOWN), or each caller's argument decides it (CALLER_DECIDES).
 */
export function needsAttention(placeClass: string) {
  return placeClass === "FIXED_SIZE" || placeClass === "UNKNOWN" || placeClass === "CALLER_DECIDES";
}

/** The catalog's text for [key], else [fallback]: a stored code the catalog does not know reads as the code. */
function sayOr(say: Say, key: string, fallback: string) {
  const text = say(key);
  return text === key ? fallback : text;
}

/** A class in words (`fixed size`); an unknown class as its code. */
export const classLabel = (say: Say, placeClass: string) => sayOr(say, "places.class." + placeClass, placeClass);

/** What a class means for the column growing, for a tooltip; an unknown class as its code. */
export const classTitle = (say: Say, placeClass: string) => sayOr(say, "places.classTitle." + placeClass, placeClass);

/** Why the index could not tell a target's declaration, in words; a reason the catalog does not know as its code. */
export const reasonText = (say: Say, reason: string) => sayOr(say, "places.reason." + reason, reason);

/** How the value is read, as SQL writes it: `SELECT INTO`, `FETCH`, `RETURNING INTO`, with `BULK COLLECT`. */
export function sourceText(say: Say, place: Pick<ColumnPlace, "source" | "bulk">) {
  const source = sayOr(say, "places.source." + place.source, place.source);
  return place.bulk ? say("places.bulk", { source }) : source;
}

/**
 * A written size in words: `12 CHAR`, `20 BYTE`, `10 digits`; a number with its scale, `8 digits before the point,
 * scale 2`; with no unit written (`NLS_LENGTH_SEMANTICS`, or none given) the length alone, which [sizeNote] explains.
 * Null when the length is not known.
 */
export function sizeText(say: Say, place: Pick<ColumnPlace, "length" | "unit" | "scale">): string | null {
  if (place.length === null) return null;
  if (place.unit === "DIGITS" && place.scale !== null) {
    return say("places.size.NUMBER", { count: place.length, length: place.length, scale: place.scale });
  }
  const unit = place.unit === "CHAR" || place.unit === "BYTE" || place.unit === "DIGITS" ? place.unit : "NONE";
  return say("places.size." + unit, { count: place.length, length: place.length });
}

/** What an `OUT` or `IN OUT` parameter target leaves to its callers, in words; null for any other target. */
export function parameterText(say: Say, place: Pick<ColumnPlace, "parameterMode">): string | null {
  return place.parameterMode === null ? null : say("places.parameter", { mode: place.parameterMode });
}

/** The value functions a place reads its column through, outer first, in words (`through UPPER(TRIM(…))`); null when read directly. */
export function functionsText(say: Say, place: Pick<ColumnPlace, "functions">): string | null {
  if (place.functions.length === 0) return null;
  const functions = place.functions.map(f => f + "(").join("") + "…" + ")".repeat(place.functions.length);
  // NVL, NVL2 and COALESCE may give back another argument; the others give back the column's value, or it trimmed or in another case.
  const other = place.functions.some(f => f === "NVL" || f === "NVL2" || f === "COALESCE");
  return say(other ? "places.functionsOther" : "places.functions", { functions });
}

/** How many calls pass a column to a parameter, before the one shown (`by 3 calls, one at`); null for other sources. */
export function callsText(say: Say, place: Pick<ColumnPlace, "callCount">): string | null {
  return place.callCount === null ? null : say("places.calls", { count: place.callCount });
}

/** What a size leaves unsaid: with no unit written, the compile-time `NLS_LENGTH_SEMANTICS` decides it; else null. */
export function sizeNote(say: Say, place: Pick<ColumnPlace, "length" | "unit">): string | null {
  return place.length !== null && place.unit === "NLS_LENGTH_SEMANTICS" ? say("places.size.nlsNote") : null;
}

/**
 * The entity whose declaration decides a target's type, when that is not the target itself, with how the row says it:
 * a column an anchor ends at (`anchored to SHOP.ORDERS.NOTE`), the declaration that writes a fixed size (`size written
 * in PK_ORD.T_CODE`), else the one that declares the type. Null when there is none, or it is the target, or it is the
 * very column read (anchored to it: nothing to add).
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

/** The places counted by class, in the page's order (a class the page does not know last), each with its words. */
export function classCounts(say: Say, byClass: Count[]): { value: string; count: number; text: string }[] {
  const rank = (value: string) => {
    const index = PLACE_CLASSES.indexOf(value as PlaceClass);
    return index < 0 ? PLACE_CLASSES.length : index;
  };
  return byClass.filter(c => c.count > 0).sort((a, b) => rank(a.value) - rank(b.value))
    .map(c => ({ value: c.value, count: c.count, text: say("places.count", { count: c.count, label: classLabel(say, c.value) }) }));
}

/** The counts by class as one line: `2 fixed size, 1 unknown, 5 anchored to the column`. */
export const classSummary = (say: Say, byClass: Count[]) => classCounts(say, byClass).map(c => c.text).join(", ");

/** How many places the counts add up to. */
export const placesTotal = (byClass: Count[]) => byClass.reduce((sum, c) => sum + c.count, 0);
