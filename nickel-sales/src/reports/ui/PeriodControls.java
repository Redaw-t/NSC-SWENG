package reports.ui;

import javafx.scene.Node;
import javafx.scene.control.DatePicker;
import javafx.scene.layout.HBox;
import javafx.geometry.Pos;
import reports.model.ReportPeriod;
import shared.PhTime;
import shared.SearchPicker;

import java.util.List;

/**
 * The period part of every sales report's toolbar: Daily / Monthly / Yearly / Custom range and a
 * date. For Monthly and Yearly any day of the month or year will do; Custom range shows a second
 * date box. Starts on today (PH time). The meaning of the choices is in {@link ReportPeriod#of}.
 */
final class PeriodControls {

    private final SearchPicker<ReportPeriod.Kind> kind = new SearchPicker<>(false, false);
    private final DatePicker day;
    private final DatePicker to;
    private final HBox view;

    /** @param onEnter Enter in a date box (applies the filters) */
    PeriodControls(Runnable onEnter) {
        day = ReportsUi.datePicker("Date", onEnter);
        to = ReportsUi.datePicker("To Date", onEnter);
        day.setValue(PhTime.today());
        to.setValue(PhTime.today());

        kind.getItems().setAll(List.of(ReportPeriod.Kind.values()));
        kind.setValue(ReportPeriod.Kind.DAY);
        kind.setPrefWidth(160);
        kind.valueProperty().addListener((obs, old, now) -> showToDate(now == ReportPeriod.Kind.CUSTOM));
        showToDate(false);

        view = new HBox(12, kind, day, to);
        view.setAlignment(Pos.CENTER_LEFT);
    }

    Node getView() {
        return view;
    }

    /**
     * The chosen period.
     *
     * @throws IllegalArgumentException a date is missing, or From is after To (the message is for the user)
     */
    ReportPeriod period() {
        ReportPeriod.Kind k = kind.getValue() == null ? ReportPeriod.Kind.DAY : kind.getValue();
        return ReportPeriod.of(k, day.getValue(), to.getValue());
    }

    private void showToDate(boolean custom) {
        to.setVisible(custom);
        to.setManaged(custom);
        day.setPromptText(custom ? "From Date" : "Date");
    }
}
