# system/ — Audit and System Management

**Status: placeholder. TODO: implement.** (Shown as "System & sync" on the home screen via `ComingSoonModule`.)

**Responsibility:** the audit log, terminal settings, backup, and system health screens.

Planned folders: `service/`, `repository/`, `ui/`.

**Today:** audit events go through `auth.contract.AuditLogger`, which `AppContext` currently
prints to the console. A database-backed logger belongs here; `AppContext.create(..., audit)`
already accepts a replacement.

**May depend on:** `shared`, `auth.contract` / `auth.permission`.
**Must not depend on:** `pos`, `product`, `inventory` internals, `test/`.
