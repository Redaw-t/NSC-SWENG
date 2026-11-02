package product.ui;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Window;
import shared.Styles;
import shared.Theme;

import java.util.function.Function;

/** Small builders shared by the Products, Categories and Suppliers screens, so they look alike. */
final class ProductUi {

    static final String FALLBACK = "Something went wrong. Try again, or contact the system administrator.";
    static final double ROW_HEIGHT = 52;
    /** Matches the header height set in Styles.table(...). */
    static final double HEADER_HEIGHT = 52;

    private ProductUi() { }

    /** A table drawn like the design: blue header, grid lines, centred cells, flexible columns. */
    static <S> TableView<S> table() {
        TableView<S> table = new TableView<>();
        Styles.table(table);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(ROW_HEIGHT);
        table.setMinHeight(HEADER_HEIGHT + ROW_HEIGHT * 3 + 2);   // TableView would otherwise insist on 400
        return table;
    }

    /**
     * Makes the table show exactly as many rows as fit, so it never scrolls: whenever its height changes,
     * the pager's page size is set to the number of rows that fit and the screen shows the page again.
     */
    static void fitPageToHeight(TableView<?> table, Pager pager, Runnable showPage) {
        table.heightProperty().addListener((obs, old, height) -> Platform.runLater(() -> {
            double usable = table.getHeight() - HEADER_HEIGHT - 2;   // 2 = the table's border
            if (usable >= ROW_HEIGHT && pager.setPageSize((int) Math.floor(usable / ROW_HEIGHT))) {
                showPage.run();
            }
        }));
    }

    /** The "No." column: 1, 2, 3 ... continuing across pages (page 2 starts after the last row of page 1). */
    static <S> TableColumn<S, S> numberColumn(Pager pager) {
        TableColumn<S, S> col = new TableColumn<>("No.");
        col.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        col.setCellFactory(c -> new TableCell<S, S>() {
            @Override
            protected void updateItem(S item, boolean empty) {
                super.updateItem(item, empty);
                paint();
            }

            @Override
            public void updateIndex(int index) {
                super.updateIndex(index);
                paint();
            }

            private void paint() {
                boolean blank = isEmpty() || getIndex() < 0;
                setGraphic(null);
                setText(blank ? null : String.valueOf(pager.offset() + getIndex() + 1));
                setStyle(Styles.cellStyle(blank));
            }
        });
        col.setPrefWidth(50);
        col.setMinWidth(46);
        col.setMaxWidth(80);
        col.setSortable(false);
        return col;
    }

    /** A plain text column. {@code width} is a starting width; the columns share the table's width in that proportion. */
    static <S> TableColumn<S, String> textColumn(String title, double width, Function<S, String> value) {
        TableColumn<S, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        col.setCellFactory(c -> new Styles.GridCell<S>());
        col.setPrefWidth(width);
        col.setSortable(false);
        return col;
    }

    /** A column whose cell shows a node built from the row (a status pill, a row of buttons). */
    static <S> TableColumn<S, S> nodeColumn(String title, double width, double minWidth, Function<S, Node> factory) {
        TableColumn<S, S> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        col.setCellFactory(c -> new TableCell<S, S>() {
            @Override
            protected void updateItem(S item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(Styles.cellStyle(empty));
                setText(null);
                setGraphic(empty || item == null ? null : factory.apply(item));
            }
        });
        col.setPrefWidth(width);
        col.setMinWidth(minWidth);
        col.setSortable(false);
        return col;
    }

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

    /** The "Are you sure?" pop-up. True if the user pressed the (red) confirm button. */
    static boolean confirm(Window owner, String title, String message, String confirmText) {
        Modal modal = new Modal(owner, 520);
        boolean[] confirmed = {false};

        Label body = new Label(message);
        Styles.modalLabel(body);
        body.setWrapText(true);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button confirm = new Button(confirmText);
        Styles.modalButton(confirm, Theme.ACCENT_RED, "white");
        confirm.setOnAction(e -> {
            confirmed[0] = true;
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title(title), body, Modal.buttons(cancel, confirm));
        modal.showAndWait();
        return confirmed[0];
    }

    /** A message with a single OK button. */
    static void notice(Window owner, String title, String message) {
        Modal modal = new Modal(owner, 520);
        Label body = new Label(message);
        Styles.modalLabel(body);
        body.setWrapText(true);

        Button ok = new Button("OK");
        Styles.modalButton(ok, Theme.INFORMATION, "white");
        ok.setDefaultButton(true);
        ok.setCancelButton(true);
        ok.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(Modal.title(title), body, Modal.buttons(ok));
        modal.showAndWait();
    }
}
