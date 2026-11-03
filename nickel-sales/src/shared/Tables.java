package shared;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.util.function.Function;

/**
 * Builders for the tables of the Products and Users screens: blue header, grid lines, centred
 * cells, columns that share the table's width, and a "No." column. One place, so every table is
 * made the same way.
 */
public final class Tables {

    /** Height of one row, and of the blue header row (Styles.table sets the header to the same). */
    public static final double ROW_HEIGHT = 52;
    public static final double HEADER_HEIGHT = 52;

    private Tables() { }

    /** An empty table drawn like the design, with rows of {@link #ROW_HEIGHT}. */
    public static <S> TableView<S> create() {
        TableView<S> table = new TableView<>();
        Styles.table(table);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(ROW_HEIGHT);
        table.setMinHeight(HEADER_HEIGHT + ROW_HEIGHT * 3 + 2);   // TableView would otherwise insist on 400
        return table;
    }

    /** A plain text column. {@code width} is a starting width; the columns share the table's width in that proportion. */
    public static <S> TableColumn<S, String> textColumn(String title, double width, Function<S, String> value) {
        TableColumn<S, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        col.setCellFactory(c -> new Styles.GridCell<S>());
        col.setPrefWidth(width);
        col.setSortable(false);
        return col;
    }

    /** A column whose cell shows a node built from the row (a status pill, a row of buttons), centred. */
    public static <S> TableColumn<S, S> nodeColumn(String title, double width, double minWidth,
                                                   Function<S, Node> factory) {
        return nodeColumn(title, width, minWidth, false, factory);
    }

    /** Same, optionally left-aligned (for a name with an avatar). */
    public static <S> TableColumn<S, S> nodeColumn(String title, double width, double minWidth, boolean leftAligned,
                                                   Function<S, Node> factory) {
        TableColumn<S, S> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        col.setCellFactory(c -> new TableCell<S, S>() {
            @Override
            protected void updateItem(S item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(cellStyle(empty, leftAligned));
                setText(null);
                setGraphic(empty || item == null ? null : factory.apply(item));
            }
        });
        col.setPrefWidth(width);
        col.setMinWidth(minWidth);
        col.setSortable(false);
        return col;
    }

    /** The "No." column: 1, 2, 3 ... continuing across pages (page 2 starts after the last row of page 1). */
    public static <S> TableColumn<S, S> numberColumn(TablePager pager) {
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

    private static String cellStyle(boolean empty, boolean leftAligned) {
        String style = Styles.cellStyle(empty);
        if (!leftAligned) {
            return style;
        }
        return style.replace("-fx-alignment: CENTER;", "-fx-alignment: CENTER_LEFT;") + " -fx-padding: 0 0 0 16;";
    }
}
