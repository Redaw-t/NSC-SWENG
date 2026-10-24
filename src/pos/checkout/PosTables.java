package pos.checkout;

import shared.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks that the database has every table and column POS saves sales into.
 * A database made by an older SQL script is missing some (e.g. Payment.ReferenceNo);
 * running setup.bat brings it up to date without losing data.
 *
 * Checked when the Point of Sale screen opens, so the cashier finds out
 * before ringing up a sale rather than when pressing Pay.
 */
public final class PosTables {

    /** Every table.column POS writes or reads. Keep in step with the SQL script. */
    private static final String[] REQUIRED = {
            "Sale.SaleID", "Sale.InvoiceNo", "Sale.TerminalID", "Sale.CashierUserID", "Sale.CompletedAt",
            "Sale.VatableSales", "Sale.VatExemptSales", "Sale.VatAmount", "Sale.Total", "Sale.SyncedAt",
            "SaleLine.SaleID", "SaleLine.LineNo", "SaleLine.ProductID", "SaleLine.Barcode",
            "SaleLine.ProductName", "SaleLine.Unit", "SaleLine.Quantity", "SaleLine.UnitPrice",
            "SaleLine.PriceType", "SaleLine.Vatable", "SaleLine.LineTotal",
            "Payment.SaleID", "Payment.PaymentNo", "Payment.Method", "Payment.ReferenceNo",
            "Payment.AmountTendered", "Payment.AmountApplied", "Payment.ChangeGiven",
            "InvoiceSequence.TerminalID", "InvoiceSequence.InvoiceYear", "InvoiceSequence.NextNumber",
    };

    private PosTables() { }

    /**
     * The required "Table.Column"s the database does not have; empty when it
     * is up to date.
     *
     * @throws RuntimeException the database can't be reached (cause: SQLException)
     */
    public static List<String> missing() {
        Set<String> present = new HashSet<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME IN ('Sale', 'SaleLine', 'Payment', 'InvoiceSequence')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                present.add((rs.getString(1) + "." + rs.getString(2)).toLowerCase());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not check the POS tables", e);
        }
        List<String> missing = new ArrayList<>();
        for (String column : REQUIRED) {
            if (!present.contains(column.toLowerCase())) {
                missing.add(column);
            }
        }
        return missing;
    }
}
