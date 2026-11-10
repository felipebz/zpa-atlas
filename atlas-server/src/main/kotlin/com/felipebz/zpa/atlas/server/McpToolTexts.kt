//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

/**
 * What the agent reads of the tools: the server's instructions and each tool's description, and the notes they share.
 * Every text is sent as is, in `initialize` and `tools/list`.
 */
internal object McpToolTexts {
    const val INSTRUCTIONS =
        "zpa-atlas is an index of the PL/SQL code of an Oracle database (packages, procedures, functions, " +
            "triggers, tables, views, columns): ask it who calls, reads or writes something, for impact analysis, " +
            "and for the values passed to a parameter or written to a column.\n" +
            "Which tool answers which question:\n" +
            "- search_entities: an entity's key from its name; dotted names work (pack.proc, tab.col).\n" +
            "- get_entity: kind, definitions, members or columns, declaration, docs, views; a routine's " +
            "spec and bodies; a column's datatype, NOT NULL, keys. find_referencing_keys: all foreign keys to them.\n" +
            "- find_column_places: every variable, parameter or field a column's value is read into, and how " +
            "each is declared.\n" +
            "- find_usages: who uses one entity, following its spec and body, its members and the views built on " +
            "it; group_usages counts them by routine, file or view. via keeps only the direct uses or only those " +
            "through views.\n" +
            "- find_occurrences, group_occurrences: questions across the code: every write in a package, calls by " +
            "name pattern, a CRUD matrix, rows by status or by value.\n" +
            "- get_values: the values a parameter is passed or a column is written with; then find_value_sites: " +
            "where one of them comes from.\n" +
            "- get_source: the text around a line. Sources are only on the server, not on the local disk.\n" +
            "- find_impact: impact analysis, who calls the callers up to the entry points; nodes are numbered (n), " +
            "keys=true gives full keys.\n" +
            "- search_text: other text (XML tags, messages, comments, SQL in strings); pass the package or routine " +
            "the question names as container_name, or a file or folder as path, else it searches the whole index.\n" +
            "Usual order: a group_ tool for the summary, a find_ tool for the lines, get_source for the text.\n" +
            "Null fields are left out, and a display equal to the key. An answer over " +
            "${McpAnswers.MAX_CHARS} characters is cut, and cutForSize says how: the rows kept (page on from " +
            "nextAfter or nextOffset), the lower list limit of an entity, or the source lines shortened. A usage " +
            "tool's included lists what it followed."

    /** How an unscoped text search is narrowed. */
    const val NARROW = "The whole index was searched: pass the package or routine name as container_name, or a " +
        "file or folder as path, to search only there."

    /** What the index does not answer, said by the tools that would otherwise seem to. */
    private const val NOT_ANSWERED =
        "Not indexed: reports, Forms menus and object libraries, and code outside the indexed sources; dynamic SQL is partial. " +
            "A status other than RESOLVED means the index is not sure: say so in the answer."

    private const val PATHS =
        "Its paths are on the server, relative to its sources folder: read the text with get_source, not from a local disk."

    private const val KEYS =
        "Entity keys: db:SCHEMA.NAME; a member as db:SCHEMA.PACKAGE.MEMBER, a column as db:SCHEMA.TABLE.COLUMN. " +
            "A procedure or function ends with its parameter list, db:HR.PKG.RUN(P_A IN NUMBER,P_B IN VARCHAR2); " +
            "its parameters add .P_A, its body @BODY after the list; #2, #3 tell apart the same signature. " +
            "What a package body declares for itself is db:HR.PKG.G_X@BODY; a field of its record type adds .F. " +
            "~ is an unknown schema (db:~.DBMS_SQL). Through a database link: db:SCHEMA.NAME@LINK, or db:NAME@LINK. " +
            "Forms: forms:ORDERS/BLOCK/ITEM/TRIGGER, forms:ORDERS/PROGRAM_UNIT (a package with its members), " +
            "forms:ORDERS/PARAMETER/P, forms::GLOBAL.G (a global)."

    private const val STATUSES =
        "status: RESOLVED is proven. The others say why the index is not sure: UNVERIFIED (not checked, no " +
            "dictionary), AMBIGUOUS (several candidates, in candidates if any), UNRESOLVED (named but not found; " +
            "reason says why), EXTERNAL (beyond a database link), DYNAMIC (dynamic SQL), PARTIAL_SOURCE (in code " +
            "the parser recovered)."

    private const val ROLES =
        "roles: DEFINITION, DECLARATION; READ, WRITE (a variable, or a column in SQL); CALL; SQL_SELECT, " +
            "SQL_INSERT, SQL_UPDATE, SQL_DELETE, SQL_MERGE, SQL_LOCK (a table, view or column in that statement); " +
            "TYPE_USE (as a type, %TYPE, %ROWTYPE); RAISE, HANDLE (exceptions); IMPLICIT (used without being " +
            "named: select *, insert without a column list); DDL_CREATE, DDL_ALTER, DDL_DROP, DDL_TRUNCATE, " +
            "DDL_ANALYZE (the object a DDL statement acts on)."

    private const val IMPLICIT_USES =
        "origin FORMS_IMPLICIT: a use Forms makes without code (a data block's query, insert, update or delete of its " +
            "table; an item's read or write of its column), with no span, unit or line; container is the block or " +
            "item, path the module's file."

    private const val FORMS_NAMES =
        "Forms code: :BLOCK.ITEM, :ITEM, :PARAMETER.P, :GLOBAL.G are READ/WRITE uses; GO_BLOCK, GO_ITEM, " +
            "EXECUTE_TRIGGER, CALL_FORM, OPEN_FORM, NEW_FORM with a literal CALL the block, item, trigger or module. " +
            "Missing: NO_SUCH_* or MODULE_NOT_INDEXED."

    const val SEARCH = "Finds the key of an entity by its name: tables, views, columns, packages, procedures, " +
        "functions, parameters, types, object type attributes, package variables and constants (a body's own included), sequences, " +
        "synonyms, triggers, Forms objects (modules, blocks, items, parameters, globals, triggers, program units). Exact name matches first. $KEYS"

    const val ENTITY = "What one entity is: its kind, parent, definitions (file and line), overloads, children " +
        "(members or columns, with their uses), relations, triggers and the views that read it. A routine spec's " +
        "bodies (key and definitions, never cut), a body's spec. declared (entity, " +
        "child): as written: type (a function's or cursor's return type); value, a default or " +
        "initial value, valueCut if cut; valueFrom SPEC: a " +
        "body's parameter takes its spec's default; specText: the spec's, where a " +
        "body differs. documentation (entity, child): its doc comment, " +
        "then the other half's (from SPEC or BODY) if it differs. " +
        "usesIncomplete (entity, child): why uses may be more. membersIncomplete (object type): attributes may be more. keys (dictionary): a " +
        "table's keys, or a column's; referencedBy: foreign keys to them, " +
        "cut (find_referencing_keys gives all); referencedByIncomplete: there may be more. " +
        "placesByClass (column): where its value lands alone, by class; " +
        "concatenatedPlaces: where it is in a concatenation (find_column_places lists both); " +
        "receives: columns read into this target. formsBlock: source, target, operations DONE (by Forms, " +
        "no code), REPLACED (trigger), NOT_ALLOWED, NOT_FOLLOWED; formsItem: column, read, insertWrite, updateWrite; " +
        "formsGlobal: the modules using it, writes, reads. " +
        "$PATHS $KEYS"

    const val PLACES =
        "Every PL/SQL variable, parameter or field (or Forms item, parameter, global) a column's value is read into, " +
            "each with its verdict against a size: size, the column's by default, in its unit (a number's digits before the point). " +
            "verdict: SMALLER (its own size is less), FITS (as large or larger, no size: CLOB, NUMBER, DATE, or the most SUBSTR keeps fits), " +
            "CALLER_DECIDES (an OUT or IN OUT parameter: each caller's argument decides, as a row), UNKNOWN (type not " +
            "known: reason; or verdictReason UNIT_MISMATCH, under 4 bytes per character of a CHAR column; " +
            "OTHER_COLUMN_UNKNOWN; NO_LENGTH; OTHER_KIND), FOLLOWS (%TYPE, %ROWTYPE or a cursor record over the column), " +
            "OWN_SIZE (no size given, the column's not known). verdicts: to list (all but FOLLOWS by default); counts: " +
            "under the other arguments. source: SELECT_INTO, FETCH, RETURNING (with bulk); ASSIGNMENT (target := r.f of a " +
            "row record); OUT_ARGUMENT (a call's argument for an OUT parameter; argumentOf, routine); " +
            "IN_ARGUMENT (a target in a routine, written from its IN parameter argumentOf; callCount calls pass it, one at " +
            "callPath, callLine); RETURNED (a function's result holding the column; callCount calls); BLOCK_QUERY (the Forms " +
            "item its block's query fills; no line). Rows " +
            "by verdict, file, line; paged (nextOffset); view columns computed from it too (viaView). Each row: " +
            "shownLine (the line to read: the call's for IN_ARGUMENT), declaredType, length, unit (BYTE, CHAR, DIGITS: p-s, with " +
            "scale), unitSource (WRITTEN; COMPILED, see unitCompiledIn; OPTION: optionUnit), size and " +
            "sizeUnit compared (the other column's for ANCHORED_ELSEWHERE, typeSource), functions read through, outer first " +
            "(NVL may give another value, REPLACE another length, SUBSTR and SUBSTRB a part: kept in keptUnit, the most they " +
            "keep), concatenated (one part of a || or CONCAT; room: " +
            "what the size leaves). Lists where the value lands alone; concatenated true adds those (concatenatedCount). $PATHS"

    const val REFERENCING = "Every foreign key that references the keys of a table or view, or those a column " +
        "is in (referencedBy of get_entity, which cuts it), by table then name, a page at a time: pass the answer's " +
        "nextOffset as offset; nextOffset is absent on the last page. Each row: owner, name, table, relation (its " +
        "entity, absent when the index has none), enabled, deleteRule, references (the referenced key, OWNER.NAME) " +
        "and columns in key order, each with the referenced column (references) at the same position: for a " +
        "column, the row's column whose references is that column is the one that must follow its type and size. " +
        "total counts them all; incomplete (TABLES_NOT_VISIBLE): there may be more, the loading user could not see " +
        "every table's constraints. $KEYS"

    const val FIND = "Where entities are defined, called, read or written across the code (occurrences), with " +
        "filters combined with AND (at least one; for the whole index, use group_occurrences), and total and " +
        "facets counted over every match. Pages: pass " +
        "the answer's nextAfter as after; nextAfter is absent on the last page. Each row has its status and " +
        "reason; facets.byStatus counts the statuses of every match. A call's arguments name their parameter " +
        "(P_A); its key is the row's entity key, a dot and that name. $IMPLICIT_USES $FORMS_NAMES $PATHS $STATUSES $ROLES $NOT_ANSWERED"

    const val USAGES = "Who calls, reads or writes one entity, for impact analysis: use it instead of " +
        "find_occurrences for one entity, following its spec or body and the views built on it. For a table, " +
        "CHILDREN with VIEWS also follows the view columns computed from its columns. Rows as in find_occurrences, " +
        "in source order, definitions and declarations left out unless roles asks for them; viaView is true on a row " +
        "reached through a view (a view column computed from the entity, or a view reading it), viaCandidate on an " +
        "AMBIGUOUS row where the entity is one of the candidates. One hop only: for the callers of the callers, up to " +
        "the entry points, use find_impact. A package body's own variable or constant is used only inside its " +
        "body. usesIncomplete: entities in scope with uses beyond the rows (key, reasons). Pages as in " +
        "find_occurrences. $IMPLICIT_USES $FORMS_NAMES $PATHS $STATUSES $NOT_ANSWERED"

    const val IMPACT = "Impact analysis: who calls the callers of an entity, transitively, up to the entry " +
        "points (code nothing in the index calls, run from Forms, jobs or other systems). The first hop is who uses " +
        "the entity, as find_usages; then the walk follows the uses that run each node: calls of a routine or type, " +
        "SQL on a view, reading a cursor, and for a DML trigger the INSERT, UPDATE, DELETE or MERGE that fire it (its " +
        "table, through a synonym or a one-base view; UPDATE OF by the SET columns; via.fires names the events; " +
        "for a trigger asked for, they are the first hop). " +
        "A node is a routine (spec and body as one), view, trigger, type, package, Forms block or item (via.origin " +
        "FORMS_IMPLICIT, no line) " +
        "or, for script code, the file. A view reached from a column is followed as a whole. Each node has n (its " +
        "number in the whole graph, the same on every page), name (the display without the parameter list, @BODY " +
        "kept for a private routine; key too, only when another node has the same name, as overloads do), its depth " +
        "(the shortest path), via (one use on that path: uses, the n of the node it uses, or 0 on the first hop, with " +
        "usesName when that hop matched another entity than the one asked for; file and line), certain (some path of RESOLVED " +
        "uses only) and stop: FOLLOWED (code outside its own cycle uses it), ENTRY_POINT (nothing in the index calls it but " +
        "itself or the routines it calls back, so only outside code can; for a trigger, nothing writes its table), " +
        "TRIGGER (a system trigger, or follow_triggers=false; trigger gives table and events), PACKAGE_LEVEL (package initialization or " +
        "declarations, not followed), DEPTH_LIMIT, NO_CONTAINER (a script file). levels counts nodes per depth and " +
        "stops per reason; stop=ENTRY_POINT lists only the entry points. Pages: pass nextOffset as offset. keys=true " +
        "gives every node's full key and via.uses as a key: use it with stop or offset to get the keys of the " +
        "nodes another tool will be called on. $PATHS"

    const val GROUP_USAGES = "Counts who uses one entity, by routine, file or view: the rows find_usages lists for " +
        "the same arguments, grouped by one to three dimensions as in group_occurrences; ENTITY is the entity a " +
        "row matched, so on a row " +
        "through a view it names the view or view column. Example, who uses a table through views, by view and " +
        "routine, in one call: key the table, include [VIEWS], via THROUGH_VIEWS, group_by ENTITY, CONTAINER. " +
        "For a table, CHILDREN with VIEWS also follows the view columns computed from its columns. include " +
        "defaults by kind as in find_usages; the answer's included lists what was followed."

    private const val VALUE_WHY =
        "Use it, not the value_kind and value filters of find_occurrences, for one parameter or column: those " +
            "filters match any argument or written value of an occurrence, not the one this parameter receives or " +
            "this column is written with; for a parameter of a body subprogram, the arguments bound to its spec's " +
            "parameter count too (calls bind the spec); for a column, the writes through the views that reach it " +
            "count too."

    private const val VALUE_KIND_NOTES =
        "STRING, NUMBER, BOOLEAN and NAME carry a value: a string without its quotes (S, not 'S'), a number as a " +
            "canonical number (1.5 for 1.50), TRUE or FALSE, a name as an identifier, upper case unless quoted (a " +
            "variable, constant, column or record field: R.CODE). BIND, EXPRESSION, NULL and DEFAULT carry none, and each is one " +
            "group: BIND a bind variable, EXPRESSION anything computed (a call, a concatenation, a sum), NULL " +
            "null or '', DEFAULT a column written with the keyword DEFAULT (insert ... values (default), update " +
            "... set c = default), so the column's default applies. An argument left out of a call is not indexed, " +
            "so a parameter's default is not counted."

    const val VALUES = "The values passed to a PARAMETER or written to a COLUMN, grouped by valueKind and " +
        "value, most frequent first, each with its count; totalGroups counts every group. $VALUE_WHY " +
        "$VALUE_KIND_NOTES Then find_value_sites, with a group's valueKind and value, lists where it comes from. " +
        "Any other kind of entity is an error."

    const val VALUE_SITES = "Where a PARAMETER is passed or a COLUMN is written with one value: the calls or " +
        "writes, for the valueKind and value of one get_values group. value is required for STRING, NUMBER, " +
        "BOOLEAN and NAME and matched " +
        "exactly as get_values gives it (S, not 'S'); for BIND, EXPRESSION, NULL and DEFAULT it is ignored and " +
        "every row of the kind matches. $VALUE_WHY Rows as in find_usages, in source order. On a parameter's " +
        "row, arguments keeps only the argument bound to that parameter (or to its spec or body twin): the " +
        "call's other arguments are left out. A column's row is whole, with the values written. Pages as in " +
        "find_occurrences. Any other kind of entity is an error. $PATHS"

    const val GROUP = "Counts occurrences across the code, by routine, entity, role, file, status or value, with " +
        "one to three dimensions and the same filters as find_occurrences. A CRUD matrix of a package: " +
        "container_name or container the package, roles " +
        "SQL_SELECT, SQL_INSERT, SQL_UPDATE, SQL_DELETE, group_by CONTAINER, ENTITY, ROLE. ROLE counts an " +
        "occurrence once per role. A group value without entity is an occurrence tied to no entity; add STATUS " +
        "to group_by to tell proven occurrences from the others. To follow an entity's spec or body, contents or " +
        "views, use group_usages. Without any filter it counts the whole index, and the answer has wholeIndex. $STATUSES $NOT_ANSWERED"

    const val TEXT = "Lines of the indexed sources that contain a piece of text, compared without case: for text " +
        "that is not an entity or a value, such as an XML tag ('<vFixTag>' or XMLElement(\"vFixTag\")), a message, " +
        "a comment or SQL inside a string. Scope it in the same call: when the question names a package or " +
        "routine, pass that name as container_name (pack or pack.proc, as written); a file or folder, as path. " +
        "Search the whole index only when the question names no unit. Then call get_source on a row's path and line to read around " +
        "it. The text is literal, * included, and has at least 3 characters; each line is searched alone, so a text " +
        "across a line break is never found. Whole files are searched as indexed: comments, strings and inactive " +
        "conditional compilation branches included. path, container (a key) and container_name narrow the search, " +
        "combined with AND, a container with its contents; without them the whole index is searched, and the " +
        "answer has wholeIndex and narrow. Each row has path, line, text, and entity: the innermost package, " +
        "routine, trigger, view, type or cursor the line is in, when known. Rows come in file and line order; total counts every line found; pages: " +
        "pass nextAfter as after. stale is true when the file changed since indexing. $PATHS"

    const val SOURCE = "The source text of an indexed file around a line, as the index read it; stale is true " +
        "when the file changed since it was indexed. The files are on the server, not on the local disk: path is " +
        "relative to the server's sources folder, as the other tools give it."
}
