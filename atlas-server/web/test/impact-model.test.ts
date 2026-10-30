import { describe, expect, it } from "vitest";
import type { EntitySummary } from "../src/api";
import { buildTree, csvField, csvText, groupByDepth, groupByOwner, pathOf, pathText, readState, shownNodes, stateSearch, viewOf } from "../src/impact-model";
import type { ImpactView, ImpactViewNode, TreeItem } from "../src/impact-model";

const entity = (key: string, kind: string): EntitySummary => ({ key, kind, display: key.slice(3), external: false, name: key.split(".").pop()!.split("(")[0] });

/**
 * A small graph, as `/api/impact/graph` answers it: LEAF is used by MID (in PACK_A) and by the function FN (standalone,
 * schema HR); TOP (in PACK_B) calls MID; the trigger TRG fires on an INSERT that TOP_INS does; a script calls MID.
 */
const entities = [
  entity("db:HR.PACK_A.LEAF(P IN NUMBER)", "PROCEDURE"), // 0, the entity
  entity("db:HR.PACK_A.MID(P IN NUMBER)", "PROCEDURE"), // 1
  entity("db:HR.FN(P IN NUMBER)", "FUNCTION"), // 2
  entity("db:HR.PACK_B.TOP()", "PROCEDURE"), // 3
  entity("db:HR.TRG", "TRIGGER"), // 4
  entity("db:HR.PACK_A", "PACKAGE"), // 5
  entity("db:HR.PACK_B", "PACKAGE"), // 6
  entity("db:APP.TOP_INS()", "PROCEDURE") // 7
];
function node(fields: Partial<ImpactViewNode> & Pick<ImpactViewNode, "depth" | "stop">): ImpactViewNode {
  return { kind: "PROCEDURE", entity: null, path: null, uses: null, firstLine: null, certain: true, owner: null, schema: "HR", trigger: null,
    via: { node: null, uses: 0, path: 0, line: 1, status: "RESOLVED", fires: null }, ...fields };
}
const view: ImpactView = {
  entity: entities[0], included: ["IMPLEMENTATIONS"], maxDepth: 10, followTriggers: true, levels: [2, 3], stops: [], certain: 4, entities,
  paths: ["hr/pack_a.pkb", "hr/fn.fun", "hr/pack_b.pkb", "hr/trg.trg", "scripts/run.sql", "app/top_ins.prc"],
  nodes: [
    node({ entity: 2, kind: "FUNCTION", depth: 1, stop: "ENTRY_POINT", via: { node: null, uses: 0, path: 1, line: 3, status: "RESOLVED", fires: null } }),
    node({ entity: 1, depth: 1, stop: "FOLLOWED", owner: 5, via: { node: null, uses: 0, path: 0, line: 11, status: "RESOLVED", fires: null } }),
    node({ entity: 3, depth: 2, stop: "ENTRY_POINT", owner: 6, via: { node: 1, uses: 1, path: 2, line: 10, status: "UNVERIFIED", fires: null } }),
    node({ entity: 4, kind: "TRIGGER", depth: 2, stop: "FOLLOWED", via: { node: 1, uses: 1, path: 3, line: 5, status: "RESOLVED", fires: null } }),
    node({ path: 4, kind: "FILE", schema: null, uses: 1, firstLine: 2, depth: 2, stop: "NO_CONTAINER", via: { node: 1, uses: 1, path: 4, line: 2, status: "RESOLVED", fires: null } }),
    node({ entity: 7, depth: 3, stop: "ENTRY_POINT", certain: false, schema: "APP", via: { node: 3, uses: 4, path: 5, line: 4, status: "RESOLVED", fires: "INSERT" } })
  ]
};

describe("pathOf", () => {
  it("follows each node's use back to the entity, the node first", () => {
    const { hops, target } = pathOf(view, 5);
    expect(hops.map(hop => `${hop.node} ${hop.path}:${hop.line} ${hop.status} ${hop.fires ?? "-"}`)).toEqual([
      "5 app/top_ins.prc:4 RESOLVED INSERT", "3 hr/trg.trg:5 RESOLVED -", "1 hr/pack_a.pkb:11 RESOLVED -"
    ]);
    expect(target.key).toBe("db:HR.PACK_A.LEAF(P IN NUMBER)");
  });

  it("a first-hop node is one hop, using the entity", () => {
    expect(pathOf(view, 0).hops.map(hop => hop.node)).toEqual([0]);
  });

  it("ends on a cycle of via instead of looping", () => {
    const cyclic: ImpactView = { ...view, nodes: view.nodes.map((n, i) => i === 1 ? { ...n, via: { ...n.via, node: 2 } } : n) };
    expect(pathOf(cyclic, 2).hops.map(hop => hop.node)).toEqual([2, 1]);
  });

  it("as text: each hop with its file and line, the trigger it fires and a status that is not RESOLVED", () => {
    expect(pathText(view, 5, events => `fires ${events}`)).toBe(
      "APP.TOP_INS() (app/top_ins.prc:4, fires INSERT) < HR.TRG (hr/trg.trg:5) < HR.PACK_A.MID(P IN NUMBER) (hr/pack_a.pkb:11) < HR.PACK_A.LEAF(P IN NUMBER)");
    expect(pathText(view, 2, events => events)).toContain("HR.PACK_B.TOP() (hr/pack_b.pkb:10, UNVERIFIED)");
  });
});

describe("viewOf", () => {
  it("fills a display left out with the key without db:, and keeps one that differs", () => {
    const answer = { ...view, entities: [{ ...entities[0], display: null }, { ...entities[1], display: "HR.\"Odd\".MID" }] };
    expect(viewOf(answer).entities.map(e => e.display)).toEqual(["HR.PACK_A.LEAF(P IN NUMBER)", 'HR."Odd".MID']);
  });
});

describe("groupByOwner", () => {
  it("groups by package or type, standalone code by schema, and script files last", () => {
    const groups = groupByOwner(view, view.nodes.map((_, i) => i));
    expect(groups.map(group => `${group.owner?.key ?? (group.files ? "files" : "schema " + group.schema)}: ${group.nodes.join(",")}`)).toEqual([
      "schema APP: 5", "schema HR: 0,3", "db:HR.PACK_A: 1", "db:HR.PACK_B: 2", "files: 4"
    ]);
  });

  it("the entry points only, in the graph's order within a group", () => {
    const entries = shownNodes(view, readState("?key=x"));
    expect(entries).toEqual([0, 2, 5]);
    expect(groupByOwner(view, entries).map(group => group.nodes)).toEqual([[5], [0], [2]]);
  });

  it("by depth for all nodes", () => {
    expect(groupByDepth(view, [5, 0, 4, 1])).toEqual([{ depth: 1, nodes: [0, 1] }, { depth: 2, nodes: [4] }, { depth: 3, nodes: [5] }]);
  });
});

describe("csv", () => {
  it("quotes a field with a comma, a quote, a line break or edge spaces, and doubles its quotes", () => {
    expect(csvField("HR.PKG.RUN(P_A IN NUMBER,P_B IN DATE)")).toBe('"HR.PKG.RUN(P_A IN NUMBER,P_B IN DATE)"');
    expect(csvField('say "hi"')).toBe('"say ""hi"""');
    expect(csvField("two\nlines")).toBe('"two\nlines"');
    expect(csvField(" padded")).toBe('" padded"');
    expect(csvField("plain")).toBe("plain");
    expect(csvField(3)).toBe("3");
  });

  it("joins fields with commas and records with CRLF", () => {
    expect(csvText([["Name", "Depth"], ["A,B", 2]])).toBe('Name,Depth\r\n"A,B",2\r\n');
  });
});

describe("the URL state", () => {
  it("reads the defaults from a bare key", () => {
    expect(readState("?key=db%3AHR.X")).toEqual({ key: "db:HR.X", triggers: true, maxDepth: 10, tab: "entries", stop: null, depth: null, certain: false });
  });

  it("reads every option and filter, and refuses values out of range", () => {
    expect(readState("?key=k&triggers=false&maxDepth=25&tab=all&stop=TRIGGER&depth=3&certain=true")).toEqual(
      { key: "k", triggers: false, maxDepth: 25, tab: "all", stop: "TRIGGER", depth: 3, certain: true });
    expect(readState("?key=k&maxDepth=51&stop=NOPE&depth=0&tab=x")).toMatchObject({ maxDepth: 10, stop: null, depth: null, tab: "entries" });
    expect(readState("?key=k&maxDepth=2.5").maxDepth).toBe(10);
  });

  it("writes what it reads back, defaults left out, the language kept", () => {
    const state = readState("?key=db%3AHR.X&triggers=false&maxDepth=3&tab=all&stop=DEPTH_LIMIT&certain=true");
    const search = stateSearch(state, "pt");
    expect(search).toBe("?key=db%3AHR.X&triggers=false&maxDepth=3&tab=all&stop=DEPTH_LIMIT&certain=true&lang=pt");
    expect(readState(search)).toEqual(state);
    expect(stateSearch(readState("?key=k"))).toBe("?key=k");
  });

  it("keeps the filters of all nodes only on that tab", () => {
    expect(stateSearch({ ...readState("?key=k"), stop: "FOLLOWED", depth: 2, certain: true })).toBe("?key=k");
  });

  it("filters all nodes by stop, depth and certainty together", () => {
    expect(shownNodes(view, readState("?key=k&tab=all&stop=ENTRY_POINT&certain=true"))).toEqual([0, 2]);
    expect(shownNodes(view, readState("?key=k&tab=all&depth=2"))).toEqual([2, 3, 4]);
  });
});

describe("buildTree", () => {
  /** Every item under [tree], depth first, as `node` indexes or `part:<entity>`. */
  const flat = (tree: TreeItem): string[] => tree.children.flatMap(child => [child.node !== null ? String(child.node) : `part:${child.part}`, ...flat(child)]);
  const nodesOf = (tree: TreeItem) => flat(tree).filter(item => !item.startsWith("part:"));

  it("puts every node exactly once, each under the node it was first reached through", () => {
    const tree = buildTree(view);
    expect(nodesOf(tree).sort()).toEqual(["0", "1", "2", "3", "4", "5"]);
    // FN and MID use LEAF itself: straight under the root, by name (FN before PACK_A.MID).
    expect(tree.children.map(child => child.node)).toEqual([0, 1]);
    const mid = tree.children[1];
    // Under MID by name: PACK_B.TOP, TRG, then the script file.
    expect(mid.children.map(child => child.node)).toEqual([2, 3, 4]);
    expect(mid.children.find(child => child.node === 3)!.children.map(child => child.node)).toEqual([5]);
  });

  it("counts the nodes below each line", () => {
    const tree = buildTree(view);
    expect(tree.below).toBe(6);
    const mid = tree.children.find(child => child.node === 1)!;
    expect(mid.below).toBe(4);
    expect(mid.children.find(child => child.node === 3)!.below).toBe(1);
    expect(tree.children.find(child => child.node === 0)!.below).toBe(0);
  });

  it("groups the first steps by the part of the entity they use, the entity itself straight under the root", () => {
    const member = entity("db:HR.PACK_A.C_MAX", "CONSTANT"); // 8, a member the first steps use
    const spec = entity("db:HR.PACK_A.LEAF(P IN NUMBER)@SPEC", "PROCEDURE"); // 9
    const parts: ImpactView = {
      ...view, entities: [...entities, member, spec],
      nodes: [
        node({ entity: 2, depth: 1, stop: "ENTRY_POINT", via: { node: null, uses: 8, path: 1, line: 3, status: "RESOLVED", fires: null } }),
        node({ entity: 1, depth: 1, stop: "FOLLOWED", via: { node: null, uses: 8, path: 0, line: 4, status: "RESOLVED", fires: null } }),
        node({ entity: 3, depth: 1, stop: "ENTRY_POINT", via: { node: null, uses: 9, path: 2, line: 5, status: "RESOLVED", fires: null } }),
        node({ entity: 7, depth: 1, stop: "ENTRY_POINT", via: { node: null, uses: 0, path: 5, line: 6, status: "RESOLVED", fires: null } }),
        node({ entity: 4, depth: 2, stop: "FOLLOWED", via: { node: 1, uses: 1, path: 3, line: 7, status: "RESOLVED", fires: null } })
      ]
    };
    const tree = buildTree(parts);
    // The parts and the direct user, by name: APP.TOP_INS, then the two parts of PACK_A.
    expect(tree.children.map(child => child.part !== null ? `part:${child.part}` : `node:${child.node}`)).toEqual(["node:3", "part:8", "part:9"]);
    const constant = tree.children[1];
    expect(constant.children.map(child => child.node).sort()).toEqual([0, 1]);
    expect(constant.below).toBe(3);
    expect(tree.children[2].children.map(child => child.node)).toEqual([2]);
    expect(nodesOf(tree).sort()).toEqual(["0", "1", "2", "3", "4"]);
  });

  it("cuts a chain that loops back, so that each of its nodes appears once and the walk ends", () => {
    const looped: ImpactView = {
      ...view,
      nodes: [
        node({ entity: 1, depth: 1, stop: "FOLLOWED", via: { node: null, uses: 0, path: 0, line: 1, status: "RESOLVED", fires: null } }),
        // 1 and 2 reach each other: a loop that never reaches the entity; 3 hangs below the loop.
        node({ entity: 2, depth: 2, stop: "FOLLOWED", via: { node: 2, uses: 3, path: 1, line: 2, status: "RESOLVED", fires: null } }),
        node({ entity: 3, depth: 3, stop: "FOLLOWED", via: { node: 1, uses: 2, path: 2, line: 3, status: "RESOLVED", fires: null } }),
        node({ entity: 4, depth: 4, stop: "ENTRY_POINT", via: { node: 2, uses: 3, path: 3, line: 4, status: "RESOLVED", fires: null } })
      ]
    };
    const tree = buildTree(looped);
    expect(nodesOf(tree).sort()).toEqual(["0", "1", "2", "3"]);
    const cut = tree.children.find(child => child.node === 1)!;
    expect(cut.loop).toBe(true);
    expect(flat(cut)).toEqual(["2", "3"]);
    expect(cut.below).toBe(2);
    expect(tree.below).toBe(4);
  });
});

