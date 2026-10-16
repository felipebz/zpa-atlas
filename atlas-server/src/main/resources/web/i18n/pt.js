"use strict";

// Mensagens em português (Brasil). Mesmas chaves de en.js; chaves ausentes caem para o inglês.
registerMessages("pt", {
  "page.search.title": "zpa-atlas",
  "page.entity.title": "zpa-atlas · entidade",
  "page.entity.titleWith": "zpa-atlas · {name}",

  "banner.loading": "carregando…",
  "banner.run": "Índice de {date} · schema {schema} · metadados {metadata}",
  "banner.none": "(nenhum)",
  "banner.unavailable": "Índice indisponível: {error}",
  "banner.warning": "Atenção: {problems}. Respostas que tocam esses arquivos podem estar incompletas.",
  "banner.failed": { one: "{count} arquivo com falha (FAILED)", other: "{count} arquivos com falha (FAILED)" },
  "banner.recovered": {
    one: "{count} arquivo com erro de parse recuperado (RECOVERED)",
    other: "{count} arquivos com erro de parse recuperado (RECOVERED)"
  },
  "banner.separator": "; ",

  "search.placeholder": "Nome (prefixo) ou nome com ponto (ex.: pack.run_job)",
  "search.allKinds": "todos os tipos",
  "search.includeExternal": "incluir externas",
  "search.submit": "Buscar",
  "search.empty": "Nada encontrado.",
  "search.column.entity": "Entidade",
  "search.column.kind": "Tipo",
  "search.column.usages": "Usos",

  "entity.external": "externa",
  "entity.schema": "schema {schema}",
  "entity.schemaUnknown": "(desconhecido)",
  "entity.parent": "pertence a",
  "entity.missingKey": "Nenhuma entidade indicada.",
  "entity.definitions": "Definição e declaração",
  "entity.overloads": "Sobrecargas",
  "entity.overload.spec": "spec",
  "entity.overload.body": "body",
  "entity.relations": "Relações",
  "entity.children": "Conteúdo",

  "usage.title": "Quem usa",
  "usage.direct": { "=0": "nenhum uso direto", one: "{count} uso direto", other: "{count} usos diretos" },
  "usage.candidate": {
    "=0": "candidata em nenhuma ocorrência ambígua",
    one: "candidata em {count} ocorrência ambígua",
    other: "candidata em {count} ocorrências ambíguas"
  },
  "usage.summary": "{direct}; {candidate}.",
  "usage.scope": "Escopo:",
  "usage.scope.implementations": "incluir spec/body (IMPLEMENTS)",
  "usage.scope.children": "incluir conteúdo (colunas, membros)",
  "usage.noDirect": "Nenhum uso direto.",
  "usage.ambiguous": "Ambíguas (pode ser esta)",
  "usage.candidates": "candidatas:",
  "usage.more": "carregar mais",
  "filters.clear": "Limpar filtros",

  "role.DEFINITION": "definição",
  "role.DECLARATION": "declaração",
  "role.READ": "leitura",
  "role.WRITE": "escrita",
  "role.CALL": "chamada",
  "role.SQL_SELECT": "SQL select",
  "role.SQL_INSERT": "SQL insert",
  "role.SQL_UPDATE": "SQL update",
  "role.SQL_DELETE": "SQL delete",
  "role.SQL_MERGE": "SQL merge",
  "role.SQL_LOCK": "SQL lock",
  "role.TYPE_USE": "uso de tipo",
  "role.RAISE": "lançamento",
  "role.HANDLE": "tratamento",
  "role.IMPLICIT": "implícito",

  "source.show": "ver linha",
  "source.stale": "O arquivo mudou desde a indexação.",
  "source.unavailable": "Fonte indisponível ({error})",
  "error.message": "Erro: {error}"
});
