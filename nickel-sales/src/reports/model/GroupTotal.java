package reports.model;

import java.math.BigDecimal;

/**
 * The sales of one cashier or one terminal in a period.
 *
 * @param key   the cashier's user ID (e.g. "NSC-00002") or the terminal ID (e.g. "T01")
 * @param total what customers paid, VAT included
 */
public record GroupTotal(String key, int transactions, BigDecimal total) { }
