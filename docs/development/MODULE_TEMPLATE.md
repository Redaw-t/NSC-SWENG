# Module template

Copy this shape when you build a Iteration 2 module (Product, Inventory, Barcode, Sync, Reports, System).
**Only create the folders you actually need.** An empty folder or a class with no behaviour
is noise; start with a `README.md` saying what is TODO, and add folders as code arrives.

```
src/<feature>/
    model/        entities and value objects owned by this module
    contract/     interfaces + records OTHER modules may depend on
    service/      use cases and business rules
    repository/   <Feature>Repository (interface) and MySql<Feature>Repository
    ui/           <Feature>Module (an app.AppModule), screens, dialogs
test/<feature>/   self-tests for the module (and test/stubs/ for stand-ins)
```

| Folder | Put here | Keep out |
|---|---|---|
| `model/` | `Product`, `Category`, `Supplier`: plain Java, validation of their own fields | SQL, JavaFX, other modules' types |
| `contract/` | The few things another module needs, e.g. `ProductLookup`, `ProductInfo`. Small interfaces (one job each). | Behaviour, SQL. Changing it breaks other people: coordinate first. |
| `service/` | `ProductService implements ProductLookup`: rules such as "barcode must be unique", "price can't be negative"; checks permissions | `javafx.*`, `java.sql.*`, `new MySqlXxx...` (receive the repository in the constructor) |
| `repository/` | `ProductRepository` (what the service needs), `MySqlProductRepository` (SQL via `shared.Database`, `PreparedStatement` with `?`) | Business rules, UI, calls to other modules' repositories |
| `ui/` | `ProductModule`, `ProductScreen`, dialogs: show data, call the service, display `UiErrors` | SQL, rules, direct repository use |

## A good example

```
ProductModule (ui, implements app.AppModule, listed in app.Modules)
  -> opens ProductScreen (ui)
       -> ProductService.save(product)          (service: validates, checks permission)
            -> ProductRepository.save(product)  (repository interface)
                 ^ implemented by MySqlProductRepository
                      -> Database.getConnection() -> MySQL
```

Wiring (in `Main`, the only place that says `new MySqlProductRepository()`):

```java
ProductRepository repo = new MySqlProductRepository();
ProductService products = new ProductService(repo, app.authz());
app.register(ProductService.class, products);                    // for your own screens
AppContext app = AppContext.create(config, products /* ProductLookup */, stock);
```

## Bad examples (do not do this)

```
ProductScreen
  -> "SELECT * FROM Product ..."          UI talks SQL.  Go through ProductService.

InventoryService
  -> CashierScreen / pos.ui.*             A service reaching into another module's UI.
  -> pos.contract.SaleRecord              Inventory must not depend on POS. Use inventory.contract.StockLine.

shared/SharedUtils
  -> computeReorderLevel(), markUp()      Business logic dumped in shared. It belongs in inventory / product.

ProductManagement.java (800 lines)        CRUD + SQL + screen + categories + suppliers in one class.
  Split: ProductService, CategoryService, MySqlProductRepository, ProductScreen.

ProductService
  -> new MySqlProductRepository()         Hard-wired. Take a ProductRepository in the constructor.
```

## Adding a module, step by step

1. Create `src/<feature>/` with the folders you need (see above) and a short `README.md`
   (purpose, owner, may/must-not depend on). Copy the style of `src/product/README.md`.
2. Define the contract(s) other modules need under `contract/` first, and agree them with the
   consumers. For example: Product owner and POS owner agree `ProductLookup` / `ProductInfo`.
3. Write the repository interface, then the service, then the MySQL repository, then the UI.
4. Add your tables to `database/NickelSales-Database-Config.sql` (`CREATE TABLE IF NOT EXISTS`,
   plus an "upgrade" block for later column changes).
5. Wire it in `Main` (`app.register(...)`) and in `app.Modules` (replace the `ComingSoonModule` line).
6. Add a `*Check` self-test under `test/<feature>/`, add it to the lists in `run-demo.bat` and
   `run-demo.sh` (`--scripted`), and a stand-in under `test/stubs/` if others need to try your
   contract before the real class exists.
7. Add the module to [REPOSITORY_MAP.md](../REPOSITORY_MAP.md).
