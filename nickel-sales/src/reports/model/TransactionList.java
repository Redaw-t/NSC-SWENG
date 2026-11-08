package reports.model;

import pos.contract.SaleRecord;

import java.util.List;

/**
 * What the Transaction History screen shows: the matching sales, the rows to list (those sales and
 * the matching returns, newest first), and every cashier and terminal that made a sale or return in
 * the period (for the filter drop-downs; they do not shrink when a filter is chosen, so you can switch
 * from one cashier to another).
 *
 * @param sales the matching sales only, newest first
 * @param rows  the matching sales AND returns, newest first (what the table lists)
 */
public record TransactionList(List<SaleRecord> sales, List<TransactionRow> rows,
                              List<String> cashierIds, List<String> terminalIds) {

    public TransactionList {
        sales = List.copyOf(sales);
        rows = List.copyOf(rows);
        cashierIds = List.copyOf(cashierIds);
        terminalIds = List.copyOf(terminalIds);
    }
}
