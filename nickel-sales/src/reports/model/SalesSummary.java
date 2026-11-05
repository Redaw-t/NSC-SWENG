package reports.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The sales summary of a period. The first five amounts come from each sale's own totals, so
 * they always agree: {@code vatableSales + vatExemptSales + vatAmount = totalIncome}.
 *
 * <p>The retail / wholesale split is different: it adds up LINE amounts. Shelf prices include VAT,
 * so {@code retailAmount + wholesaleAmount} equals the total income. (Sales saved while prices were
 * before VAT have line amounts without VAT, so for them the split is less than the income.)
 *
 * @param transactions number of completed sales
 * @param totalIncome  what customers paid, VAT included
 * @param breakdown    one row per day (per month for a yearly report or a range longer than
 *                     {@code SalesReportService.MAX_DAILY_ROWS} days), including days without sales
 */
public record SalesSummary(ReportPeriod period, int transactions, BigDecimal totalIncome,
                           BigDecimal vatableSales, BigDecimal vatExemptSales, BigDecimal vatAmount,
                           BigDecimal retailAmount, BigDecimal wholesaleAmount, List<SalesBucket> breakdown) {

    public SalesSummary {
        breakdown = List.copyOf(breakdown);
    }
}
