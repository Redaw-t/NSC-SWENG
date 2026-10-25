# database/

| Path | What it is |
|---|---|
| `NickelSales-Database-Config.sql` | The schema and starter users. Safe to run again: it only creates what is missing and adds columns/indexes that older databases lack. The setup scripts (`nickel-sales/setup.*`, `setup-database.*`) run it. |
| `migrations/` | TODO. Placeholder for versioned changes, once the schema outgrows one script. |
| `seed/` | TODO. Placeholder for sample data (products, stock) for development. |

Rules for Iteration 2 (see [CONTRIBUTING](../docs/development/CONTRIBUTING.md)):

- Each module owns its tables. Add yours to the script in the same way the POS tables are
  (`CREATE TABLE IF NOT EXISTS`, then an "upgrade" block for columns added later).
- Never edit or drop another module's table without telling its owner.
- Only the module's own repository classes (`MySql...Repository`) touch its tables.
- Never put real passwords in this folder.
