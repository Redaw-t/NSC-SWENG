# inventory/ — Inventory Management (Iteration 2)

**Status: contracts only. TODO: implement.**

**Responsibility:** stock levels and stock movements: stock in, stock out (including the stock-out
for a completed sale), adjustments, low-stock rules, movement log.

| Folder | State | What goes there |
|---|---|---|
| `contract/` | **Exists** | `StockRecorder` (called by POS inside the checkout transaction), `StockLine`, `InsufficientStockException`. Do not change without telling the POS owner. |
| `model/` | TODO | Stock level, stock movement. |
| `service/` | TODO | Use cases and rules. Implements `StockRecorder`. |
| `repository/` | TODO | `InventoryRepository` (interface) and `MySqlInventoryRepository`. All SQL lives here. |
| `ui/` | TODO | `InventoryModule` (registered in `app.Modules`) and the screens. |

**Important:** `StockRecorder.recordSale` must use the `Connection` it is given and never
commit, roll back or close it. POS saves the sale and the stock-out in one transaction.

**May depend on:** `shared`, `product.contract`, `auth.*` (roles), its own folders.
**Must not depend on:** `pos.*` (not even `pos.contract`), `test/`.

See [docs/development/MODULE_TEMPLATE.md](../../../docs/development/MODULE_TEMPLATE.md).
