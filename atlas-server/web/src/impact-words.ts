// The impact page in words, without the DOM: the summary's sentences, a trigger's note and a line of a path. Each
// takes the translator ([Say]) so that it reads the same from the page and from a test.

import type {EntitySummary} from "./api";
import type {Params, Say} from "./i18n-format";
import type {Hop, ImpactTrigger, ImpactView} from "./impact-model";
import type {NameShape} from "./names";
import {shownNameText} from "./names";

/** A message of the catalog with its values: what the page fills in, some values as links. */
export interface Phrase {
  key: string;
  params: Params;
}

/**
 * The summary's first sentence: how many objects may be affected and how many of them for certain. `count` picks the
 * plural; `total` and `certain` are the numbers shown (the page puts links there).
 */
export function summaryPhrase(total: number, certain: number): Phrase {
  const key = certain === total ? "impact.summary.all" : certain === 0 ? "impact.summary.none" : "impact.summary.some";
  return { key, params: { count: total, total, certain } };
}

/**
 * How far the objects are: how many use the entity directly, how many through other code, and the longest path in
 * steps. [levels] counts the objects at each depth, the first being those that use it directly.
 */
export function reachPhrase(levels: number[]): Phrase {
  const direct = levels[0] ?? 0;
  const through = levels.slice(1).reduce((sum, n) => sum + n, 0);
  return through === 0
    ? { key: "impact.reach.direct", params: { count: direct, direct } }
    : { key: "impact.reach.both", params: { count: through, direct, through, steps: levels.length } };
}

/** How far one object is, in words: `directly`, `2 steps away`. */
export const distanceText = (say: Say, depth: number) => say("impact.distance", { count: depth });

/** A name without its schema (`PKG.PROC(…)` of `HR.PKG.PROC(…)`), or within [owner] when the list already names it. */
export function nameShape(entity: EntitySummary, owner?: EntitySummary | null): NameShape {
  if (owner) return { within: owner };
  const dot = entity.display.indexOf(".");
  return dot > 0 ? { within: { display: entity.display.slice(0, dot) } } : {};
}

/**
 * A trigger's note, in the words of the entity page's trigger table: `instead of insert, for each row, on V_COMPANY`;
 * several events are joined by `; `.
 */
export function triggerNote(say: Say, trigger: ImpactTrigger) {
  const events = trigger.events.map(event => {
    const what = [say("trigger.timing." + event.timing), event.event.toLowerCase()].join(" ") +
      (event.column ? " " + say("trigger.of") + " " + event.column.name : "");
    return event.level ? what + ", " + say("trigger.level." + event.level) : what;
  });
  return say("impact.triggerOn", { events: events.join("; ") || "—", table: trigger.table?.name ?? "—" });
}

/**
 * One line of the path under "Why?", as text pieces: the name (short), its place (`line 7`, in [path]), the status in
 * words when not resolved, and what the hop fires. The page links the name and the place.
 */
export function pathLine(say: Say, view: ImpactView, hop: Hop) {
  const node = view.nodes[hop.node];
  const entity = node.entity !== null ? view.entities[node.entity] : null;
  return {
    entity,
    name: entity ? shownNameText(entity, nameShape(entity)) : view.paths[node.path!],
    place: say("entity.line", { line: hop.line }),
    path: hop.path,
    line: hop.line,
    status: hop.status === "RESOLVED" ? null : say("status." + hop.status),
    fires: hop.fires ? say("impact.firesOn", { events: hop.fires.toLowerCase() }) : null
  };
}

/** The last line of a path: what it reaches, and whether that is the object the page starts from. */
export const pathEnd = (say: Say, view: ImpactView, target: EntitySummary) =>
  say(target.key === view.entity.key ? "impact.path.entity" : "impact.path.matched");
