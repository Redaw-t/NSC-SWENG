package reports.model;

import pos.contract.SaleRecord;

import java.util.List;

/**
 * What the Transaction History screen shows: the matching sales, newest first, and every
 * cashier and terminal that made a sale in the period (for the filter drop-downs; they do
 * not shrink when a filter is chosen, so you can switch from one cashier to another).
 */
public record TransactionList(List<SaleRecord> sales, List<String> cashierIds, List<String> terminalIds) {

    public TransactionList {
        sales = List.copyOf(sales);
        cashierIds = List.copyOf(cashierIds);
        terminalIds = List.copyOf(terminalIds);
    }
}
