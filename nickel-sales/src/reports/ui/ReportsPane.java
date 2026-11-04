package reports.ui;

import javafx.scene.Parent;

/** One tab of the Reports screen. */
interface ReportsPane {

    Parent getView();

    /** Loads the report again: when the tab is shown, and on F5. */
    void refresh();
}
