# File index

**Question this page answers: "What are the important existing files and classes?"**

Only entry points, contracts, services, repositories, UI classes, shared infrastructure, tests, scripts and
Iteration 2 starting points are listed; trivial files (exceptions, enums, small records) are skipped unless they are
part of a contract. For folder rules see [REPOSITORY_MAP.md](../REPOSITORY_MAP.md); for how modules interact see
[ARCHITECTURE.md](ARCHITECTURE.md).

Paths are relative to `nickel-sales/src/` (sections 1 to 10), `nickel-sales/test/` (section 11), or the repository root
(sections 12 and 13). Use the Java package name (folder path with dots) when importing.

**Modification guidance labels**

| Label | Meaning |
|---|---|
| **SAFE TO EXTEND** | The owner of that area can add to it freely; keep behaviour and public signatures. |
| **COORDINATE BEFORE CHANGING** | Other people's code depends on it, or it is a shared file. Talk to the owner first; make small additive edits. |
| **SHARED CONTRACT — CHANGE CAREFULLY** | Another module depends on this interface or record. A change breaks consumers and stand-ins; agree it with the consumer's owner and update `StubInventory` and the checks together. |
| **TEST ONLY** | Lives in `test/`. Never import from `src/`. |
| **DO NOT USE IN PRODUCTION** | Test stand-in or dev launcher. |
| **PLACEHOLDER** | Only a README so far; implement it as part of Iteration 2. |

## 1. app

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `app/Main.java` | The production entry point. Reads `AppConfig`, builds `AppContext`, launches the window. **Currently passes `null, null` for `ProductLookup` and `StockRecorder`, so POS is disabled here.** | App / shell (shared) | Uses `AppContext`, `NickelSalesApp`. Started by hand/IDE. | **COORDINATE BEFORE CHANGING.** Iteration 2 starting point: wire Product and Inventory here (replace the nulls, `app.register(...)`). Must not import `test/`. |
| `app/AppContext.java` | Builds the shared services once: auth, authorisation, user admin, sale repository, payments, receipts, printer, and the optional `SalesService` (only when products and stock are connected). `register(Class, service)` / `find(Class)` for module services. | App / shell (shared) | Uses `auth.*`, `pos.*`, `product.contract`, `inventory.contract`. Used by every screen through `ScreenContext.app()`, by `Main` and `DevApp`. | **COORDINATE BEFORE CHANGING.** Add module services with `register`, not by growing this class. Its `create(config, products, stock[, audit])` signature is used by `Main`, `DevApp` and `AppCheck`. |
| `app/AppConfig.java` | Terminal settings from `NSC_*` environment variables (terminal ID, idle timeout, store details, receipt width). | App / shell | Uses `pos.repository.InvoiceNumberGenerator` (static terminal-ID check). Used by `Main`, `AppContext`, `DevApp`. | COORDINATE BEFORE CHANGING (new setting = small additive edit; update `AppCheck` and `nickel-sales/CONTRIBUTING.md`). |
| `app/Modules.java` | The one place modules add their home-screen button. Lines for Products & inventory, Reports and System & sync are still `ComingSoonModule`. | App / shell (shared) | Uses `AppModule`, `PosModule`, `UserAdminModule`, `Permission`. Used by `NickelSalesApp`, `AppCheck`. | **COORDINATE BEFORE CHANGING.** Iteration 2 starting point: replace your module's `ComingSoonModule` line only. |
| `app/AppModule.java` | Interface: title, description, `Permission`, `open(ScreenContext)`. | App / shell | Implemented by `PosModule`, `UserAdminModule`, `ComingSoonModule`, future modules. | **SHARED CONTRACT — CHANGE CAREFULLY.** |
| `app/ScreenContext.java` | Record handed to a module screen: `stage`, `sessionId`, `user`, `app` (the `AppContext`), `home` (`goHome()`). | App / shell | Used by every `*.ui` screen. | **SHARED CONTRACT — CHANGE CAREFULLY.** |
| `app/ComingSoonModule.java` | Placeholder home-screen button for a module that doesn't exist yet. | App / shell | Used in `Modules`. | PLACEHOLDER (delete a usage when the real module arrives). |
| `app/DashboardRouter.java`, `app/HomeScreen.java` | Moves between login, home screen and module screens; the home screen's number-key buttons. | App / shell | `DashboardRouter` creates `LoginScreen` and `IdleLogoutTimer`; used by `NickelSalesApp`. | COORDINATE BEFORE CHANGING |
| `app/IdleLogoutTimer.java` | Logs out after inactivity. | App / shell | Used by `DashboardRouter`. | COORDINATE BEFORE CHANGING |
| `app/NickelSalesApp.java` | The JavaFX `Application`; opens the window on the login screen. | App / shell | Started by `Main` (and by `DevApp` in `test/`). | COORDINATE BEFORE CHANGING |

## 2. auth (User and Access Management, Iteration 1, done)

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `auth/contract/UserRepository.java` | Interface for loading and saving users. | Auth | Implemented by `MySqlUserRepository` (and the stub in `AuthDemo`). Used by auth services. | **SHARED CONTRACT — CHANGE CAREFULLY.** |
| `auth/contract/SessionManager.java`, `Session.java` | Session interface and record. | Auth | Used by login, admin and screens. | SHARED CONTRACT — CHANGE CAREFULLY |
| `auth/contract/AuditLogger.java` | Audit-event port, used by auth and by POS checkout. `AppContext` currently prints events to the console. | Auth (to move to `system` later) | Used by `AuthenticationService`, `UserAdminService`, `CheckoutService`. | **SHARED CONTRACT — CHANGE CAREFULLY.** Replace the implementation, not the interface. |
| `auth/contract/User.java`, `UserRole.java`, `UserStatus.java`, `PasswordPolicy.java` | User model, roles (A, C, I), status, password-policy interface. | Auth | Used across auth, `app`, `pos.checkout`. | COORDINATE BEFORE CHANGING |
| `auth/login/AuthenticationService.java` | Login with bcrypt, lockout, session start. | Auth | Uses `UserRepository`, `SessionManager`, `PasswordPolicy`, `AuditLogger`. Used by `AppContext`, `LoginScreen`, account services. | COORDINATE BEFORE CHANGING |
| `auth/account/UserAccountService.java`, `UserAdminService.java` | Change own password; admin add/edit/activate users (requires `MANAGE_USERS`). | Auth | Used by `AppContext` and the auth screens. | COORDINATE BEFORE CHANGING |
| `auth/account/PassPolicy.java` | Password rules (8–72 chars, uppercase, digit). | Auth | Used by `AppContext`, account services, dialogs. | COORDINATE BEFORE CHANGING |
| `auth/permission/Permission.java`, `AuthorizationService.java` | Permission list and role → permission matrix. Add a permission here when a module needs one. | Auth | Used by `app.Modules`, `AppModule`, services. | **COORDINATE BEFORE CHANGING** (new `Permission` is a small additive edit). |
| `auth/session/InMemorySessionManager.java` | Session store (in memory). | Auth | Used by `AppContext`. | COORDINATE BEFORE CHANGING |
| `auth/repository/MySqlUserRepository.java` | **The only SQL for the `Users` table** (moved from `auth/storage`). | Auth | Implements `UserRepository`; uses `shared.Database`. | COORDINATE BEFORE CHANGING |
| `auth/ui/LoginScreen.java`, `UserManagementScreen.java`, `UserFormDialog.java`, `ChangePasswordDialog.java`, `LogoutAction.java`, `UserAdminModule.java` | Login, user admin and password screens; `UserAdminModule` is the home-screen entry. | Auth | Use `app` shell API and auth services. | COORDINATE BEFORE CHANGING. `LoginScreen` (369 lines) is large; don't make it bigger. |

## 3. product (Iteration 2: Product Management + data layer)

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `product/contract/ProductLookup.java` | Interface POS (and Barcode) use: `findByBarcode`, `findById`, `search(text, limit)`. Rules in its Javadoc: inactive products are still found by barcode; search returns active products only, sorted by name. | Product | **Implemented by:** the Product service (TODO) and `test/stubs/StubInventory`. **Used by:** `SalesService`, `PosScreen`, `ProductSearchDialog`, `AppContext`. | **SHARED CONTRACT — CHANGE CAREFULLY.** Iteration 2 starting point: implement it. |
| `product/contract/ProductInfo.java` | Read-only product view for POS: ID, barcode, name, unit, retail/wholesale price (before VAT), vatable, active, stock on hand. | Product | Used by POS pricing and cart, `PosScreen`, stub. | **SHARED CONTRACT — CHANGE CAREFULLY.** |
| `product/README.md` | What to build here (`model`, `service`, `repository`, `ui`) and what to avoid. | Product | n/a | PLACEHOLDER |

## 4. inventory (Iteration 2: Inventory Management)

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `inventory/contract/StockRecorder.java` | `recordSale(String reference, List<StockLine> lines, Connection tx)`: deduct stock inside POS's transaction, re-check stock, never commit/close `tx`. | Inventory | **Implemented by:** Inventory service (TODO) and `StubInventory`. **Called by:** `pos.checkout.CheckoutService`. Also passed to `AppContext`. | **SHARED CONTRACT — CHANGE CAREFULLY.** Iteration 2 starting point: implement it. |
| `inventory/contract/StockLine.java` | Record: product ID + quantity (> 0, 3 decimals) to deduct. | Inventory | Built by `CheckoutService`; read by implementations. | **SHARED CONTRACT — CHANGE CAREFULLY.** |
| `inventory/contract/InsufficientStockException.java` | Thrown when stock is short (message is cashier-ready). | Inventory | Thrown by `SalesService`, `StockRecorder` implementations; caught by `PosScreen`. | SHARED CONTRACT — CHANGE CAREFULLY |
| `inventory/README.md` | What to build (`model`, `service`, `repository`, `ui`) and the transaction rule. | Inventory | n/a | PLACEHOLDER |

## 5. pos (POS & Sales: review, stabilisation and integration)

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `pos/cart/SalesService.java` | The cashier's sale engine for one terminal: start/cancel sale, add by barcode/product ID, change quantity, totals, stock problems, `finalizeSale`. The only class that changes a `Sale`. | POS | Uses `ProductLookup`, `PricingService`, `CheckoutService`. Used by `AppContext`, `PosScreen`, checks. | **COORDINATE BEFORE CHANGING.** Large (301 lines); keep `Sale`'s mutators package-private. |
| `pos/cart/Sale.java`, `SaleLineItem.java`, `SaleTotals.java`, `SaleStatus.java`, `StockProblem.java`, `CartException.java` | Cart model and results. | POS | Used by cart, checkout, receipt, UI. | COORDINATE BEFORE CHANGING |
| `pos/cart/PricingService.java`, `PricingRule.java`, `ThresholdPricingRule.java`, `Vat.java` | Retail/wholesale pricing (wholesale at quantity >= 6), 12% VAT. New pricing = a new `PricingRule`. | POS | Use `ProductInfo`. | **SAFE TO EXTEND** (add a rule); changing the existing rule changes totals, so run `CartCheck`/`CheckoutCheck`. |
| `pos/checkout/CheckoutService.java` | Completes a paid sale in ONE transaction: invoice number, save sale, `StockRecorder.recordSale`, audit log. **Calls `Database.inTransaction` directly (documented temporary exception).** | POS | Uses `SaleRepository`, `InvoiceNumberGenerator`, `StockRecorder`, `AuditLogger`. Used by `SalesService`, `AppContext`. | **COORDINATE BEFORE CHANGING.** Iteration 2 integration point for stock deduction. |
| `pos/checkout/PaymentService.java` | Validates and builds the payment (cash change, GCash/Maya/QRPh reference). Saves nothing. | POS | Used by `AppContext`, `PosScreen`. | COORDINATE BEFORE CHANGING |
| `pos/contract/SaleRecord.java`, `SaleLineRecord.java`, `PaymentRecord.java`, `PaymentMethod.java`, `PriceType.java` | Immutable completed-sale records; the constructors enforce totals and payment rules. | POS | Used by repository, receipts, Sync, Reports. | **SHARED CONTRACT — CHANGE CAREFULLY.** |
| `pos/contract/SaleHistory.java` | Read-only access to completed sales (by ID, invoice number, date range). | POS → Reports | Implemented by `MySqlSaleRepository`. Exposed by `AppContext.saleHistory()`. | **SHARED CONTRACT — CHANGE CAREFULLY.** Iteration starting point for Reports. |
| `pos/contract/SaleExport.java` | Unsynced completed sales (oldest first) and `markSynced`. | POS → Sync | Implemented by `MySqlSaleRepository`. Exposed by `AppContext.saleExport()`. | **SHARED CONTRACT — CHANGE CAREFULLY.** **Iteration 2 starting point for Sync.** |
| `pos/repository/SaleRepository.java` | POS-internal interface: `save(SaleRecord, Connection)` plus the read side. | POS | Implemented by `MySqlSaleRepository`; used by `CheckoutService`. | COORDINATE BEFORE CHANGING |
| `pos/repository/MySqlSaleRepository.java` | **The only SQL for `Sale`, `SaleLine`, `Payment`** (implements `SaleRepository`, `SaleExport`). | POS | Uses `shared.Database`. Created by `AppContext`. | COORDINATE BEFORE CHANGING (244 lines; don't add unrelated queries). |
| `pos/repository/InvoiceNumberGenerator.java` | Gap-free invoice numbers (`T01-2026-000123`) from `InvoiceSequence`; static helpers `format` and `checkTerminalId`. | POS | Used by `CheckoutService`, `SalesService`, `AppConfig`, `AppContext`. | COORDINATE BEFORE CHANGING |
| `pos/repository/PosTables.java` | Checks that the POS tables/columns exist (`missing()`). | POS | Used by `PosModule`. | COORDINATE BEFORE CHANGING. If you add POS columns, update its `REQUIRED` list and the SQL script together. |
| `pos/receipt/ReceiptBuilder.java`, `Receipt.java`, `StoreInfo.java` | Builds receipt text from a saved sale (`build`, `buildReprint`); formatting only. | POS | Built by `AppContext.receipts()`; used by `PosScreen`, `ReceiptDialog`. | SAFE TO EXTEND (formatting); run `ReceiptCheck`. |
| `pos/receipt/ReceiptView.java` | Text layout helper shared by the preview and the printer. | POS | Used by `ReceiptDialog`, `JavaFxReceiptPrinter`. | COORDINATE BEFORE CHANGING |
| `pos/receipt/PrinterPort.java`, `JavaFxReceiptPrinter.java` | Printer interface and the JavaFX implementation. A different printer plugs in through `PrinterPort`. | POS | Created by `AppContext`; passed to `SaleCompleteDialog` by `PosScreen`. | SAFE TO EXTEND (new `PrinterPort` implementation). |
| `pos/ui/PosModule.java` | "Point of Sale" home-screen entry; explains when POS is unavailable (no products connected, or old tables). | POS | Implements `AppModule`; uses `AppContext.salesService()`, `PosTables`. | COORDINATE BEFORE CHANGING |
| `pos/ui/PosScreen.java` | The cashier screen (keyboard-driven, barcode scanner acts as a keyboard). | POS | Uses `SalesService`, `PaymentService`, `ProductLookup`. | COORDINATE BEFORE CHANGING. Large (626 lines): no SQL and no pricing rules here. **Barcode starting point** for scan input. |
| `pos/ui/PaymentDialog.java`, `ProductSearchDialog.java`, `QuantityDialog.java`, `ReceiptDialog.java`, `ReprintDialog.java`, `SaleCompleteDialog.java` | Cashier dialogs. | POS | Used by `PosScreen`. | COORDINATE BEFORE CHANGING |

## 6. barcode (Iteration 2: Barcode Management)

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `barcode/README.md` | Intended responsibility: barcode → product via `ProductLookup`, validation, generation. | Barcode | `product.contract` | PLACEHOLDER. Start from `ProductLookup.findByBarcode`, and see how `SalesService.addItem(barcode)` and `PosScreen` consume it. |

## 7. sync (Iteration 2: Synchronization / API foundation)

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `sync/README.md` | Intended responsibility: send/receive data using exported contracts, never screens. | Sync | `pos.contract.SaleExport`, product/inventory contracts | PLACEHOLDER. Start from `pos/contract/SaleExport.java`, `AppContext.saleExport()`, and the Sync test in `CheckoutCheck`. |

## 8. reports

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `reports/README.md` | Intended: read-only reports. Home-screen button is a `ComingSoonModule`. | Reports (later iteration) | `pos.contract.SaleHistory` | PLACEHOLDER |

## 9. system

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `system/README.md` | Intended: audit log, settings, backup. Today audit events only print to the console via `AppContext`. | System (later iteration) | `auth.contract.AuditLogger` | PLACEHOLDER |

## 10. shared

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `shared/Database.java` | `getConnection()` (settings from `NSC_DB_*`) and `inTransaction(work)` (commit on success, roll back on any throw). | Shared infrastructure | Used by every `MySql...Repository` and `CheckoutService`. | **COORDINATE BEFORE CHANGING.** Iteration 2 repositories use it. |
| `shared/Money.java`, `Quantity.java`, `PhTime.java` | Money (BigDecimal, 2 dp), quantities (3 dp), Philippine time rules. | Shared | Used by contracts, POS, product, inventory. | COORDINATE BEFORE CHANGING (changes alter every module's numbers). |
| `shared/UiErrors.java` | Turns unexpected errors into messages a user can act on (database down, tables missing…). | Shared | Used by screens. | COORDINATE BEFORE CHANGING |

## 11. test (`nickel-sales/test/`)

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `stubs/StubInventory.java` | In-memory catalog and stock; implements `ProductLookup` and `StockRecorder`. Documents the sample products. | Test support | Used by `DevApp` and the POS/app checks. | **TEST ONLY / DO NOT USE IN PRODUCTION.** Keep it in step with the contracts. Reference behaviour for the Product and Inventory implementers. |
| `app/DevApp.java` | Development launcher: `AppContext.create(config, stub, stub)`, so POS runs before Product/Inventory exist. Started by `run-demo` and `.run/`. | Test support | Uses `app.*`, `StubInventory`. | **DO NOT USE IN PRODUCTION.** Retire when `Main` is wired to the real modules. |
| `app/AppCheck.java` | Settings, module list, home-screen buttons per role, shared-service wiring. | Test | `--scripted` | TEST ONLY. Update when you change `Modules` or `AppContext`. |
| `auth/AuthDemo.java`, `AuthzDemo.java`, `UserAdminDemo.java` | Login scenarios (with in-memory stub users, also `--console`), permission matrix, user-admin rules. | Test | `--scripted` | TEST ONLY |
| `pos/PosContractCheck.java` | Money/quantity/time rules, POS contracts, the stub's behaviour, POS tables present, `Database.inTransaction`. | Test | `--scripted`; needs MySQL for its database checks | TEST ONLY. Add contract-level checks for Product/Inventory here or in new `test/product`, `test/inventory`. |
| `pos/CartCheck.java` | Cart and pricing: wholesale switch, stock blocking, VAT totals. | Test | `--scripted` | TEST ONLY |
| `pos/CheckoutCheck.java` | Payments, invoice numbers, checkout rollback, saved-sale read-back, Sync export, Reports history. | Test | `--scripted`; needs MySQL | TEST ONLY. **Reference for stock-out and Sync behaviour.** |
| `pos/ReceiptCheck.java` | Receipt amounts against the saved sale, widths, reprint from MySQL. | Test | `--scripted`; needs MySQL | TEST ONLY |

## 12. database

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `database/NickelSales-Database-Config.sql` | The schema script: `Users`, `Sale`, `SaleLine`, `Payment`, `InvoiceSequence`, seed users, and an "UPGRADES" block. Safe to re-run. **No Product or Inventory tables yet.** | Shared; each module owns its tables | Run by `setup.*`; read by `MySql...Repository` classes; verified by `PosTables`. | **COORDINATE BEFORE CHANGING.** Iteration 2: add Product and Inventory tables here (`CREATE TABLE IF NOT EXISTS` + upgrade block). |
| `database/README.md`, `migrations/README.md`, `seed/README.md` | Folder rules; migrations and seed data are not used yet. | Shared | n/a | PLACEHOLDER (`migrations/`, `seed/`) |

## 13. scripts / docs

| File / Class | Purpose | Module / Owner Area | Depends On / Used By | Modification Guidance |
|---|---|---|---|---|
| `nickel-sales/run-demo.bat`, `run-demo.sh` | Compile `src/` + `test/` to a clean `out/`, then run the app (`app.DevApp`), `--console`, or **`--scripted`** (the list of all checks). | Build / test scripts | Use `setup.*`, `lib/`. | COORDINATE BEFORE CHANGING. A new `*Check` must be added to the `--scripted` list in **both** files. |
| `nickel-sales/setup.bat`, `setup.sh`, `setup-database.ps1`, `setup-database.sh` | Check JDK, download JavaFX, check MySQL, create the database from the SQL script. | Build / test scripts | Use the SQL script. | COORDINATE BEFORE CHANGING |
| `nickel-sales/CONTRIBUTING.md` | Machine setup, environment variables, running and testing, database changes, troubleshooting. | Docs | n/a | SAFE TO EXTEND |
| `README.md` (root) | Step-by-step guide for non-developers, plus a developer section. | Docs | n/a | SAFE TO EXTEND |
| `docs/REPOSITORY_MAP.md` | Where code belongs. | Docs | n/a | SAFE TO EXTEND (add your module's row details). |
| `docs/development/ARCHITECTURE.md`, `MODULE_TEMPLATE.md`, `NAMING_AND_STRUCTURE.md`, `CONTRIBUTING.md` | Architecture rules, module template, naming rules, contribution rules. | Docs | n/a | COORDINATE BEFORE CHANGING (team rules). |
| `docs/development/ITERATION2_REPO_CHANGES.md`, `FILE_INDEX.md` | The pre-Iteration-2 change log; this index. | Docs | n/a | SAFE TO EXTEND (keep this index current when you add important files). |
| `docs/archive/ITERATION1_CONTRIBUTOR_GUIDE.md` | The old Iteration 1 guide. Out of date. | Docs | n/a | Historical; don't rely on its paths. |
| `.run/*.run.xml`, `NSC-SWENG-Session-UI.iml`, `.idea/` | Shared IntelliJ run configurations (`app.DevApp`, `auth.AuthDemo`) and module setup. | IDE | Refer to class names `app.DevApp`, `auth.AuthDemo`. | COORDINATE BEFORE CHANGING (class renames break them). |
| `nickel-sales/.project`, `.classpath` | The Eclipse project (the only one). | IDE | Source folders `src`, `test`. | COORDINATE BEFORE CHANGING |

## Iteration 2 starting points at a glance

| Feature | Start from |
|---|---|
| **Product** | `product/README.md`, `product/contract/ProductLookup.java`, `ProductInfo.java`, `test/stubs/StubInventory.java`, `app/Modules.java`, `app/Main.java`, `database/NickelSales-Database-Config.sql` |
| **Inventory** | `inventory/README.md`, `inventory/contract/StockRecorder.java`, `StockLine.java`, `pos/checkout/CheckoutService.java`, `shared/Database.java`, `StubInventory.recordSale`, `test/pos/CheckoutCheck.java` |
| **POS integration** | `app/Main.java`, `app/AppContext.java`, `pos/ui/PosModule.java`, `pos/cart/SalesService.java`, `pos/checkout/CheckoutService.java`, `test/app/DevApp.java`, `test/app/AppCheck.java` |
| **Barcode** | `barcode/README.md`, `ProductLookup.findByBarcode`, `pos/ui/PosScreen.java`, `SalesService.addItem(barcode)` |
| **Sync** | `sync/README.md`, `pos/contract/SaleExport.java`, `pos/repository/MySqlSaleRepository.java`, `AppContext.saleExport()`, `app/Modules.java` ("System & sync") |
