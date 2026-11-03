# sync/ — Synchronization / API foundation (Iteration 2)

**Status: placeholder. TODO: implement.**

**Responsibility:** sending local data to the central server and receiving updates, over the
network. Works from exported contracts, never from screens.

Planned folders: `contract/`, `service/`, `repository/`.

**Input today:** `pos.contract.SaleExport`, which lists completed sales not yet synced
(oldest first) and has `markSynced`. Get it from `AppContext.saleExport()`.

**May depend on:** `shared`, `pos.contract`, `product.contract`, `inventory.contract`.
**Must not depend on:** any `*.ui` package, `pos` internals (`cart`, `checkout`, `repository`), `test/`.
