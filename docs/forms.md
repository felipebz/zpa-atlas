# Oracle Forms modules

Atlas indexes the PL/SQL of your Oracle Forms modules next to the database code: triggers, program units and record
group queries. The uses of a table or a package then list the Forms code that uses it, and each module has a page with
its code.

## Indexing the modules

1. **Export:** Atlas reads the XML that Oracle's `frmf2xml` writes (Forms 12), one `name_fmb.xml` per form. Export with
   `DUMP=ALL`, so that the file also holds the code the module inherits from other modules:

   ```sh
   frmf2xml OVERWRITE=YES DUMP=ALL orders.fmb
   ```

2. **Index:** put the `_fmb.xml` files anywhere under `--sources`, beside the database code. They are read whatever
   `--extensions` says:

   ```sh
   zpa-atlas index --sources path/to/sources --out atlas.db --compilation-schema HR
   ```

Object libraries (`_olb.xml`) and menu modules (`_mmb.xml`) are not indexed: the [coverage notes](concepts.md#coverage-notes)
count them.

## What you see

- **A module is known by the name it is called by**, as `CALL_FORM` and `OPEN_FORM` name it. By default that is its
  file's name: `orders_fmb.xml` is `ORDERS`. A reader whose files are named otherwise may say another name. Its `Name`
  property, when it is another, is shown on its page as its internal name. Two files with the same name, in two folders
  or under two file names, are one module, indexed from the first file by path.
- **The module's page** shows its objects as a tree, in the module's order, as the Form Builder's Object Navigator
  does: its triggers, data blocks with their items, program units, record groups and attached libraries. A trigger,
  program unit or record group query opens on the code page, one at a time, with the tree beside it. Each piece of code
  is named by its path: the module, the block and item it is on, and its name, such as
  `ORDERS/ORDERS/STATUS/WHEN-VALIDATE-ITEM`.
- **The module's objects are entities:** the module, its blocks, items, triggers, program units and attached libraries,
  keyed `forms:` and their path, such as `forms:ORDERS/CALC_TOTAL`. A package's specification and body are one
  entity, and the uses of its routines count as uses of the package; a routine it does not declare is not found.
- **Used by** lists the Forms code by its path, next to the database code.

A name the module's own program units declare means that program unit, even when the database has a routine with the
same name. The other names are the database's, as in an anonymous block compiled in `--compilation-schema`.

What to make of it:

- **Calls into an attached library** are not found, or point to a guessed database object: the library's code is not
  in the module.
- **A module exported without `DUMP=ALL`** may lack the code it inherits; a coverage note says so. Export it again.

## Data blocks

A data block on a table queries, inserts, updates and deletes it without a line of code. Atlas records that as uses of
the table and its columns, with the origin `FORMS_IMPLICIT` and no line:

- **A block on a table** is a database block whose query data source is a table it names. Its query uses the table as
  `SELECT`; its inserts, updates and deletes use its DML data target (the same table, unless the block names another)
  as `INSERT`, `UPDATE` and `DELETE`. An operation the block does not allow, or that a transactional trigger replaces
  (`ON-SELECT`, `ON-INSERT`, `ON-UPDATE`, `ON-DELETE`, the block's own or else the module's), is not recorded: the
  trigger's own code is.
- **An item on a column** is a database item that holds a value (not a button or a chart), in a block on a table. Its
  column is its `Column Name`, else its own name. It reads the column when its block queries, and writes it in the
  inserts and updates both it and its block allow.
- The table and its columns are found as the module's SQL finds them, in `--compilation-schema`: a column the table
  lacks is not found, and a block on `DUAL` uses no table, as a query of `DUAL` does not.
- The block's page shows its table, its DML target, each operation (done by Forms, replaced by a trigger, or not
  allowed) and its `WHERE` and `ORDER BY` clauses; the item's page shows its column.
- A property value Atlas does not know, such as a word of a Form Builder in another language, is not guessed: a coverage
  note lists it.

Not followed yet:

- the columns named in a block's `WHERE` and `ORDER BY` clauses;
- the lock Forms takes on a row before it updates or deletes it;
- data sources and targets other than a table (a procedure, a `FROM` clause query, transactional triggers): a coverage
  note counts the blocks;
- an item as a place a column's value is read into, with its size, in **Read into**;
- `:BLOCK.ITEM` and the module's other names in the code;
- master-detail relations.

## Serving

The server rebuilds a module's page from its XML file: keep the `_fmb.xml` files under the folder `serve` reads with
`--sources`.

Other export formats can be read by a reader in a JAR in the `readers` folder of the distribution
(`/opt/zpa-atlas/readers` in the container). `index` and `serve` both load them: see [Development](development.md#forms-readers).
