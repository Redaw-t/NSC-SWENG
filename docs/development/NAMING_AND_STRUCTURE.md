# Naming and structure

## Packages and folders

- Package names: **lowercase, one word**: `product`, `inventory`, `pos`, `auth`, `barcode`, `sync`.
  The folder path matches the package exactly (`src/product/service/` = `package product.service;`).
- Standard sub-packages inside a module: `model`, `contract`, `service`, `repository`, `ui`.
  Existing POS and auth sub-packages that already describe their job (`pos.cart`, `pos.checkout`,
  `pos.receipt`, `auth.login`, `auth.account`, `auth.permission`, `auth.session`) stay as they are.
- Tests mirror the production package: `test/pos/CartCheck.java` is `package pos;`.
  Stand-ins live in `test/stubs/` (`package stubs;`). Nothing in `test/` is needed to run `Main`.

## Classes and members

| Thing | Rule | Example |
|---|---|---|
| Classes, interfaces, records, enums | `PascalCase`, a noun | `ProductService`, `SaleRecord` |
| Methods, fields, variables | `camelCase`, verbs for methods | `findByBarcode`, `stockOnHand` |
| Constants | `UPPER_SNAKE_CASE` | `WHOLESALE_MIN_QTY` |
| Repository interface | `<Thing>Repository` | `ProductRepository` |
| Repository implementation | `MySql<Thing>Repository` | `MySqlProductRepository` |
| Service | `<Thing>Service` | `InventoryService` |
| Screen / dialog | `<Thing>Screen`, `<Thing>Dialog` | `ProductScreen` |
| Home-screen entry | `<Feature>Module` (implements `app.AppModule`) | `InventoryModule` |
| Contract | A descriptive role name (`ProductLookup`, `StockRecorder`, `SaleExport`), not `IProduct` or `ProductInterface` | |
| Exception | `<Problem>Exception` | `InsufficientStockException` |
| Self-test | `<Thing>Check` (with a `main`, exit code 1 on failure) | `CartCheck` |

Avoid vague names: `Manager`, `Helper`, `Utils`, `Handler`, `Data`, `Info` (unless it really is
read-only information, like `ProductInfo`). Say what the class does. Don't rename existing
classes just to match a pattern if the current name is already clear.

## Where does this class go?

| It... | Put it in |
|---|---|
| shows something / reads input | `<module>.ui` |
| decides something (a rule, a calculation, a permission) | `<module>.service` (or the existing POS/auth packages) |
| runs SQL | `<module>.repository` |
| is needed by a different module | `<module>.contract` (keep it small) |
| is a plain entity or value | `<module>.model` |
| is money / quantity / time / DB connection / error text for every module | `shared` (ask the team first) |
| is only for a test or demo | `test/` |

## Files and folders at the repository root

```
database/                 schema script, migrations/ and seed/ placeholders
docs/                     REPOSITORY_MAP.md, development/ (rules and templates)
nickel-sales/src/         production code
nickel-sales/test/        self-tests, stand-ins (stubs/), dev launcher (app/DevApp)
nickel-sales/lib/         bundled jars (JavaFX is downloaded by setup, not committed)
nickel-sales/*.bat, *.sh  setup and run scripts
```

Build output (`out/`, `bin/`, `*.class`), personal IDE state, passwords and `.env` files are
never committed (see `.gitignore`).
