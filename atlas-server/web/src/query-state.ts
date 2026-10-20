import type { EntitySummary } from "./api";
import type { QueryParams } from "./query-params";

// What the query page holds: its form, its answer, and what changes as it runs.

export const form = document.getElementById("query") as HTMLFormElement;
export const answer = document.getElementById("answer")!;
/** A form control by name (`form.name` is the form's own name, not the input). */
export const field = (name: string) => form.elements.namedItem(name) as HTMLInputElement;

/** What the page changes as it runs, in one object: a module cannot reassign another module's `let`. */
interface PageState {
  /** The query that ran last, as page parameters; null before the first run. */
  current: QueryParams | null;
  /** The query the URL opened with, until an answer is cleared: the first Run takes its mode when nothing ran. */
  opened: QueryParams | null;
  /** Only the latest run fills the answer. */
  generation: number;
  /** The dimensions grouped last, offered again when the answer goes back from the list to groups. */
  lastGroupBy: string;
  /** The dimensions the mode bar offers, from the code lists. */
  dimensions: string[];
  /** A `name` that came in a URL beside an `entity`: shown under the picked entity, kept until removed. */
  keptName: string | null;
}

export const state: PageState = { current: null, opened: null, generation: 0, lastGroupBy: "ENTITY", dimensions: [], keptName: null };
/** The entity each suggest field has picked, by its parameter. */
export const picks: Record<string, EntitySummary | null> = { entity: null, container: null };
/** The text typed in each suggest field before it picked, put back when the pick is removed. */
export const typed: Record<string, string> = { entity: "", container: "" };
