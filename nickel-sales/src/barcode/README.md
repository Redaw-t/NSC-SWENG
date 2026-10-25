# barcode/ — Barcode Management (Iteration 2)

**Status: placeholder. TODO: implement.**

**Responsibility:** turning a scanned or typed barcode into a product (through
`product.contract.ProductLookup`), validating barcode formats, and assigning/printing barcodes
for products that have none.

Planned folders: `service/`, `ui/`.

**May depend on:** `shared`, `product.contract`.
**Must not depend on:** `pos.*`, `inventory.*` internals, `test/`.
It has no tables of its own unless the Product owner agrees to add a column or table.
