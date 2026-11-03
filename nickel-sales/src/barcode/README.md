# barcode/ — Barcode Management (Iteration 2)

**Status: placeholder. TODO: implement.**

**Responsibility:** turning a scanned or typed barcode into a product (through
`product.contract.ProductLookup`), validating barcode formats, and assigning/printing barcodes
for products that have none.

Planned folders: `service/`, `ui/`.

**Done so far (receipt barcode):** `service/Code128` (encoder + decoder for self-tests),
`service/EscPosBarcode` (printer raster picture), `ui/BarcodeView` (screen drawing). The receipt
prints a Code 128 of the invoice number (e.g. `T01-2026-000123`) above "THIS IS NOT AN OFFICIAL
RECEIPT"; scanning it types the invoice number, ready to look the sale up. Looking the sale up is
not built here yet.

**May depend on:** `shared`, `product.contract`.
**Must not depend on:** `pos.*`, `inventory.*` internals, `test/`.
It has no tables of its own unless the Product owner agrees to add a column or table.
