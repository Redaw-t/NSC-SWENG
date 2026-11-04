package reports.model;

import java.math.BigDecimal;

/**
 * One product in the Top Products report.
 *
 * @param name         the name on its most recent sale in the period (sale lines keep the name
 *                     the product had when it was sold)
 * @param quantity     units sold, in the product's unit
 * @param amount       sum of its line amounts (before VAT)
 * @param sharePercent amount as a percentage of ALL line amounts in the period, 2 decimals
 *                     (rounded, so the shares can add up to 99.99 or 100.01)
 */
public record ProductSales(String productId, String name, String unit, BigDecimal quantity,
                           BigDecimal amount, BigDecimal sharePercent) { }
