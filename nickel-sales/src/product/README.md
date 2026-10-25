# product/ — Product Management (Iteration 2)

**Status: contracts only. TODO: implement.**

**Responsibility:** the product catalog: create/edit/deactivate products, categories,
suppliers, prices (retail + wholesale), VAT flag, and finding products by ID, name or barcode.

| Folder | State | What goes there |
|---|---|---|
| `contract/` | **Exists** | `ProductLookup` and `ProductInfo`, the read-only view POS and Barcode use. Do not change without telling the POS and Barcode owners. |
| `model/` | TODO | The product entity, category, supplier. |
| `service/` | TODO | Use cases and rules (validation, duplicate barcodes). Implements `ProductLookup`. |
| `repository/` | TODO | `ProductRepository` (interface) and `MySqlProductRepository`. All SQL lives here. |
| `ui/` | TODO | `ProductModule` (registered in `app.Modules`) and the screens. |

**May depend on:** `shared`, `auth.contract` / `auth.permission` (roles), its own folders.
**Must not depend on:** `pos.*`, `inventory.*` internals, `test/`.

See [docs/development/MODULE_TEMPLATE.md](../../../docs/development/MODULE_TEMPLATE.md).
