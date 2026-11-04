# reports/ — Transaction and inventory reports

**Status: built: Transactions, Sales Summary, Top Products, Cashiers & Terminals, Inventory.**
Opened from the home screen / sidebar "Reports" entry via `ui.ReportsModule`.

**Responsibility:** read-only sales and inventory reports. Reports never write: the services only
hold the read-only `pos.contract.SaleHistory`.

| Folder | What is in it |
|---|---|
| `model/` | `ReportPeriod` (day / month / year / custom, cut at PH midnight), `ReportFilter`, and the report rows (`SalesSummary`, `SalesBucket`, `ProductSales`, `GroupTotal`, `TransactionList`) |
| `service/` | One small service per report: `TransactionHistoryService`, `SalesReportService` (one `salesFor(period)` for every period), `ProductPerformanceService`, `CashierReportService`, `InventoryReportService` (through `inventory.contract.StockOverview`); `ReportsAccess` (permission check); no JavaFX, so `ReportsCheck` tests them without a database |
| `ui/` | `ReportsModule`, `ReportsScreen` (only the tabs the user may open), one pane per report, `SaleDetailsDialog` (read-only; POS's receipt classes are POS internals), `ReportsUi`, `UserNames` |

**Who sees what (Story 5):**

| Role | Sales reports (`VIEW_SALES_REPORTS`) | Inventory report (`VIEW_INVENTORY_REPORTS`) |
|---|---|---|
| Admin | yes | yes |
| Inventory staff | no | yes |
| Cashier | no | no |

The Reports button needs `VIEW_INVENTORY_REPORTS` (an `AppModule` has one permission). That works because
every role with sales reports also has inventory reports; `ReportsCheck` fails if a new role breaks this.

**Rules worth knowing:**
- Days, months and years are in Philippine time (`shared.PhTime`): a sale at 23:30 PH belongs to that day.
- All amounts are `BigDecimal` (`shared.Money`). Total income, VATable, VAT-exempt and VAT come from each
  sale's own totals, so they always add up. The retail / wholesale split and Top Products use line amounts,
  which are **before VAT**.
- An exact invoice number is found on any date; part of one searches the chosen period.
- Top-N ties: the other measure, then name, then product ID. Exactly N rows.
- Sales per customer and per supplier are not possible: sales have no customer, and sale lines no supplier.

**Self-test:** `test/reports/ReportsCheck` (part of `run-demo --scripted`): builds sales in memory, no database.

**May depend on:** `shared`, `pos.contract`, `product.contract`, `inventory.contract`, `auth.contract` /
`auth.permission`, `app` (module and screen wiring, from `ui` only).
**Must not depend on:** `pos` internals, other modules' `ui`, `test/`. Reports never write.
