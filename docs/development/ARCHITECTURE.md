# Architecture

How the Nickel Sales Center code is organised, and the dependency rules every module follows.
Read this before you add a class. The target is simple: **four developers work in parallel
without stepping on each other.**

## 1. The layers

```
UI  (JavaFX screens, dialogs)
 |  calls
 v
Service  (use cases and business rules)
 |  depends on the INTERFACE of
 v
Repository interface  (what the service needs stored/loaded)
 ^
 |  implemented by
Repository implementation  (MySqlXxxRepository: all the SQL)
 |  uses
 v
shared.Database  ->  local MySQL
```

| Layer | Does | Must NOT |
|---|---|---|
| **UI** (`*.ui`) | Shows data, collects input, calls services, shows errors (`shared.UiErrors`) | contain SQL, `java.sql` types, or business rules (prices, VAT, stock checks, permissions) |
| **Service** (`*.service`, or the existing `cart`, `checkout`, `account`, `login`, `permission`) | Implements use cases and rules; talks to repositories and other modules through interfaces | import `javafx.*` or open its own database connection (one temporary exception: `CheckoutService`, see "Known exceptions") |
| **Repository** (`*.repository`) | Reads and writes the module's own tables | contain UI logic or business decisions |
| **Contract** (`*.contract`) | Small interfaces and plain records that other modules are allowed to depend on | contain behaviour, SQL, or JavaFX |
| **Model** (`*.model`) | The module's own entities and value objects | depend on UI or repositories |

## 2. Allowed dependency direction

Inside one module: `ui -> service -> repository (interface) <- repository (MySql...)`.

Between modules, depend on **contracts**, not on another module's internals:

```
app (composition root: Main, AppContext, Modules)
  -> every module (to wire them together; nothing else may depend on app's wiring)

pos      -> product.contract, inventory.contract, auth.contract/permission, shared
reports  -> pos.contract (SaleHistory), product.contract, inventory.contract, shared
sync     -> pos.contract (SaleExport), product.contract, inventory.contract, shared
barcode  -> product.contract, shared
inventory-> product.contract, auth.*, shared
product  -> auth.*, shared
auth     -> shared
```

Never the other way round:

- `product` and `inventory` **never** import anything from `pos` (not even `pos.contract`).
  Inventory is told what to deduct with plain `inventory.contract.StockLine`s.
- Nobody imports another module's `ui` package. A module only exposes its screens by
  implementing `app.AppModule` and being listed in `app.Modules`.
- Nothing under `src/` imports `test.*`, `stubs.*`, or a class that lives in `test/`.

### Known exceptions (documented, not to be copied)

| Where | Why it exists |
|---|---|
| `*.ui` imports `app.AppModule`, `app.ScreenContext`, `app.HomeScreen`; `app` imports each `*.ui` module | The home screen / module-plugin mechanism lives in `app`. It is the only upward dependency allowed. |
| `pos.cart` <-> `pos.checkout` (package cycle inside POS) | `SalesService` (cart) finalises through `CheckoutService`, and `Sale`'s mutators are package-private so only `SalesService` can change a sale. Fixing it means introducing an interface in `pos.cart`; deferred, owned by POS. |
| `pos.*` imports `auth.contract.AuditLogger` | `AuditLogger` is the shared audit port. It moves to `system.contract` when the System module is built. |
| `pos.ui.PosModule` calls `pos.repository.PosTables.missing()` | A start-up check that the schema is current. Do not add more repository calls to UI classes. |
| `pos.checkout.CheckoutService` calls `shared.Database.inTransaction` directly | **Temporary.** Checkout must save the sale (`SaleRepository`), take the invoice number, and deduct stock (`StockRecorder`) in ONE database transaction, so the service opens the transaction and hands the same `Connection` to both. Both contracts therefore expose `java.sql.Connection`. Do not copy this pattern in new services; a later change should move transaction handling behind a repository or unit-of-work abstraction. |
| `app.AppConfig` and `app.AppContext` import `pos.repository` classes (`InvoiceNumberGenerator`, `MySqlSaleRepository`) | `AppConfig` uses the static `InvoiceNumberGenerator.checkTerminalId` to validate the terminal ID, and `AppContext` (the composition root) creates the MySQL sale repository. Acceptable in the wiring layer only; no other class outside `pos` should import `pos.repository`. |

## 3. Shared code

`shared/` holds only infrastructure and value objects that every module needs:
`Database`, `Money`, `Quantity`, `PhTime`, `UiErrors`. It is **not** a place for "helpers".
If a class knows about products, sales, users or stock, it belongs in that module.
A new class in `shared/` needs the agreement of the whole team.

## 4. Production vs test

```
src/    production. Compiles and runs with nothing from test/.
test/   self-tests (*Check, *Demo), stand-ins (test/stubs), and the dev launcher (test/app/DevApp).
```

- `Main` is the only production entry point.
- `DevApp` is a development launcher: it is `Main` plus `stubs.StubInventory`, so POS can be tried
  before the real Product/Inventory modules exist. Once they are wired into `Main`, `DevApp`
  can be retired.
- A stand-in goes in `test/stubs/` and implements the same contract the real class will.

## 5. Wiring and module boundaries

- `app.AppContext` builds the shared services once. A module's own services are added with
  `AppContext.register(Type.class, instance)` in `Main` and read with `find(Type.class)`,
  so adding a module does not change `AppContext`.
- A module appears on the home screen by implementing `app.AppModule` and replacing its
  `ComingSoonModule` line in `app.Modules`. Permissions come from `auth.permission.Permission`.
- Keep classes small. If a class does UI **and** SQL, or CRUD **and** reporting **and** stock
  maths, split it before it grows. (Existing large classes: `PosScreen`, `LoginScreen`,
  `SalesService`, `MySqlSaleRepository`. Don't make them bigger.)

## 6. Example runtime flows

**Cashier scans a barcode (Iteration 2 target):**

```
PosScreen                       (pos.ui)
  -> SalesService.addItem(barcode) (pos.cart)      pricing + stock check
    -> ProductLookup.findByBarcode (product.contract)
         implemented by ProductService (product.service)
           -> ProductRepository (interface) -> MySqlProductRepository -> Database -> MySQL
```

**Completing a sale (this already works):**

```
PosScreen -> SalesService.finalizeSale -> CheckoutService.complete
  Database.inTransaction {
      InvoiceNumberGenerator.nextInvoiceNo       (pos.repository)
      SaleRepository.save                        (pos.repository -> MySQL)
      StockRecorder.recordSale(invoiceNo, lines) (inventory.contract; Inventory deducts stock)
  }                                              all three commit, or none do
  -> AuditLogger.log("SALE_COMPLETED ...")
  -> ReceiptBuilder / PrinterPort                (pos.receipt)
```

**Reports and Sync read what POS exposes:**
`SaleHistory` -> Reports, `SaleExport` -> Sync, both from `AppContext`.
