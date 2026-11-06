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
- **The module's page** shows its code as PL/SQL, each trigger, program unit and record group query under a comment
  with its path: the module, the block and item it is on, and its name, such as
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

## Serving

The server rebuilds a module's page from its XML file: keep the `_fmb.xml` files under the folder `serve` reads with
`--sources`.

Other export formats can be read by a reader in a JAR in the `readers` folder of the distribution
(`/opt/zpa-atlas/readers` in the container). `index` and `serve` both load them: see [Development](development.md#forms-readers).
