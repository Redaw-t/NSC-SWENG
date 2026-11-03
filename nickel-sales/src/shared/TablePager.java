package shared;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.util.List;

/**
 * The footer under a table: "Showing 1-20 of 248" on the left and the page buttons on the
 * right. It only does the arithmetic and the buttons; the screen keeps the full (filtered)
 * list and asks for {@link #slice} of it. The page size follows the table's height
 * ({@link #fitToHeight}) so the table never needs a scroll bar.
 *
 * <p>Two looks, one class: {@link #links} is the plain "&lt; 1 2 3 4 &gt;" of the product
 * screens, {@link #boxed} is the boxed "‹ 1 2 ›" (current page filled blue) of the Users screen.
 */
public final class TablePager {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_LINKS = 7;

    private final BorderPane view = new BorderPane();
    private final Label summary = new Label();
    private final HBox buttons;
    private final Runnable onPageChange;
    private final boolean boxed;

    private int pageSize = DEFAULT_PAGE_SIZE;   // rows per page; set from the table height
    private int page = 1;
    private int total;

    /** Plain page links ("&lt; 1 2 3 4 &gt;"). @param onPageChange called after a page button is clicked: show {@link #slice} again */
    public static TablePager links(Runnable onPageChange) {
        return new TablePager(onPageChange, false);
    }

    /** Boxed page buttons ("‹ 1 2 ›"). @param onPageChange called after a page button is clicked: show {@link #slice} again */
    public static TablePager boxed(Runnable onPageChange) {
        return new TablePager(onPageChange, true);
    }

    private TablePager(Runnable onPageChange, boolean boxed) {
        this.onPageChange = onPageChange;
        this.boxed = boxed;
        this.buttons = new HBox(boxed ? 6 : 4);
        Styles.muted(summary);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        BorderPane.setAlignment(summary, Pos.CENTER_LEFT);
        BorderPane.setAlignment(buttons, Pos.CENTER_RIGHT);
        view.setLeft(summary);
        view.setRight(buttons);
        render();
    }

    public Parent getView() {
        return view;
    }

    /** Back to the first page (after a filter or search changed). */
    public void reset() {
        page = 1;
    }

    /** Tells the pager how many rows there are; keeps the current page if it still exists. */
    public void setTotal(int rows) {
        total = Math.max(0, rows);
        page = Math.max(1, Math.min(page, pageCount()));
        render();
    }

    /**
     * Sets how many rows fit on a page. Keeps the first row on screen where possible.
     *
     * @return true if the size changed, so the screen must show the page again
     */
    public boolean setPageSize(int rows) {
        int size = Math.max(1, rows);
        if (size == pageSize) {
            return false;
        }
        int firstRow = (page - 1) * pageSize;
        pageSize = size;
        page = firstRow / pageSize + 1;
        render();
        return true;
    }

    /**
     * Makes {@code table} show exactly as many rows as fit, so it never scrolls: whenever its height
     * changes, the page size is set to the number of rows that fit and {@code showPage} runs again.
     *
     * @param headerHeight height of the table's header row
     * @param rowHeight    height of one row (the table's fixed cell size)
     */
    public void fitToHeight(TableView<?> table, double headerHeight, double rowHeight, Runnable showPage) {
        table.heightProperty().addListener((obs, old, height) -> Platform.runLater(() -> {
            double usable = table.getHeight() - headerHeight - 2;   // 2 = the table's border
            if (usable >= rowHeight && setPageSize((int) Math.floor(usable / rowHeight))) {
                showPage.run();
            }
        }));
    }

    /** How many rows come before the current page (for a "No." column). */
    public int offset() {
        return (page - 1) * pageSize;
    }

    /** The rows of the current page. */
    public <T> List<T> slice(List<T> rows) {
        int from = offset();
        if (from >= rows.size()) {
            return List.of();
        }
        return rows.subList(from, Math.min(rows.size(), from + pageSize));
    }

    private int pageCount() {
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    private void render() {
        if (total == 0) {
            summary.setText("Showing 0 of 0");
        } else {
            int from = offset() + 1;
            int to = Math.min(total, page * pageSize);
            summary.setText("Showing " + from + (boxed ? "\u2013" : "-") + to + " of " + total);
        }

        int pages = pageCount();
        int first = Math.max(1, Math.min(page - MAX_LINKS / 2, pages - MAX_LINKS + 1));
        int last = Math.min(pages, first + MAX_LINKS - 1);

        buttons.getChildren().clear();
        buttons.getChildren().add(button(boxed ? "\u2039" : "<", page - 1, false, page > 1));
        for (int p = first; p <= last; p++) {
            buttons.getChildren().add(button(String.valueOf(p), p, p == page, p != page));
        }
        buttons.getChildren().add(button(boxed ? "\u203A" : ">", page + 1, false, page < pages));
    }

    private Button button(String text, int target, boolean current, boolean enabled) {
        Button button = new Button(text);
        if (boxed) {
            styleBoxed(button, current);
        } else {
            Styles.pagerLink(button, current);
        }
        button.setDisable(!enabled && !current);   // the current page is shown at full strength
        button.setOnAction(e -> {
            page = target;
            render();
            onPageChange.run();
        });
        return button;
    }

    private static void styleBoxed(Button button, boolean current) {
        String base = "-fx-background-radius: 4; -fx-border-radius: 4; -fx-border-width: 1; -fx-min-width: 34; "
                + "-fx-pref-height: 32; -fx-padding: 0 8 0 8; -fx-cursor: hand; ";
        String line = Styles.darker(Theme.BORDER);
        String normal;
        String hover;
        if (current) {
            normal = base + "-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-border-color: " + Theme.PRIMARY_BLUE
                    + "; -fx-text-fill: white; -fx-font-weight: bold;";
            hover = normal;
        } else {
            normal = base + "-fx-background-color: " + Theme.SURFACE + "; -fx-border-color: " + line
                    + "; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";";
            hover = base + "-fx-background-color: " + Styles.rgba(Theme.PRIMARY_BLUE, 0.08) + "; -fx-border-color: "
                    + line + "; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";";
        }
        button.setStyle(normal);
        button.hoverProperty().addListener((obs, was, is) -> button.setStyle(is ? hover : normal));
    }
}
