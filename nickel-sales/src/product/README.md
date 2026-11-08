# product/ — Product Management (Iteration 2)

**Status: built.** Products, categories and suppliers persist in MySQL; POS finds real
products through `ProductService`; the **Products** button on the home screen manages them.

**Responsibility:** the product catalog: create/edit/discontinue products, categories,
suppliers, prices (retail + wholesale, both VAT included), VAT flag, barcodes, and finding
products by ID, name or barcode.

| Folder | What is there |
|---|---|
| `contract/` | `ProductLookup` and `ProductInfo`, the read-only view POS and Barcode use. Do not change without telling the POS and Barcode owners. `BarcodeAssigner`, `BarcodeEntry`, `BarcodeAssignException`: what the Barcode tab needs (list products with last-updated time, save a barcode). |
| `model/` | `Product`, `Category`, `Supplier` (records that validate their own fields) and `ProductDraft` (what the add/edit form submits). |
| `repository/` | `ProductRepository`, `CategoryRepository`, `SupplierRepository` (interfaces) and the `MySql...Repository` classes. All SQL lives here. A duplicate barcode / name comes back as `DuplicateValueException`. |
| `service/` | `ProductService` (**implements `ProductLookup`** + create / edit / discontinue), `CategoryService`, `SupplierService`, and `ProductException` (messages are safe to show). Rules and permission checks live here. |
| `ui/` | `ProductModule` (registered in `app.Modules`), `ProductScreen` (the tab strip: Products / Categories / Suppliers / Barcode) with `ProductsPane`, `CategoriesPane`, `SuppliersPane`, the dialogs (the pop-ups (`Modal`, `ProductFormDialog`, `SupplierFormDialog`, `NameDialog`, `ProductDetailsDialog`, plus `ProductUi.confirm` / `notice`)) and small shared helpers (`ProductUi`, `Pager`). The Barcode tab is the Barcode module's `barcode.ui.BarcodePane`; `ProductBarcodes` connects it to `ProductService` through `product.contract.BarcodeAssigner` (assign / reassign a barcode, last-updated time). The screen is drawn inside the sidebar frame `app.AppShell` and styled in Java by `shared.Styles` (colours from `shared.Theme`). `CategoryDialog` / `SupplierDialog` are the old pop-up lists, no longer opened. |

**May depend on:** `shared`, `auth.contract` / `auth.permission` (roles), its own folders.
**Must not depend on:** `pos.*`, `inventory.*` internals, `test/`.

## Rules the service enforces

- Name, unit and retail price are required; prices are not negative; wholesale price is
  not above retail; prices are rounded to 2 decimals.
- A barcode belongs to one product only (the database also enforces it, so two terminals
  racing cannot create a duplicate). A product may have no barcode.
- A new product can only use an **active** category / supplier; an edit may keep one that
  was deactivated later.
- Products, categories and suppliers are **never deleted**, only deactivated, so old sales
  stay explainable. A discontinued product is still found by barcode (POS shows "no longer
  sold") but is left out of cashier search.
- Creating / editing / deactivating needs `MANAGE_PRODUCTS` (Admin and Inventory staff) and
  is written to the audit log (`PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_DEACTIVATED`, ...).

## Stock belongs to Inventory

The `Product` table has **no stock column**. Stock lives only in Inventory's table
(`Inventory.QuantityOnHand`, with every change in `StockMovement`). `ProductInfo.stockOnHand` is
**read** from there (`MySqlProductRepository`'s SELECT joins `Inventory`; a product never stocked
shows 0) but **never written** by this module: new products start at 0, editing a product does
not touch stock, and there is no service method to change it. Only the Inventory module changes
stock (Stock In / Out / Adjust, and POS sales and returns through `StockRecorder`).

## How it is wired

`app.Main.wire(...)` is the only place that creates the repositories. It passes
`ProductService` to `AppContext.create(...)` as POS's `ProductLookup` and registers
`ProductService`, `CategoryService` and `SupplierService` for the screens.
Management methods take the acting `User` (the screens read it fresh from the session on
every click), so a role removed mid-shift is noticed immediately.

## Tables

`Category`, `Supplier`, `Product` in `database/NickelSales-Database-Config.sql` (safe to
re-run; it also adds nine test products; the self-tests in `test/` rely on exactly those, see
`test/stubs/TestCatalog`).

## Tests

`test/product/ProductCheck.java` (needs MySQL; cleans up after itself): model rules, seed
catalog, search, categories, suppliers, create/edit/deactivate, permissions, MySQL's own
unique/stock guards, and POS finding and selling real products. Run with
`run-demo --scripted`.

See [docs/development/MODULE_TEMPLATE.md](../../../docs/development/MODULE_TEMPLATE.md).
