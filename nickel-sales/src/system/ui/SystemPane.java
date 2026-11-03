package system.ui;

import javafx.scene.Parent;

/** One tab of the Audit &amp; System screen, and what the keyboard shortcuts can ask of it. */
interface SystemPane {

    Parent getView();

    /** Called every time the tab is shown, and on F5. */
    void refresh();

    /** Ctrl+F: puts the cursor in the tab's search box, if it has one. */
    default void focusSearch() { }

    /** Ctrl+E: exports what the tab is showing, if it can. */
    default void export() { }
}
