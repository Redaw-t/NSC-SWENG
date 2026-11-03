# inventory/ — Inventory Management (Iteration 2)

**Status: core done (model, repository, service, tests). TODO: screens (`ui/`), history/list queries, barcode hook.**

**Responsibility:** stock levels and stock movements: stock in, stock out (including the stock-out
for a completed sale), adjustments, reorder levels, low-stock rules, movement history.

| Folder | State | What goes there |
|---|---|---|
| `contract/` | **Exists** | `StockRecorder` (called by POS inside the checkout transaction), `StockLine`, `InsufficientStockException`, and `StockLevels` (read-only current stock, used by Product to fill `ProductInfo.stockOnHand`). Do not change without telling the POS / Product owners. |
| `model/` | **Done** | `StockLevel`, `StockMovement` (checks its own numbers), `MovementType`, `StockReason`, `StockStatus`. |
| `service/` | **Done** (core) | `InventoryService` (stock in/out, adjust, reorder level; implements `StockRecorder`), `InventoryStockLevels` (implements `StockLevels`), `InventoryAccess` (session + `UPDATE_INVENTORY` check), `InventoryException`. |
| `repository/` | **Done** (core) | `InventoryRepository` (interface) and `MySqlInventoryRepository`. All SQL lives here. TODO: list/search/history/summary queries for the two screens. |
| `ui/` | TODO | `InventoryModule` (registered in `app.Modules`) and the screens: Current Stock, Stock Movement History, Stock In / Stock Out / Adjust Stock windows. |

**Tables** (in `database/NickelSales-Database-Config.sql`): `Inventory` (stock per product; the database
refuses negative stock) and `StockMovement` (every change, signed quantity, before/after, reason,
reference, note, who). A product gets its `Inventory` row the first time it is stocked.

**Wiring in `Main`** (no construction cycle: `InventoryStockLevels` does not need `ProductLookup`):

```java
InventoryRepository inventoryRepo = new MySqlInventoryRepository();
StockLevels levels = new InventoryStockLevels(inventoryRepo);      // give this to ProductService
// ProductService products = new ProductService(productRepo, levels, ...);   (Product owner)
InventoryService inventory = new InventoryService(inventoryRepo, products,
        InventoryAccess.of(app.auth(), app.authz()), app.auditLogger());
// AppContext.create(config, products /* ProductLookup */, inventory /* StockRecorder */);
app.register(InventoryService.class, inventory);
```

**Important:** `StockRecorder.recordSale` uses the `Connection` it is given and never
commits, rolls back or closes it. POS saves the sale and the stock-out in one transaction.

**May depend on:** `shared`, `product.contract`, `auth.*` (roles), its own folders.
**Must not depend on:** `pos.*` (not even `pos.contract`), `test/`.

Tests: `test/inventory/InventoryCheck.java` (model + service rules run with no database, using
`test/stubs/InMemoryInventoryRepository`; the MySQL part is skipped when MySQL is not reachable).

See [docs/development/MODULE_TEMPLATE.md](../../../docs/development/MODULE_TEMPLATE.md).