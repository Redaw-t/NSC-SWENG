package product.ui;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import shared.Styles;

import java.util.List;

/**
 * The table footer from the design: "Showing 1-20 of 248" on the left and
 * "&lt; 1 2 3 4 &gt;" on the right. It only does the arithmetic and the links; the
 * screen keeps the full (filtered) list and asks for {@link #slice} of it.
 */
final class Pager {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_LINKS = 7;

    private final BorderPane view = new BorderPane();
    private final Label summary = new Label();
    private final HBox links = new HBox(4);
    private final Runnable onPageChange;

    private int pageSize = DEFAULT_PAGE_SIZE;   // rows per page; the screens set it from the table height
    private int page = 1;
    private int total;

    /** @param onPageChange called after a page link is clicked: show {@link #slice} again */
    Pager(Runnable onPageChange) {
        this.onPageChange = onPageChange;
        Styles.muted(summary);
        links.setAlignment(Pos.CENTER_RIGHT);
        BorderPane.setAlignment(summary, Pos.CENTER_LEFT);
        BorderPane.setAlignment(links, Pos.CENTER_RIGHT);
        view.setLeft(summary);
        view.setRight(links);
        render();
    }

    Parent getView() {
        return view;
    }

    /** Back to the first page (after a filter or search changed). */
    void reset() {
        page = 1;
    }

    /** Tells the pager how many rows there are; keeps the current page if it still exists. */
    void setTotal(int rows) {
        total = Math.max(0, rows);
        page = Math.max(1, Math.min(page, pageCount()));
        render();
    }

    /**
     * Sets how many rows fit on a page (the screens call this when the table's height changes, so the
     * table never needs a scroll bar). Keeps the first row on screen where possible.
     *
     * @return true if the size changed, so the screen must show the page again
     */
    boolean setPageSize(int rows) {
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

    /** How many rows come before the current page (for the "No." column). */
    int offset() {
        return (page - 1) * pageSize;
    }

    /** The rows of the current page. */
    <T> List<T> slice(List<T> rows) {
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
            summary.setText("Showing " + from + "-" + to + " of " + total);
        }

        int pages = pageCount();
        int first = Math.max(1, Math.min(page - MAX_LINKS / 2, pages - MAX_LINKS + 1));
        int last = Math.min(pages, first + MAX_LINKS - 1);

        links.getChildren().clear();
        links.getChildren().add(link("<", page - 1, false, page > 1));
        for (int p = first; p <= last; p++) {
            links.getChildren().add(link(String.valueOf(p), p, p == page, p != page));
        }
        links.getChildren().add(link(">", page + 1, false, page < pages));
    }

    private Button link(String text, int target, boolean current, boolean enabled) {
        Button button = new Button(text);
        Styles.pagerLink(button, current);
        button.setDisable(!enabled);
        button.setOnAction(e -> {
            page = target;
            render();
            onPageChange.run();
        });
        return button;
    }
}
