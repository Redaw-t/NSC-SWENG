# Iteration 2 repository changes

**Question this page answers: "What did we change before Iteration 2, and why?"**

This is the change log of the repository cleanup done before Iteration 2 feature work. It is derived
from `git diff -M HEAD` against commit `5026ffb` ("fix: reupload + doc updates") and the working tree at
the time of writing. Nothing here is a plan: every entry describes something that was actually done,
except the clearly labelled "considered but not done" and "deferred" sections.

Related pages: [REPOSITORY_MAP.md](../REPOSITORY_MAP.md) (where code belongs),
[ARCHITECTURE.md](ARCHITECTURE.md) (how modules interact), [FILE_INDEX.md](FILE_INDEX.md) (important files).

## 1. Why the cleanup was performed

Four developers start Iteration 2 in parallel (Product, Inventory, POS integration, Barcode, Sync).
Before the cleanup:

- The Product and Inventory contracts lived inside `pos.contract`, so those modules would have had to
  depend on POS. `StockRecorder` even took POS's `SaleRecord`.
- The test stand-in `StubInventory` sat under `test/pos/stub/`, and the real entry point `app.Main`
  passes `null` for products and stock, so POS only worked through the test launcher `DevApp`.
- POS's SQL classes were mixed into `pos.checkout` with the checkout service.
- The user repository was in `auth.storage` while the rest of the code uses `repository`.
- Two overlapping Eclipse projects (root and `nickel-sales/`) were committed.
- There was no `docs/development/`, and the 1,000-line `nickel-sales/CONTRIBUTING.md` mixed setup, history and rules.

Behaviour was preserved: no login, session, role, user-management, POS cart, pricing, VAT, payment, invoice,
persistence or receipt logic was changed, and the database schema was not changed.

## 2. Files moved (OLD LOCATION → NEW LOCATION)

All paths are under `nickel-sales/src/` unless noted. Moves were made with `git mv`, so history follows the files.

### 2.1 Contracts extracted from POS into Product and Inventory

| Old location | New location | Reason |
|---|---|---|
| `pos/contract/ProductLookup.java` (`package pos.contract`) | `product/contract/ProductLookup.java` (`package product.contract`) | Product owns the product lookup contract; POS, Barcode and others consume it. |
| `pos/contract/ProductInfo.java` | `product/contract/ProductInfo.java` | The read-only product view belongs with its owner. |
| `pos/contract/StockRecorder.java` | `inventory/contract/StockRecorder.java` | Inventory owns stock deduction; POS calls it. |
| `pos/contract/InsufficientStockException.java` | `inventory/contract/InsufficientStockException.java` | Thrown for stock problems, so it belongs with Inventory. |

Product and Inventory must never depend on `pos`; moving these removes the only reason they would have to.

### 2.2 POS repository restructuring

| Old location | New location | Reason |
|---|---|---|
| `pos/checkout/SaleRepository.java` | `pos/repository/SaleRepository.java` | Repository interface separated from the checkout service. |
| `pos/checkout/MySqlSaleRepository.java` | `pos/repository/MySqlSaleRepository.java` | All sale SQL in one repository package. |
| `pos/checkout/PosTables.java` | `pos/repository/PosTables.java` | Schema check (information_schema SQL) is data-access code. |
| `pos/checkout/InvoiceNumberGenerator.java` | `pos/repository/InvoiceNumberGenerator.java` | It reads and updates `InvoiceSequence`, so it is data access. |

`pos.checkout` now holds `CheckoutService`, `PaymentService` and `CheckoutException` only. Class bodies were not changed;
only `package` lines, imports and Javadoc links.

### 2.3 Auth

| Old location | New location | Reason |
|---|---|---|
| `auth/storage/MySqlUserRepository.java` (`package auth.storage`) | `auth/repository/MySqlUserRepository.java` (`package auth.repository`) | Same naming as every other module (`<module>.repository`). One file; `AppContext` import updated. |

### 2.4 Test and stub isolation

| Old location | New location | Reason |
|---|---|---|
| `test/pos/stub/StubInventory.java` (`package pos.stub`) | `test/stubs/StubInventory.java` (`package stubs`) | One clearly named place for stand-ins, not nested under a production-looking `pos` package. |

`test/pos/stub/` no longer exists. `test/app/DevApp.java` stays where it was (still `package app`) because the run
scripts and `.run/` configuration start `app.DevApp`.

### 2.5 Documentation

| Old location | New location | Reason |
|---|---|---|
| `nickel-sales/CONTRIBUTING.md` | `docs/archive/ITERATION1_CONTRIBUTOR_GUIDE.md` | Kept in full for the Iteration 1 role specs and POS design history. An "archived, out of date" banner was added at the top. |

## 3. Files renamed

No class was renamed. Package renames (a package rename is also a path change, listed above):

| Old | New |
|---|---|
| `pos.contract.ProductLookup`, `ProductInfo` | `product.contract.ProductLookup`, `ProductInfo` |
| `pos.contract.StockRecorder`, `InsufficientStockException` | `inventory.contract.StockRecorder`, `InsufficientStockException` |
| `pos.checkout.SaleRepository`, `MySqlSaleRepository`, `PosTables`, `InvoiceNumberGenerator` | `pos.repository.*` |
| `auth.storage.MySqlUserRepository` | `auth.repository.MySqlUserRepository` |
| `pos.stub.StubInventory` | `stubs.StubInventory` |

`PassPolicy` was deliberately **not** renamed.

## 4. Files deleted

| Deleted | Reason |
|---|---|
| `.project` (repository root) | Duplicate Eclipse project that overlapped `nickel-sales/.project`; nothing referenced it. The Eclipse project is the `nickel-sales/` folder. |
| `.classpath` (repository root) | Same: it pointed into `nickel-sales/src`, `test`, `lib`. |

No Java file was deleted, and no user data or database content was touched. Build output (`nickel-sales/out/`) was never tracked.
The directory `test/pos/stub/` disappeared because its only file moved.

Also: the uncommitted VS Code Java extension edit to `nickel-sales/.project` (a `filteredResources` block) was reverted with `git checkout`.

## 5. Contract extraction: what POS depends on now

- POS imports **only** `product.contract.*` (`ProductLookup`, `ProductInfo`) and `inventory.contract.*`
  (`StockRecorder`, `StockLine`, `InsufficientStockException`) from those modules.
- `product/` and `inventory/` contain only a `contract/` package (plus README) and import nothing from `pos`.
- `AppContext.create(config, ProductLookup products, StockRecorder stock)` still takes interfaces; only the imports changed.

Files whose imports were updated for the moves: `app/AppContext`, `pos/cart/SalesService`, `PricingRule`,
`PricingService`, `ThresholdPricingRule`, `pos/checkout/CheckoutService`, `pos/ui/PosScreen`, `pos/ui/ProductSearchDialog`,
`app/AppConfig`, `pos/ui/PosModule`, and the tests `AppCheck`, `DevApp`, `CartCheck`, `CheckoutCheck`, `PosContractCheck`, `ReceiptCheck`.

## 6. `StockRecorder` signature change and why

| | Signature |
|---|---|
| Old (`pos.contract.StockRecorder`) | `void recordSale(SaleRecord sale, Connection tx) throws InsufficientStockException, SQLException` |
| New (`inventory.contract.StockRecorder`) | `void recordSale(String reference, List<StockLine> lines, Connection tx) throws InsufficientStockException, SQLException` |

New file: `inventory/contract/StockLine.java`, a record `StockLine(String productId, BigDecimal quantity)` (quantity is normalised
to 3 decimals and must be more than zero).

**Why:** the old parameter type `SaleRecord` is a POS class, so any Inventory implementation would have had to import `pos.contract`,
reversing the dependency direction. Inventory is now handed plain stock lines and a `reference` (POS passes the invoice number,
useful for Inventory's movement log). Transaction behaviour is unchanged: the call still happens inside `Database.inTransaction`,
and implementations must still use the given `Connection` and never commit, roll back or close it.

Code changed because of it: `CheckoutService.complete` now maps each `SaleLineRecord` to a `StockLine` and calls
`stock.recordSale(record.invoiceNo(), stockOut, tx)`. `StubInventory.recordSale` and the lambdas and calls in `CheckoutCheck` and
`PosContractCheck` were updated to the new signature (a small `stockOut(...)` helper was added to `PosContractCheck`).

## 7. Other edits made along the way

| File | Change | Reason |
|---|---|---|
| `src/app/Main.java` | Comment rewritten (mentions `product.contract.ProductLookup` and `inventory.contract.StockRecorder`; states it must not import `test/`) | Point Iteration 2 developers at the right contracts. Code unchanged (still passes `null, null`). |
| `src/app/NickelSalesApp.java` | Class comment and `IllegalStateException` text no longer mention `DevApp` | Production code should not describe the test launcher as normal. |
| `src/product/contract/ProductLookup.java` | Javadoc points to "a stand-in from test/stubs" | No production file names a test class. |
| `src/pos/contract/SaleRecord.java`, `pos/cart/CartException.java` | Javadoc references updated | Stale links to moved classes. |
| `nickel-sales/run-demo.bat`, `run-demo.sh` | Header text "Sprint 1 demo" replaced with "Nickel Sales Center Application - run and scripted checks" | Outdated wording. Behaviour unchanged. |
| `.gitignore` | Ignores a root `/.project` and `/.classpath`, `.factorypath`, `.apt_generated/`, `*.orig`, `*.bak`, `*.tmp`; Eclipse comment updated | Keep the duplicate Eclipse files and editor leftovers out. |
| `README.md` | "For developers" section and top link now point to `docs/development/`, `docs/REPOSITORY_MAP.md`; folder tree updated | Docs moved. |

## 8. Documentation restructuring

| Document | Answers |
|---|---|
| `docs/REPOSITORY_MAP.md` | Where does code belong? |
| `docs/development/ARCHITECTURE.md` | How should modules interact? |
| `docs/development/MODULE_TEMPLATE.md` | How do I build a new module? |
| `docs/development/NAMING_AND_STRUCTURE.md` | How should things be named? |
| `docs/development/FILE_INDEX.md` | What are the important existing files and classes? |
| `docs/development/ITERATION2_REPO_CHANGES.md` | This page |
| `docs/development/CONTRIBUTING.md` | How do I contribute safely? |
| `nickel-sales/CONTRIBUTING.md` (new, short) | Setup, environment variables, running, self-tests, database changes, troubleshooting |
| `docs/archive/ITERATION1_CONTRIBUTOR_GUIDE.md` | Old Iteration 1 guide, historical |

`database/README.md` was also added.

## 9. Placeholders added for Iteration 2 (README only, no Java except the two contract packages above)

| Path | State |
|---|---|
| `src/product/README.md` | Contracts exist; `model`, `service`, `repository`, `ui` are TODO |
| `src/inventory/README.md` | Contracts exist; `model`, `service`, `repository`, `ui` are TODO |
| `src/barcode/README.md` | Placeholder, TODO |
| `src/sync/README.md` | Placeholder, TODO |
| `src/reports/README.md` | Placeholder, TODO |
| `src/system/README.md` | Placeholder, TODO |
| `database/migrations/README.md`, `database/seed/README.md` | Placeholders, TODO |

No fake business classes were created.

## 10. Known architectural exceptions, deliberately deferred

Full table: "Known exceptions" in [ARCHITECTURE.md](ARCHITECTURE.md). In short:

- `pos.cart` ↔ `pos.checkout` package cycle (`SalesService` uses `CheckoutService`; `Sale`'s mutators are package-private).
- `app` ↔ `auth.ui` and `app` ↔ `pos.ui` (the home-screen module mechanism lives in `app`).
- `CheckoutService` calls `Database.inTransaction` directly (temporary; needed for the one sale + stock transaction).
- `AppConfig` and `AppContext` import `pos.repository` classes (wiring layer only).
- `pos.ui.PosModule` calls `PosTables.missing()`.
- `AuditLogger` still lives in `auth.contract` and is used by POS.
- `app.Main` still passes `null` for `ProductLookup` and `StockRecorder`, so POS is disabled in the production launcher until Product and Inventory are wired in.
  `DevApp` (test folder) is the temporary way to run POS with test products.
- "Products & inventory" is a single `ComingSoonModule` button (`Permission.UPDATE_INVENTORY`) in `app/Modules.java`.
- The SQL script has no Product or Inventory tables yet.
- Large classes were not split: `PosScreen`, `LoginScreen`, `SalesService`, `MySqlSaleRepository`, `AppContext`.
- `NSC-SWENG-Session-UI.iml` was not renamed.

**Considered but not done:** moving `SalesService` to a new `pos.service` package to break the cart/checkout cycle. It was tried and
reverted, because `Sale`'s mutating methods are package-private on purpose and the move forced them public.

## 11. What developers must NOT undo

1. **Do not put Product or Inventory contracts back into `pos`**, and never import `pos.*` from `product` or `inventory`.
2. **Do not change `StockRecorder` back to take `SaleRecord`** (or any other POS type). Change its signature only after agreeing with the POS and Inventory owners, and update `StubInventory` and the checks together.
3. **Do not make `Sale`'s mutators public** to move `SalesService` or to call them from elsewhere.
4. **Do not import anything from `test/` (including `stubs.*` and `DevApp`) in `src/`.** `git grep -nE "import (stubs|test)\." -- src` must print nothing, and `src/` must compile on its own.
5. **Do not move SQL out of `*.repository`/`shared.Database` into UI or service classes**, and don't add more repository calls to UI classes.
6. **Do not add a root-level `.project` or `.classpath`**; the Eclipse project is `nickel-sales/`.
7. **Do not revive `auth.storage` or `test/pos/stub`**, or recreate the old `pos.contract` Product/Stock classes.
8. **Do not use `shared/` as a dumping ground**; business logic belongs in its module.
9. **Do not change the `DevApp` and `app.*` names** without updating `run-demo.bat`, `run-demo.sh` and `.run/*.run.xml`.
10. **Keep the SQL script safe to re-run** (`CREATE TABLE IF NOT EXISTS`, upgrade blocks, `INSERT IGNORE`).

## 12. State when this was written

- `src/` compiles on its own (without `test/`).
- `run-demo --scripted`: the auth checks, `CartCheck` and `AppCheck` pass. `PosContractCheck`, `CheckoutCheck` and `ReceiptCheck` fail only
  on a database that lacks the POS tables. The local development database had only the `Users` table, so run
  `database/NickelSales-Database-Config.sql` (via `setup.bat`) to get a fully green run.
- All changes were in the working tree, uncommitted.
