# reports/ — Transaction and inventory reports

**Status: placeholder. TODO: implement.** (Shown as "Reports" on the home screen via `ComingSoonModule`.)

**Responsibility:** read-only sales and inventory reports.

Planned folders: `service/`, `repository/`, `ui/`.

**Input today:** `pos.contract.SaleHistory` (completed sales by ID, invoice number, date range).
Get it from `AppContext.saleHistory()`.

**May depend on:** `shared`, `pos.contract`, `product.contract`, `inventory.contract`.
**Must not depend on:** `pos` internals, other modules' `ui` packages, `test/`. Reports never write.
