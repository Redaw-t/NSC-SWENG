package product.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import shared.Styles;
import shared.Theme;

/**
 * Small builders for the buttons and status pill of the Products, Categories and Suppliers
 * screens, so they look alike. Tables, pager and pop-ups are shared (shared.Tables,
 * shared.TablePager, shared.Modal).
 */
final class ProductUi {

    private ProductUi() { }

    /** The green "Active" / grey "Inactive" pill. */
    static Label pill(boolean active) {
        Label pill = new Label(active ? "Active" : "Inactive");
        if (active) {
            Styles.pill(pill, Theme.SUCCESS, "white");
        } else {
            Styles.pill(pill, Theme.BORDER, Theme.SECONDARY_TEXT);
        }
        return pill;
    }

    /** The colours a row button can have. */
    enum RowColour { VIEW, EDIT, DANGER, SUCCESS }

    /** A small rounded button for a table row. */
    static Button rowButton(String text, RowColour colour) {
        Button button = new Button(text);
        switch (colour) {
            case VIEW -> Styles.rowButton(button, Theme.BORDER, Theme.PRIMARY_TEXT);
            case EDIT -> Styles.rowButton(button, Theme.INFORMATION, "white");
            case DANGER -> Styles.rowButton(button, Theme.ACCENT_RED, "white");
            case SUCCESS -> Styles.rowButton(button, Theme.SUCCESS, "white");
        }
        return button;
    }

    /** The Deactivate (red) or Reactivate (green) row button. */
    static Button toggleButton(boolean currentlyActive) {
        return currentlyActive ? rowButton("Deactivate", RowColour.DANGER) : rowButton("Reactivate", RowColour.SUCCESS);
    }

    static Button primaryButton(String text) {
        Button button = new Button(text);
        Styles.primaryButton(button);
        return button;
    }
}
