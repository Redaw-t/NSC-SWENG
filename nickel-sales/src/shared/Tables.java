package shared;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseEvent;

import java.util.function.Function;

/**
 * Builders for the tables of the Products, Users, Inventory, Reports and System screens: blue header,
 * grid lines, centred cells, columns that share the table's width, and a "No." column. One place, so
 * every table is made the same way.
 *
 * <p>Fixed tables: columns can't be reordered (every builder turns it off) and the header can't be
 * dragged or resized ({@link #lockHeader}), so the approved widths stay as designed.
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
        lockHeader(table);
        return table;
    }

    /**
     * Stops the user dragging column borders (resize, double-click auto-size) or dragging columns around:
     * mouse presses, drags and clicks on a column header are swallowed. The headers do nothing else
     * here (sorting is off), so nothing is lost. Also call it on tables not made by {@link #create}.
     */
    public static void lockHeader(TableView<?> table) {
        table.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> swallowInHeader(e, table));
        table.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> swallowInHeader(e, table));
        table.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> swallowInHeader(e, table));
        table.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> swallowInHeader(e, table));
    }

    private static void swallowInHeader(MouseEvent e, Node table) {
        Node n = e.getTarget() instanceof Node t ? t : null;
        for (; n != null && n != table; n = n.getParent()) {
            if (n.getStyleClass().contains("column-header")) {
                e.consume();
                return;
            }
        }
    }

    /** A plain text column. {@code width} is a starting width; the columns share the table's width in that proportion. */
    public static <S> TableColumn<S, String> textColumn(String title, double width, Function<S, String> value) {
        TableColumn<S, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        col.setCellFactory(c -> new Styles.GridCell<S>());
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setReorderable(false);
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
        col.setReorderable(false);
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
        col.setReorderable(false);
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
