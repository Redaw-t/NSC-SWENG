package reports.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the sales summary's breakdown: a day, or a month.
 *
 * @param start first day of the row (PH time)
 * @param label "Nov 4, 2026" for a day, "Nov 2026" for a month
 * @param total what customers paid in that row, VAT included
 */
public record SalesBucket(LocalDate start, String label, int transactions, BigDecimal total) { }
