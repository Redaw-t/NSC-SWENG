package pos.checkout;

import auth.contract.AuditLogger;
import inventory.contract.InsufficientStockException;
import inventory.contract.StockLine;
import inventory.contract.StockRecorder;
import pos.cart.Sale;
import pos.cart.SaleLineItem;
import pos.cart.SaleTotals;
import pos.contract.PaymentRecord;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import pos.repository.InvoiceNumberGenerator;
import pos.repository.SaleRepository;
import shared.Database;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a paid sale into a saved, completed sale, as ONE database transaction:
 *
 *   1. take the next invoice number (InvoiceSequence, row locked)
 *   2. insert Sale + SaleLine + Payment
 *   3. StockRecorder.recordSale  (Product &amp; Inventory deducts stock; it
 *      checks stock again and throws InsufficientStockException if short)
 *
 * If ANY step fails, everything is rolled back: no sale, no payment, no
 * stock change, and the invoice number is not used up. Only after the
 * commit is the sale logged (AuditLogger event SALE_COMPLETED).
 *
 * Called by SalesService.finalizeSale; screens use that, not this.
 */
public class CheckoutService {

    private final SaleRepository sales;
    private final InvoiceNumberGenerator invoices;
    private final StockRecorder stock;
    private final AuditLogger audit;

    public CheckoutService(SaleRepository sales, InvoiceNumberGenerator invoices, StockRecorder stock,
                           AuditLogger audit) {
        this.sales = Objects.requireNonNull(sales, "sales");
        this.invoices = Objects.requireNonNull(invoices, "invoices");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Saves {@code sale} paid by {@code payment} and returns the completed sale
     * with its invoice number. Does not change {@code sale}; SalesService marks
     * it COMPLETED once this returns.
     *
     * @throws InsufficientStockException stock ran short (nothing was saved)
     * @throws RuntimeException           database problem (nothing was saved; cause = SQLException)
     */
    public SaleRecord complete(Sale sale, PaymentRecord payment) throws InsufficientStockException {
        Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);   // DATETIME(3) keeps ms
        List<SaleLineRecord> lines = new ArrayList<>();
        for (SaleLineItem item : sale.getItems()) {
            lines.add(item.toRecord(lines.size() + 1));
        }
        SaleTotals totals = sale.totals();

        SaleRecord saved;
        try {
            saved = Database.inTransaction(tx -> {
                String invoiceNo = invoices.nextInvoiceNo(tx, sale.getTerminalId(), completedAt);
                SaleRecord record = new SaleRecord(sale.getSaleId(), invoiceNo, sale.getTerminalId(),
                        sale.getCashierId(), completedAt, lines, List.of(payment),
                        totals.vatableSales(), totals.vatExemptSales(), totals.vatAmount(), totals.total());
                sales.save(record, tx);
                List<StockLine> stockOut = record.lines().stream()
                        .map(line -> new StockLine(line.productId(), line.quantity()))
                        .toList();
                stock.recordSale(record.invoiceNo(), stockOut, tx);
                return record;
            });
        } catch (SQLException e) {
            throw new RuntimeException("Could not save the sale", e);
        }

        try {
            audit.log("SALE_COMPLETED " + saved.invoiceNo(), saved.cashierUserId());
        } catch (RuntimeException logFailure) {
            // The sale is saved; a logging problem must not make it look failed.
            System.err.println("[AUDIT] could not log sale " + saved.invoiceNo() + ": " + logFailure);
        }
        return saved;
    }
}
