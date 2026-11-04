# system/ — Audit and System Management

**Status: all five tabs built (Activity Logs, Inventory Logs, Sync Logs, System Status, Backup & Restore).
Sync Logs fills up once the Sync module is connected. Old logs are never cleared: see Retention.**
Opened from the sidebar's "Audit Logs" entry (Admin only: `MANAGE_SYSTEM`) via `ui.SystemModule`.

**Responsibility:** the audit trail, sync logs, system health and backup/restore screens.

| Folder | What is in it |
|---|---|
| `contract/` | For other modules: `SyncLogWriter`, `SyncRetryHandler` (Sync), `StatusProvider` (a component's health) |
| `model/` | `AuditEvent` (splits an audit line into columns), `ActivityDescription` (Details wording), log entries and filters, `BackupRecord`, `StatusItem` |
| `repository/` | Activity, inventory, sync and backup-history repositories with their MySQL versions; `DatabaseDump` (writes, checks and restores a backup file) |
| `service/` | `ActivityLogger` (the app's `AuditLogger`), one service per tab, `SystemAccess`, `BackupFolderSetting` |
| `ui/` | `SystemScreen` (tabs and keyboard shortcuts, laid out like the Reports design), one `SystemPane` per tab, `SystemUi` (shared pieces), `UserNames` (names for a tab's rows, looked up once per load) |

**Tables:** `ActivityLog`, `SyncLog` and `BackupRecord` belong to this module. Inventory Logs only reads
Inventory's `StockMovement` table.

**Sync:** get `SyncLogWriter` with `app.find(SyncLogWriter.class)` and call `attempt`, then `succeeded` or
`failed`. Connect the Retry button with `app.find(SyncLogService.class).get().setRetryHandler(...)`.
Until then the tab says Sync is not connected and shows how many sales are unsent.

**Backup and restore:** files are gzipped SQL (`nickelsales-T01-20261104-100000.sql.gz`) written over the
app's own connection, so no MySQL tools are needed on the terminal. The folder is a per-terminal setting.
A restore checks the file, makes a safety backup (and stops if it cannot), and puts the safety backup back
if it fails part-way. It replaces every table in the backup, so the whole window is locked while it runs
and the admin is signed out afterwards. The activity log is one of those tables, so entries made after the
backup are gone from the live log; the safety backup keeps them. A backup's checks catch a damaged, cut-off
or wrong file, and a changed statement count or header. They do not catch a value edited inside a statement.

**Retention:** System Status warns 5 days before the oldest logs reach 30 days (NFR-11, FR-19), and again
after. Nothing is deleted, automatically or by hand: the log is append-only, and that is a deliberate
choice over NFR-11's "refreshing". A backup keeps a copy.

**Keyboard (FR-20):** Ctrl+1 to Ctrl+5 open the tabs, Ctrl+F goes to the search box, Ctrl+E exports, F5
reloads, and Enter in a date box applies the filters. Esc closes a pop-up. The shortcuts belong to this
screen only.

**Searching:** the log stores action codes and builds its sentences on screen, so
`ActivityDescription.actionsMatching` turns the words the admin sees ("Sign-in", "Stock removed") back
into codes. A new sentence in `describe` also needs its opening words in `OPENINGS`.

**Wording:** the Module column stores `Login` and shows it as `Sign-in` (`AuditEvent.moduleLabel`), so
older rows and the filter keep working.

**How events get here:** every module already calls `auth.contract.AuditLogger.log(event, userId)`.
`app.Main` passes them all an `ActivityLogger`, which saves one `ActivityLog` row per event (and still
prints it). Saving never blocks the action: if the database is down, the problem is printed and the
login, sale or change carries on. Event format, read by `AuditEvent.parse`:

    ACTION [target=ID] [name=old>new ...] [other words]
    e.g. PRICE_CHANGED target=P-0001 retail=250.00>260.00 wholesale=none>240.00

Without `target=`, the second word is the target (`STOCK_IN P-0001 +5`, `SALE_COMPLETED T01-2026-000143`).
The module (Login, Users, Products, Inventory, POS, System, Other) comes from the action's name.

**Details wording (`model.ActivityDescription`):** the Details column, the View pop-up and the CSV
export all show one sentence per entry, made when the entry is shown (so older entries read the
same as new ones). Every sentence follows this convention:

1. One complete sentence in sentence case, ending with a period.
2. *What happened*, then a colon and the *specifics* when there are any:
   `Stock removed: quantity 2 (reason: damage).`
3. Facts only, in the past tense: no guesses about why, no informal words.
4. Business words, never codes: "sign-in", "count correction", not `LOGIN_FAILED`, `COUNT_CORRECTION`.
5. Who, what and where stay in their own columns (User, Target, Terminal); the sentence adds the rest.
6. Unsuccessful attempts start with `Sign-in failed:` (wrong details) or `Sign-in denied:`
   (correct details, but the account may not sign in).

A module that adds a new audit event should add its sentence there (and a line to the self-test).

**Self-tests:** `test/system/SystemLogCheck` and `SystemAdminCheck` (part of `run-demo --scripted`).
`SystemAdminCheck` restores only into its own throw-away databases; the real one is only read.

**May depend on:** `shared`, `auth.contract` / `auth.permission`, `app` (module and screen wiring).
**Must not depend on:** `pos`, `product`, `inventory` internals, `test/`.
