# barcode/ — Barcode Management (Iteration 2)

**Status: built (Barcode tab of the Products screen).**

**Responsibility:** turning a scanned or typed barcode into a product (through
`product.contract.ProductLookup`), validating barcode formats, and assigning/printing barcodes
for products that have none.

Folders: `service/`, `ui/`.

**Done so far (receipt barcode):** `service/Code128` (encoder + decoder for self-tests),
`service/EscPosBarcode` (printer raster picture), `ui/BarcodeView` (screen drawing). The receipt
prints a Code 128 of the invoice number (e.g. `T01-2026-000123`) above "THIS IS NOT AN OFFICIAL
RECEIPT"; scanning it types the invoice number, ready to look the sale up. Looking the sale up is
not built here yet.

**The Barcode tab** (`ui/BarcodePane`, shown by `product.ui.ProductScreen`):

| Piece | What it does |
|---|---|
| `BarcodePane` | Search, Barcode Status filter, **+ Assign Barcode**, and the table (Product Code, Name, Barcode, Status, Last Updated, Actions). Assigned rows get View / Reassign / Print, unassigned rows get Assign. |
| `AssignBarcodeDialog` | Pick a product (fixed when reassigning), then **Scan** (clears the box and waits: a scanner types the code and presses Enter), **Generate** (unused store-only EAN-13) or type it. Reassign asks "Replace?" first. |
| `PrintLabelDialog` | Product, barcode, copies (1-100) and a preview; prints through `LabelPrinterPort`. |
| `BarcodeDetailsDialog` | Read-only View: format, status, problem, last updated, label preview. |
| `service/BarcodeService` | `rows()` (status per product), `problemWith`, `assign`, `suggestBarcode`, `printLabels`. |
| `service/BarcodeStatus` | `ASSIGNED`, `UNASSIGNED`, `ERROR` (shown as "Duplicate / Error": wrong check digit, spaces, or two products with the same code). |
| `service/Ean` | EAN-13 / UPC-A / EAN-8 bars for the preview, the same bars the printer's own EAN command prints. Other codes preview as Code 128. |

Products are listed and saved ONLY through `product.contract.BarcodeAssigner` (implemented by
`product.ui.ProductBarcodes` on top of `ProductService`, which checks `MANAGE_PRODUCTS` and writes the
audit log `PRODUCT_BARCODE_ASSIGNED` / `PRODUCT_BARCODE_REASSIGNED`). Only the barcode column is
written, so nothing else another terminal just edited is overwritten. The unique index on
`Product.Barcode` is still the real guarantee against duplicates. A row with an Error status cannot
be printed until it is reassigned. "Last Updated" is the product row's `UpdatedAt`, so it also moves
when the product is edited for another reason.

**May depend on:** `shared`, `product.contract`.
**Must not depend on:** `pos.*`, `inventory.*` internals, `test/`.
It has no tables of its own unless the Product owner agrees to add a column or table.
