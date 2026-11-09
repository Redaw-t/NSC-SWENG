# sync/: every POS terminal's changes reach every other terminal

**Status: built** (phases 1–6 of the sync plan). Self-test: `sync.SyncCheck`, plus `sync.ApiCheck`
for the API.

## The idea in one picture

Each POS keeps its **own** MySQL database and keeps selling when the network or the Main POS is
down. The Main POS (T01) is the **hub**: every change goes through it, and it puts all of them in
one order that every PC applies.

```
 every PC:     action ──same transaction──► store tables + SyncOutbox (this PC's queue)
 Additional:   SyncOutbox ──push──► Main POS's SyncFeed      (the Main POS's own queue goes there too)
 Main POS:     SyncFeed numbers every change (Seq): ONE order for the whole store
 every PC:     SyncFeed after its place ──pull──► applied in order + its place moved, together
```

- **Nothing is lost.** A change and its queue row are saved in one transaction
  (`shared.SyncOutbox`). Applying a change and moving the PC's place in the list are one
  transaction too.
- **Nothing counts twice.** Each change has a UUID. The Main POS skips one it already has, and a PC
  skips a row it already has. So sending again after a dropped connection is harmless, and a PC's
  own changes coming back change nothing.
- **Every PC ends up the same.** Each PC applies every change in the Main POS's order, its own
  included, so the newest edit wins everywhere.
- **Main POS down:** an Additional POS keeps selling and its queue grows. It is sent, oldest first,
  when the Main POS is back. System Status shows "Main POS offline" and the number waiting.

## What is synced, and how (`shared.SyncTables`)

| Data | How |
| ---- | --- |
| Sale + SaleLine + Payment, Return + ReturnLine | Only added. One change per sale or return. IDs are UUIDs. |
| StockMovement | Only added, by its `GlobalID` (UUID). A **new** movement from another PC changes this PC's stock by the same amount. Stock is the sum of the movements, so two PCs selling at the same time add up correctly. |
| ActivityLog, StoreDetails | Only added, by `GlobalID`. The store details in use = the newest by `PerformedAt`. |
| Product, Category, Supplier, Users, Inventory.ReorderLevel | Edited: the whole row, newest in the list wins. `Users.LastSignIn` stays on each PC. |
| InvoiceSequence, BackupRecord, SyncLog, all of `nickelsales_appconfig` | **Never** synced (each PC's own). |

**New IDs** for products, users, categories and suppliers come from the Main POS
(`shared.MasterIds`, `MasterIdSources`, Main POS table `IdCounter`), so two terminals never make the
same one. On an Additional POS, **adding or editing these (and store details, reorder levels) needs
the Main POS to be reachable**. Otherwise the person sees "This needs the Main POS..."
(`shared.MainPosUnavailableException`). Sales, stock-ins, returns and changing your own password
always work.

**Stock below zero:** when two PCs sell the last item while they can't reach each other, both sales
are kept. Stock then shows below zero (the `CHECK` on `Inventory.QuantityOnHand` was removed) and the
Sync log gets a Failed line: "count it and adjust the stock". The app itself still never sells
more than a PC's own stock.

**A change a PC can't take** (e.g. a barcode another product there already has) is skipped, logged
as Failed in Settings > Sync Logs, and kept in `SyncFailed`. **Retry** applies it again once the
clash is fixed. Anything else that goes wrong (database down) stops the round, and the next round,
3 seconds later, tries again.

## Files

| File | What |
| ---- | ---- |
| `SyncEngine` | One round: push / pull / apply (Additional POS), or move own queue + apply (Main POS). Also the Main POS's `SyncHub`. |
| `SyncWorker` | Runs a round every 3 s on a background thread. |
| `SyncDb` | The sync tables in `nickelsales_appconfig` (`SyncOutbox`, `SyncFeed`, `SyncFailed`, `IdCounter`, setting `SyncAppliedSeq`). |
| `ChangeApplier` | Writes a change into this PC's store tables. Only synced tables and columns that exist here. |
| `SyncStatus` | What System Status shows. |
| `MasterIdSources` | Where new shared IDs come from (Main POS / ask the Main POS). |
| `api/` | The Main POS's API (`ApiServer`: pairing, first copy, `/api/sync/push`, `/pull`, `/next-id`) and its client (`MainPosClient`). |
| `discovery/` | Finding the Main POS on the network: it answers on **UDP 8766**. Used when the router changes its address, in Installation Setup (fills in the IP) and in Settings. |

Captured in the repositories: `MySqlSaleRepository`, `MySqlReturnRepository`,
`MySqlInventoryRepository`, `MySqlActivityLogRepository`, `MySqlStoreDetailsRepository`,
`MySqlProductRepository`, `MySqlCategoryRepository`, `MySqlSupplierRepository`,
`MySqlUserRepository`. Wired in `app.SyncRuntime` (started by `NickelSalesApp` once the PC is set
up). Recording is on only in the real app (`Main.start`), never in the self-tests.

## Network

- **TCP 8765**: the Main POS's API, which every Additional POS calls (shared API key in `X-API-Key`).
- **UDP 8766**: the Main POS answers "where is the Main POS?".
- The installer opens both in Windows Firewall, for the local network only (`InstallTasks.allowTerminalSync`).
- **Main POS's address changed** (the router): an Additional POS that can't reach it for 30 s
  looks for it on the network, checks it (answers as T01 and accepts the key) and follows it. If that
  can't work, an admin sets it in **Settings > General > Main POS** ("Check and Save" changes nothing
  unless the check passes).
- **An Additional POS's address changed:** nothing to do. The Main POS never calls it, and it notes
  the new address when the terminal next sends changes.

## Not covered

- Replacing a dead Main POS with a new PC (restore its latest backup there; the Additional POS's
  queues fill in what happened after it as long as the new PC has the same IP).
- Restoring a backup on one PC does not roll the others back.
- A PC that was off for longer than the Main POS keeps its list (90 days) misses the older changes:
  pair it again.

**May depend on:** `shared`, `system.contract` (Sync log), `pos.contract`, `product.contract`, `inventory.contract`.
**Must not depend on:** any `*.ui` package, other modules' internals, `test/`.
