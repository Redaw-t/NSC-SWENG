# Repository map

**Question this page answers: "Where does code belong?"**

It is the high-level folder and package map: what each folder is for, what must stay out of it,
which Iteration 2 feature area owns it, and which way dependencies may point.
Other documents answer the other questions:

| Document | Answers |
|---|---|
| **REPOSITORY_MAP.md** (this page) | Where does code belong? |
| [development/ARCHITECTURE.md](development/ARCHITECTURE.md) | How should modules interact? |
| [development/MODULE_TEMPLATE.md](development/MODULE_TEMPLATE.md) | How do I build a new module? |
| [development/NAMING_AND_STRUCTURE.md](development/NAMING_AND_STRUCTURE.md) | How should things be named? |
| [development/FILE_INDEX.md](development/FILE_INDEX.md) | What are the important existing files and classes? |
| [development/ITERATION2_REPO_CHANGES.md](development/ITERATION2_REPO_CHANGES.md) | What did we change before Iteration 2, and why? |
| [development/CONTRIBUTING.md](development/CONTRIBUTING.md) | How do I contribute safely? |

Paths are under `nickel-sales/` unless noted. "contract" means the module's `contract/` package,
the only part other modules should import.

## 1. What belongs in each source folder (`src/`)

| Package / Folder | Belongs here | Does NOT belong here | Iteration 2 feature area / owner |
|---|---|---|---|
| `app/` | Entry point (`Main`), wiring (`AppContext`, `AppConfig`), the module list (`Modules`), the home-screen shell (`AppModule`, `ScreenContext`, `HomeScreen`, `DashboardRouter`, `IdleLogoutTimer`) | Business rules, SQL, any module's screens or services | Shared. **Shared UI shell:** small additive edits only; wiring of Product/Inventory happens here |
| `auth/` | Login, sessions, users, roles and permissions, user-admin screens (`contract`, `login`, `account`, `permission`, `session`, `repository`, `ui`) | Anything about sales, products or stock | User and Access Management (Iteration 1, done); maintenance only |
| `pos/` | Selling: `cart` (cart, pricing, `SalesService`), `checkout` (payment, checkout transaction), `receipt` (build and print), `repository` (sale and invoice-number SQL), `contract` (sale records, `SaleHistory`, `SaleExport`), `ui` (cashier screens) | Product catalog or stock logic, product/inventory SQL, test stand-ins | **POS review and stabilisation; POS to Product/Inventory integration** (Nathan's slice) |
| `product/` | The product catalog: `contract` now (`ProductLookup`, `ProductInfo`); later `model`, `service`, `repository`, `ui` | Stock quantities' business rules, anything from `pos` | **Product Management + data layer** |
| `inventory/` | Stock levels and movements: `contract` now (`StockRecorder`, `StockLine`, `InsufficientStockException`); later `model`, `service`, `repository`, `ui` | Anything from `pos` (including `pos.contract`), product catalog editing | **Inventory Management** |
| `barcode/` | Barcode to product lookup, barcode validation and generation (README only for now) | A second product lookup, SQL on product tables | **Barcode Management** |
| `sync/` | Sending and receiving data with the central server, working from exported contracts (README only for now) | Screens, direct reads of POS internals | **Synchronization / API foundation** |
| `reports/` | Read-only sales and inventory reports (README only for now) | Writes of any kind, POS internals | Transaction and Reports (later iteration) |
| `system/` | Audit log, settings, backup (README only for now) | Sales, product or stock rules | Audit and System Management (later iteration) |
| `shared/` | Only cross-module infrastructure: `Database`, `Money`, `Quantity`, `PhTime`, `UiErrors` | Business logic of any module, "helpers", anything that knows about products, sales, users or stock | Whole team; additions need team agreement |

## 2. Allowed dependencies per folder

| Package / Folder | May depend on | Must not depend on |
|---|---|---|
| `app/` | every module (to wire it), `shared` | `test/`; business logic belongs to the modules |
| `auth/` | `shared`, `app` (shell API from `ui` only) | `pos`, `product`, `inventory`, `test/` |
| `pos/` | `product.contract`, `inventory.contract`, `auth.contract`/`auth.permission`, `shared`, `app` (shell API from `ui` only) | `product`/`inventory` internals (`service`, `repository`, `ui`), `reports`, `sync`, `test/` |
| `product/` | `shared`, `auth.*` (roles) | `pos.*` (any), `inventory` internals, `test/` |
| `inventory/` | `shared`, `product.contract`, `auth.*` | `pos.*` (any, including `pos.contract`), `test/` |
| `barcode/` | `shared`, `product.contract` | `pos` internals, `inventory` internals, `test/` |
| `sync/` | `shared`, `pos.contract` (`SaleExport`), `product.contract`, `inventory.contract` | any `*.ui`, `pos` internals, `test/` |
| `reports/` | `shared`, `pos.contract` (`SaleHistory`), `product.contract`, `inventory.contract` | `pos` internals, other modules' `ui`, `test/` |
| `system/` | `shared`, `auth.contract`/`auth.permission` | `pos`, `product`, `inventory` internals, `test/` |
| `shared/` | JDK only | every module, `test/` |

Inside one module the direction is `ui -> service -> repository interface <- MySql repository -> shared.Database`
(see [ARCHITECTURE.md](development/ARCHITECTURE.md)).

## 3. Test and support folders

| Folder | Belongs here | Must not be depended on by |
|---|---|---|
| `test/auth/`, `test/pos/`, `test/app/AppCheck` | Self-tests (`*Check`, `*Demo`) run by `run-demo --scripted` | anything in `src/` |
| `test/stubs/` | Stand-ins for modules that don't exist yet (`StubInventory` implements `ProductLookup` and `StockRecorder`) | anything in `src/` |
| `test/app/DevApp` | Development launcher: `Main`'s wiring plus `StubInventory`, so POS can be tried | anything in `src/` |

Tests and stubs may use anything in `src/`. Nothing in `src/` may use them.

## 4. Other top-level folders (repository root)

| Path | Purpose |
|---|---|
| `database/NickelSales-Database-Config.sql` | The one schema script (safe to re-run); `migrations/` and `seed/` are TODO placeholders |
| `docs/` | This map; `development/` (architecture, template, naming, file index, change log, contributing); `archive/` (old Iteration 1 guide, out of date) |
| `nickel-sales/setup.*`, `setup-database.*`, `run-demo.*` | Setup and run scripts (also `--console`, `--scripted`) |
| `nickel-sales/lib/` | Bundled jars; `lib/javafx/` is downloaded by setup and ignored by git |
| `nickel-sales/.project`, `.classpath` | The Eclipse project (the only one; none at the root) |
| `.idea/`, `NSC-SWENG-Session-UI.iml`, `.run/` | Shared IntelliJ setup and run configurations |
| `.github/modernize/` | Tool-generated, ignored by its own `.gitignore` |

## 5. Allowed module-to-module dependencies

```
                  +------ app (Main, AppContext, Modules) wires everything ------+
                  |                                                              |
 pos ----> product.contract        reports ---> pos.contract (SaleHistory)
  |------> inventory.contract      sync ------> pos.contract (SaleExport)
  |------> auth.contract/permission barcode --> product.contract
                                   inventory -> product.contract
 everything ---> shared            product, inventory: never import pos
```

## 6. Known remaining exceptions

See "Known exceptions" in [development/ARCHITECTURE.md](development/ARCHITECTURE.md), and
[development/ITERATION2_REPO_CHANGES.md](development/ITERATION2_REPO_CHANGES.md) for the deferred items.
For individual files and classes, see [development/FILE_INDEX.md](development/FILE_INDEX.md).
