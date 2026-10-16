"use strict";

// English messages. A translation is one file like this one, registered in LOCALES in common.js.
// Values are plain text, never HTML; {name} is a placeholder; an object holds one message per plural category,
// and optionally exact forms such as "=0".
registerMessages("en", {
  "page.search.title": "zpa-atlas",
  "page.entity.title": "zpa-atlas · entity",
  "page.entity.titleWith": "zpa-atlas · {name}",

  "banner.loading": "loading…",
  "banner.run": "Index of {date} · schema {schema} · metadata {metadata}",
  "banner.none": "(none)",
  "banner.unavailable": "Index unavailable: {error}",
  "banner.warning": "Warning: {problems}. Answers that touch these files may be incomplete.",
  "banner.failed": { one: "{count} file failed (FAILED)", other: "{count} files failed (FAILED)" },
  "banner.recovered": {
    one: "{count} file with a recovered parse error (RECOVERED)",
    other: "{count} files with a recovered parse error (RECOVERED)"
  },
  "banner.separator": "; ",

  "search.placeholder": "Name (prefix) or dotted name (e.g. pack.run_job)",
  "search.allKinds": "all kinds",
  "search.includeExternal": "include external",
  "search.submit": "Search",
  "search.empty": "Nothing found.",
  "search.column.entity": "Entity",
  "search.column.kind": "Kind",
  "search.column.usages": "Uses",

  "entity.external": "external",
  "entity.schema": "schema {schema}",
  "entity.schemaUnknown": "(unknown)",
  "entity.parent": "belongs to",
  "entity.missingKey": "No entity given.",
  "entity.definitions": "Definition and declaration",
  "entity.overloads": "Overloads",
  "entity.overload.spec": "spec",
  "entity.overload.body": "body",
  "entity.relations": "Relations",
  "entity.children": "Contents",

  "usage.title": "Used by",
  "usage.direct": { one: "{count} direct use", other: "{count} direct uses" },
  "usage.candidate": {
    one: "candidate in {count} ambiguous occurrence",
    other: "candidate in {count} ambiguous occurrences"
  },
  "usage.summary": "{direct}; {candidate}.",
  "usage.scope": "Scope:",
  "usage.scope.implementations": "include spec/body (IMPLEMENTS)",
  "usage.scope.children": "include contents (columns, members)",
  "usage.noDirect": "No direct uses.",
  "usage.ambiguous": "Ambiguous (may be this one)",
  "usage.candidates": "candidates:",
  "usage.more": "load more",
  "filters.clear": "Clear filters",

  // Roles without a label are shown by their stored name.
  "role.TYPE_USE": "type use",

  "source.show": "show line",
  "source.stale": "The file changed since it was indexed.",
  "source.unavailable": "Source unavailable ({error})",
  "error.message": "Error: {error}"
});
