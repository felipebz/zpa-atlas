import {describe, expect, it, vi} from "vitest";
import type {EntityDetail, EntitySummary, FormsInfo, FormsNode, FormsTree} from "../src/api";
import {
    allOpen,
    blockView,
    codePageAsk,
    codeUnitName,
    filterTree,
    firstUnit,
    formsCrumbs,
    formsDefinition,
    formsName,
    globalUseRows,
    groupId,
    initialOpen,
    itemView,
    nodeId,
    nodeUnit,
    openTo,
    sameUnit,
    settledCode,
    unitKindWord,
    unitLines,
    unitNode,
    visibleRows,
    wantedCode
} from "../src/forms-view";
import {codeHref, fileHref} from "../src/html";

// The Doag corpus module demo0004_fmb.xml: its internal name is RELEASE_LOCKS.
function forms(key: string, kind: string, display: string): EntitySummary {
  return { key: "forms:" + key, kind, display, external: false, name: display.slice(display.lastIndexOf("/") + 1) };
}

const module = forms("DEMO0004", "FORMS_MODULE", "DEMO0004");
const info: FormsInfo = { module, title: "RELEASE_LOCKS", path: "demo0004_fmb.xml" };
const vLock = forms("DEMO0004/V$LOCK", "FORMS_BLOCK", "DEMO0004/V$LOCK");
const id1 = forms("DEMO0004/V$LOCK/ID1", "FORMS_ITEM", "DEMO0004/V$LOCK/ID1");
const steuer = forms("DEMO0004/STEUERBLOCK", "FORMS_BLOCK", "DEMO0004/STEUERBLOCK");
const pbExit = forms("DEMO0004/STEUERBLOCK/PB_EXIT", "FORMS_ITEM", "DEMO0004/STEUERBLOCK/PB_EXIT");
const pressed = forms("DEMO0004/STEUERBLOCK/PB_EXIT/WHEN-BUTTON-PRESSED", "FORMS_TRIGGER", "DEMO0004/STEUERBLOCK/PB_EXIT/WHEN-BUTTON-PRESSED");
const newForm = forms("DEMO0004/WHEN-NEW-FORM-INSTANCE", "FORMS_TRIGGER", "DEMO0004/WHEN-NEW-FORM-INSTANCE");
const accordion = forms("DEMO0004/ACCORDION", "FORMS_LIBRARY", "DEMO0004/ACCORDION");
const pkAccordion = forms("DEMO0004/PK_ACCORDION", "FORMS_PROGRAM_UNIT", "DEMO0004/PK_ACCORDION");
const locks = forms("DEMO0004/RG_LOCKS", "FORMS_RECORD_GROUP", "DEMO0004/RG_LOCKS");

function page(entity: EntitySummary, ancestors: EntitySummary[]): Pick<EntityDetail, "key" | "name" | "display" | "ancestors" | "forms"> {
  return { key: entity.key, name: entity.name, display: entity.display, ancestors, forms: info };
}

const leaf = (entity: EntitySummary): FormsNode => ({ entity, groups: [] });

/**
 * The module as the server gives it: triggers, libraries, blocks (triggers, then items with their triggers), program
 * units (a package as two nodes, its specification and its body), record groups.
 */
const tree: FormsTree = {
  root: module,
  groups: [
    { kind: "TRIGGERS", nodes: [leaf(newForm)] },
    { kind: "LIBRARIES", nodes: [leaf(accordion)] },
    { kind: "BLOCKS", nodes: [
      { entity: vLock, groups: [{ kind: "ITEMS", nodes: [leaf(id1)] }] },
      { entity: steuer, groups: [{ kind: "ITEMS", nodes: [{ entity: pbExit, groups: [{ kind: "TRIGGERS", nodes: [leaf(pressed)] }] }] }] }
    ] },
    { kind: "PROGRAM_UNITS", nodes: [{ ...leaf(pkAccordion), unitKind: "PACKAGE" }, { ...leaf(pkAccordion), unitKind: "PACKAGE_BODY" }] },
    { kind: "RECORD_GROUPS", nodes: [leaf(locks)] }
  ]
};

/**
 * The rows shown as text: each indented by its depth, a group by its kind, an object by its name (a package's half
 * after it), `+`/`-` when it opens.
 */
function lines(open: Set<string>, text = "") {
  return visibleRows(tree, open, filterTree(tree, text)).map(row => "  ".repeat(row.depth) + (row.type === "group"
    ? `[${row.kind}]${row.open ? "" : " +"}`
    : `${row.entity.name}${row.unitKind ? " " + row.unitKind : ""}${row.openable ? (row.open ? " -" : " +") : ""}`));
}

describe("Forms names", () => {
  it("titles the module by its internal name", () => {
    expect(formsName(module, info)).toEqual({ key: module.key, label: "RELEASE_LOCKS", title: "DEMO0004" });
    expect(formsCrumbs(page(module, []))).toEqual([]);
  });

  it("names a block, an item and a trigger by their own name, the path as the tooltip, under the module's internal name", () => {
    expect(formsName(vLock, info)).toEqual({ key: vLock.key, label: "V$LOCK", title: "DEMO0004/V$LOCK" });
    expect(formsCrumbs(page(vLock, [module])).map(c => c.label)).toEqual(["RELEASE_LOCKS"]);
    expect(formsName(id1, info)).toEqual({ key: id1.key, label: "ID1", title: "DEMO0004/V$LOCK/ID1" });
    expect(formsCrumbs(page(id1, [module, vLock]))).toEqual([
      { key: module.key, label: "RELEASE_LOCKS", title: "DEMO0004" },
      { key: vLock.key, label: "V$LOCK", title: "DEMO0004/V$LOCK" }
    ]);
    expect(formsName(pressed, info).label).toBe("WHEN-BUTTON-PRESSED");
    expect(formsName(pressed, info).title).toBe("DEMO0004/STEUERBLOCK/PB_EXIT/WHEN-BUTTON-PRESSED");
    expect(formsCrumbs(page(pressed, [module, steuer, pbExit])).map(c => [c.label, c.title])).toEqual([
      ["RELEASE_LOCKS", "DEMO0004"], ["STEUERBLOCK", "DEMO0004/STEUERBLOCK"], ["PB_EXIT", "DEMO0004/STEUERBLOCK/PB_EXIT"]
    ]);
  });

  it("names a module parameter by its own name, and a global, which belongs to no module, as its code writes it", () => {
    const city = forms("DEMO0004/PARAMETER/P_CITY", "FORMS_PARAMETER", "DEMO0004/PARAMETER/P_CITY");
    expect(formsName(city, info)).toEqual({ key: city.key, label: "P_CITY", title: "DEMO0004/PARAMETER/P_CITY" });
    const user: EntitySummary = { key: "forms::GLOBAL.ROUTE_USER", kind: "FORMS_GLOBAL", display: ":GLOBAL.ROUTE_USER", external: false, name: "ROUTE_USER" };
    expect(formsName(user, null)).toEqual({ key: user.key, label: ":GLOBAL.ROUTE_USER", title: ":GLOBAL.ROUTE_USER" });
    expect(formsName(user, info).label).toBe(":GLOBAL.ROUTE_USER");
  });

  it("lists a global's modules in the answer's order, each count leading to its uses in that module's file", () => {
    const desk = forms("ROUTE_DESK", "FORMS_MODULE", "ROUTE_DESK");
    const view = forms("ROUTE_VIEW", "FORMS_MODULE", "ROUTE_VIEW");
    const key = "forms::GLOBAL.ROUTE_USER";
    expect(globalUseRows(key, [
      { module: desk, path: "route_desk_fmb.xml", writes: 1, reads: 2 },
      { module: view, path: "forms/route_view_fmb.xml", writes: 0, reads: 1 }
    ])).toEqual([
      { module: desk, path: "route_desk_fmb.xml", writes: 1, reads: 2,
        writesQuery: { entity: key, roles: "WRITE", path: "route_desk_fmb.xml" }, readsQuery: { entity: key, roles: "READ", path: "route_desk_fmb.xml" } },
      { module: view, path: "forms/route_view_fmb.xml", writes: 0, reads: 1,
        writesQuery: null, readsQuery: { entity: key, roles: "READ", path: "forms/route_view_fmb.xml" } }
    ]);
    expect(globalUseRows(key, [])).toEqual([]);
  });

  it("names a place in Forms code by its unit's own name, without a key collision's number", () => {
    expect(codeUnitName("forms:DEMO0004/STEUERBLOCK/PB_EXIT/WHEN-BUTTON-PRESSED")).toBe("WHEN-BUTTON-PRESSED");
    expect(codeUnitName("forms:DEMO0004/STEUERBLOCK/POST-QUERY#2")).toBe("POST-QUERY");
    expect(codeUnitName("forms:DEMO0004/PK_ACCORDION")).toBe("PK_ACCORDION");
  });

  it("keeps a snippet inside the use's unit in Forms code, and every line elsewhere", () => {
    const pre = { key: "forms:DATA_DESK/PRE-FORM", line: 1 };
    const unit = (line: number) => ({ key: "forms:DATA_DESK/EVENTS/ON-UPDATE", line });
    const lines = [{ number: 40, code: pre }, { number: 41, code: null }, { number: 42, code: unit(1) }, { number: 43, code: unit(2) }];
    expect(unitLines(lines, 42).map(l => l.number)).toEqual([42, 43]);
    expect(unitLines([{ number: 7 }, { number: 8 }], 8).map(l => l.number)).toEqual([7, 8]);
  });

  it("says of an item and a parameter their kind and path, and nothing of a global or other objects", () => {
    const code = { kind: "FORMS_ITEM", display: "DATA_DESK/EVENTS/CODE", formsItem: { database: true, kind: "TEXT_ITEM", columnName: "",
      read: true, insertWrite: true, updateWrite: true } };
    expect(formsDefinition(code)).toEqual({ kindKey: "formsItemKind.TEXT_ITEM", path: "DATA_DESK/EVENTS/CODE" });
    expect(formsDefinition({ kind: "FORMS_PARAMETER", display: "DATA_DESK/P_MODE" })).toEqual({ kindKey: "popover.formsParameter", path: "DATA_DESK/P_MODE" });
    expect(formsDefinition({ kind: "FORMS_GLOBAL", display: ":GLOBAL.ROUTE_USER" })).toBeNull();
  });
});

describe("the Forms tree", () => {
  it("shows the groups in the server's order, open, and each object by its own name in the order given, blocks closed", () => {
    expect(lines(initialOpen(tree))).toEqual([
      "[TRIGGERS]", "  WHEN-NEW-FORM-INSTANCE",
      "[LIBRARIES]", "  ACCORDION",
      "[BLOCKS]", "  V$LOCK +", "  STEUERBLOCK +",
      "[PROGRAM_UNITS]", "  PK_ACCORDION PACKAGE", "  PK_ACCORDION PACKAGE_BODY",
      "[RECORD_GROUPS]", "  RG_LOCKS"
    ]);
  });

  it("opens everything with expand all, and shows only the groups with collapse all", () => {
    expect(lines(allOpen(tree))).toEqual([
      "[TRIGGERS]", "  WHEN-NEW-FORM-INSTANCE",
      "[LIBRARIES]", "  ACCORDION",
      "[BLOCKS]",
      "  V$LOCK -", "    [ITEMS]", "      ID1",
      "  STEUERBLOCK -", "    [ITEMS]", "      PB_EXIT -", "        [TRIGGERS]", "          WHEN-BUTTON-PRESSED",
      "[PROGRAM_UNITS]", "  PK_ACCORDION PACKAGE", "  PK_ACCORDION PACKAGE_BODY",
      "[RECORD_GROUPS]", "  RG_LOCKS"
    ]);
    expect(lines(new Set())).toEqual(["[TRIGGERS] +", "[LIBRARIES] +", "[BLOCKS] +", "[PROGRAM_UNITS] +", "[RECORD_GROUPS] +"]);
  });

  it("opens one block alone, its items closed below it", () => {
    const open = initialOpen(tree);
    open.add(steuer.key);
    expect(lines(open).slice(4, 9)).toEqual(["[BLOCKS]", "  V$LOCK +", "  STEUERBLOCK -", "    [ITEMS]", "      PB_EXIT +"]);
  });

  it("keeps the objects whose name holds the text, ignoring case, and opens the branches leading to them", () => {
    const filter = filterTree(tree, "pressed")!;
    expect([...filter.open]).toEqual([groupId(pbExit.key, "TRIGGERS"), pbExit.key, groupId(steuer.key, "ITEMS"), steuer.key, groupId(module.key, "BLOCKS")]);
    expect(lines(filter.open, "pressed")).toEqual([
      "[BLOCKS]", "  STEUERBLOCK -", "    [ITEMS]", "      PB_EXIT -", "        [TRIGGERS]", "          WHEN-BUTTON-PRESSED"
    ]);
  });

  it("keeps what a kept object holds, closed until it is opened, and nothing for a text no name holds", () => {
    const filter = filterTree(tree, "v$lock")!;
    expect(lines(filter.open, "v$lock")).toEqual(["[BLOCKS]", "  V$LOCK +"]);
    expect(lines(new Set([...filter.open, vLock.key]), "v$lock")).toEqual(["[BLOCKS]", "  V$LOCK -", "    [ITEMS]", "      ID1"]);
    expect(lines(filterTree(tree, "nothing here")!.open, "nothing here")).toEqual([]);
  });

  it("filters nothing for a blank text", () => {
    expect(filterTree(tree, "  ")).toBeNull();
  });

  it("keeps a package's two halves as two objects, each its own id and word", () => {
    const rows = visibleRows(tree, initialOpen(tree), filterTree(tree, "accordion"));
    expect(rows.filter(row => row.type === "node").map(row => row.id)).toEqual([
      accordion.key, nodeId(pkAccordion.key, "PACKAGE"), nodeId(pkAccordion.key, "PACKAGE_BODY")
    ]);
    expect(new Set(rows.map(row => row.id)).size).toBe(rows.length);
    expect(["PACKAGE", "PACKAGE_BODY", null].map(unitKindWord)).toEqual(["entity.overload.spec", "entity.overload.body", null]);
  });
});

describe("the code page", () => {
  vi.stubGlobal("location", { search: "" });
  const path = "demo0004_fmb.xml";
  const body = { key: pkAccordion.key, unitKind: "PACKAGE_BODY" };

  it("reads the unit and the line from the address, the line's anchor first", () => {
    const search = "?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FPK_ACCORDION&unitKind=PACKAGE_BODY&line=78";
    expect(wantedCode(search, "#L78")).toEqual({ unit: body, line: 78 });
    expect(wantedCode(search, "#L80")).toEqual({ unit: body, line: 80 });
    expect(wantedCode(search, "")).toEqual({ unit: body, line: 78 });
    expect(wantedCode("?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FRG_LOCKS", "")).toEqual({ unit: { key: locks.key, unitKind: null }, line: null });
  });

  it("asks for the module's first unit when the address names none, a module text's line left unmarked", () => {
    expect(wantedCode("?path=demo0004_fmb.xml", "")).toEqual({ unit: null, line: null });
    expect(wantedCode("?path=demo0004_fmb.xml&line=412", "#L412")).toEqual({ unit: null, line: null });
  });

  it("names in the address the unit shown when it named another or none", () => {
    const first = { key: newForm.key, unitKind: null };
    // The first unit in the tree's order, shown for an address without a unit or with one the module does not have.
    expect(settledCode({ unit: null, line: null }, first)).toEqual({ unit: first, line: null });
    expect(settledCode({ unit: { key: "forms:DEMO0004/NOWHERE", unitKind: null }, line: 5 }, first)).toEqual({ unit: first, line: null });
    // A package asked without its half shows its specification, at the line asked.
    const spec = { key: pkAccordion.key, unitKind: "PACKAGE" };
    expect(settledCode({ unit: { key: pkAccordion.key, unitKind: null }, line: 12 }, spec)).toEqual({ unit: spec, line: 12 });
    expect(settledCode({ unit: body, line: 78 }, body)).toBeNull();
    expect(codeHref(path, first, null)).toBe("file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FWHEN-NEW-FORM-INSTANCE");
  });

  it("marks the node of the unit shown, its branch opened", () => {
    const shown = { key: pressed.key, unitKind: null };
    const node = unitNode(tree, shown)!;
    expect(node.entity).toBe(pressed);
    const open = openTo(tree, initialOpen(tree), nodeId(shown.key, shown.unitKind));
    expect(lines(open).slice(4, 11)).toEqual([
      "[BLOCKS]", "  V$LOCK +", "  STEUERBLOCK -", "    [ITEMS]", "      PB_EXIT -", "        [TRIGGERS]", "          WHEN-BUTTON-PRESSED"
    ]);
    // A package's body is its own node, not its specification's.
    expect(unitNode(tree, body)!.unitKind).toBe("PACKAGE_BODY");
    expect(unitNode(tree, { key: vLock.key, unitKind: null })).toBeNull();
  });

  it("leads a piece of code chosen in the tree to its code page, the rest to their pages", () => {
    const units = tree.groups.flatMap(group => group.nodes).map(node => nodeUnit(node));
    expect(units.map(unit => unit && codeHref(path, unit, null))).toEqual([
      "file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FWHEN-NEW-FORM-INSTANCE",
      null,
      null,
      null,
      "file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FPK_ACCORDION&unitKind=PACKAGE",
      "file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FPK_ACCORDION&unitKind=PACKAGE_BODY",
      "file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FRG_LOCKS"
    ]);
    expect(sameUnit(units[4]!, units[5]!)).toBe(false);
    expect(sameUnit({ key: locks.key }, { key: locks.key, unitKind: null })).toBe(true);
  });

  it("asks for the module's code page alone when the address names a unit, for the file alone when not", () => {
    expect(codePageAsk(path, body)).toEqual({ api: "api/forms-file", params: { path, key: pkAccordion.key, unitKind: "PACKAGE_BODY", colors: true } });
    expect(codePageAsk("pkg/pack_orders.pkb", null)).toEqual({ api: "api/file", params: { path: "pkg/pack_orders.pkb", colors: true } });
    expect(codePageAsk("pkg/pack_orders.pkb", null, "DB_SOURCE").api).toBe("api/file");
  });

  it("asks for the first unit when the file turns out to be a Forms module, the address then naming it", () => {
    const asked = codePageAsk(path, null, "FORMS_MODULE");
    expect(asked).toEqual({ api: "api/forms-file", params: { path, key: undefined, unitKind: undefined, colors: true } });
    // The server shows the first unit in the tree's order; the address is replaced by one naming it.
    const shown = firstUnit(tree)!;
    const settled = settledCode({ unit: null, line: null }, shown)!;
    expect(codeHref(path, settled.unit, settled.line)).toBe("file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FWHEN-NEW-FORM-INSTANCE");
  });

  it("leads the module's File to its first unit in the tree's order: depth first, a package's half by its kind", () => {
    expect(fileHref(path, firstUnit(tree))).toBe("file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FWHEN-NEW-FORM-INSTANCE");
    // Without module triggers or libraries, a block's item's trigger comes before the program units.
    const blocks: FormsTree = { root: module, groups: tree.groups.filter(group => group.kind !== "TRIGGERS") };
    expect(firstUnit(blocks)).toEqual({ key: pressed.key, unitKind: null });
    const units: FormsTree = { root: module, groups: [{ kind: "PROGRAM_UNITS", nodes: [{ ...leaf(pkAccordion), unitKind: "PACKAGE_BODY" }, { ...leaf(pkAccordion), unitKind: "PACKAGE" }] }] };
    expect(fileHref(path, firstUnit(units))).toBe("file.html?path=demo0004_fmb.xml&unit=forms%3ADEMO0004%2FPK_ACCORDION&unitKind=PACKAGE_BODY");
    // A module without code: the file page finds that out.
    const none: FormsTree = { root: module, groups: [{ kind: "LIBRARIES", nodes: [leaf(accordion)] }] };
    expect(firstUnit(none)).toBeNull();
    expect(fileHref(path, firstUnit(none))).toBe("file.html?path=demo0004_fmb.xml");
  });
});

describe("a data block's and an item's data", () => {
  // The fixture module DATA_DESK: EVENTS queries and writes HR.EVENT_LOG, COMPANY queries HR.V_COMPANY and writes HR.COMPANY.
  const db = (display: string, kind: string): EntitySummary =>
    ({ key: "db:" + display, kind, display, external: false, name: display.slice(display.lastIndexOf(".") + 1) });
  const eventLog = db("HR.EVENT_LOG", "TABLE");
  const onUpdate = forms("DATA_DESK/EVENTS/ON-UPDATE", "FORMS_TRIGGER", "DATA_DESK/EVENTS/ON-UPDATE");

  it("links the block's relations and says each operation: done by Forms, replaced by its trigger, not allowed", () => {
    const view = blockView({
      source: { type: "TABLE", name: "EVENT_LOG", entity: eventLog }, target: { type: "TABLE", name: "EVENT_LOG", entity: eventLog },
      operations: [{ operation: "QUERY", state: "DONE" }, { operation: "INSERT", state: "NOT_ALLOWED" },
        { operation: "UPDATE", state: "REPLACED", trigger: onUpdate }, { operation: "DELETE", state: "DONE" }],
      where: "", orderBy: ""
    });
    expect(view.source).toEqual({ entity: eventLog, written: "EVENT_LOG", typeKey: null });
    expect(view.operations).toEqual([
      { operationKey: "formsOperation.QUERY", stateKey: "formsOperationState.DONE", trigger: null },
      { operationKey: "formsOperation.INSERT", stateKey: "formsOperationState.NOT_ALLOWED", trigger: null },
      { operationKey: "formsOperation.UPDATE", stateKey: "formsOperationState.REPLACED", trigger: onUpdate },
      { operationKey: "formsOperation.DELETE", stateKey: "formsOperationState.DONE", trigger: null }
    ]);
    expect([view.where, view.orderBy]).toEqual([null, null]);
  });

  it("says an operation replaced by the module's trigger is the module's", () => {
    const onInsert = forms("DATA_DESK/ON-INSERT", "FORMS_TRIGGER", "DATA_DESK/ON-INSERT");
    const view = blockView({
      source: { type: "TABLE", name: "EVENT_LOG", entity: eventLog }, target: { type: "TABLE", name: "EVENT_LOG", entity: eventLog },
      operations: [{ operation: "INSERT", state: "REPLACED", trigger: onInsert }, { operation: "UPDATE", state: "REPLACED", trigger: onUpdate }],
      where: "", orderBy: ""
    });
    expect(view.operations.map(o => [o.stateKey, o.trigger])).toEqual([
      ["formsOperationState.REPLACED_BY_MODULE", onInsert], ["formsOperationState.REPLACED", onUpdate]]);
  });

  it("names a source that is no table as written, with its type, and keeps WHERE and ORDER BY as written", () => {
    const view = blockView({
      source: { type: "PROCEDURE", name: "order_api.query_orders" }, target: { type: "NONE", name: "order_api.query_orders" },
      operations: ["QUERY", "INSERT", "UPDATE", "DELETE"].map(operation => ({ operation, state: "NOT_FOLLOWED" })),
      where: "st_active = 'Y'\n  and id > 0", orderBy: "company_name"
    });
    expect(view.source).toEqual({ entity: null, written: "order_api.query_orders", typeKey: "formsDataType.PROCEDURE" });
    expect(view.target.typeKey).toBe("formsDataType.NONE");
    expect(view.operations.map(o => o.stateKey)).toEqual(Array(4).fill("formsOperationState.NOT_FOLLOWED"));
    expect([view.where, view.orderBy]).toEqual(["st_active = 'Y'\n  and id > 0", "company_name"]);
  });

  it("links an item's column and the one its block writes, and says what Forms does with it", () => {
    const view = itemView({ database: true, kind: "TEXT_ITEM", columnName: "", column: db("HR.V_COMPANY.ID", "COLUMN"),
      targetColumn: db("HR.COMPANY.ID", "COLUMN"), read: true, insertWrite: true, updateWrite: false });
    expect(view).toEqual({
      kindKey: "formsItemKind.TEXT_ITEM", database: true, column: db("HR.V_COMPANY.ID", "COLUMN"), targetColumn: db("HR.COMPANY.ID", "COLUMN"),
      done: [{ key: "forms.data.read", yes: true }, { key: "forms.data.insertWrite", yes: true }, { key: "forms.data.updateWrite", yes: false }]
    });
  });

  it("says nothing Forms does for an item on no column", () => {
    const button = itemView({ database: true, kind: "PUSH_BUTTON", columnName: "", read: false, insertWrite: false, updateWrite: false });
    expect(button).toEqual({ kindKey: "formsItemKind.PUSH_BUTTON", database: true, column: null, targetColumn: null, done: [] });
    expect(itemView({ database: false, kind: "DISPLAY_ITEM", columnName: "", read: false, insertWrite: false, updateWrite: false }).database).toBe(false);
  });
});
