# database/

| Path | What it is |
|---|---|
| `NickelSales-Database-Config.sql` | The schema only (tables, no data). Every table is InnoDB. Safe to run again: it only creates what is missing and upgrades older databases (missing columns/indexes, non-InnoDB tables). The setup scripts (`nickel-sales/setup.*`, `setup-database.*`) run it, and so does the installer. |
| `NickelSales-AppConfig.sql` | `nickelsales_appconfig`, a separate database for this installation's set-up state: `PosTerminals` (which PC is T01, T02 ... and their addresses) and `AppSettings` (this PC's terminal ID, API key ...). Filled by the app's Installation Setup screen. Safe to run again. The setup scripts and the installer both run it. |
| `NickelSales-Demo-Data.sql` | Demo users (password `Password123`) and a test catalog with starting stock. **Development databases only, never a store's.** The setup scripts run it after the schema; the installer never does. The self-tests create their own fixture (`test/stubs/TestCatalog`). |
| Audit and System tables | `ActivityLog` (append-only, never updated or deleted), `SyncLog` and `BackupRecord`. Owned by `nickel-sales/src/system/`. |
| `migrations/` | TODO. Placeholder for versioned changes, once the schema outgrows one script. |
| `seed/` | TODO. Placeholder for sample data (products, stock) for development. |

Rules for Iteration 2 (see [CONTRIBUTING](../docs/development/CONTRIBUTING.md)):

- Each module owns its tables. Add yours to the schema script in the same way the POS tables are
  (`CREATE TABLE IF NOT EXISTS ... ENGINE=InnoDB`, then an "upgrade" block for columns added later,
  and a line in the InnoDB block at the end). Test rows go in `NickelSales-Demo-Data.sql`, not the schema.
- Never edit or drop another module's table without telling its owner.
- Only the module's own repository classes (`MySql...Repository`) touch its tables.
- Never put real passwords in this folder.
