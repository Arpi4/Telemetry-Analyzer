package com.telemetry.desktop;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Callback;

import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Building blocks of the desktop look, shared by the views: cards with an accent-bar title (as
 * the panels of the web client), KPI tiles, legend entries and table columns.
 */
final class Ui {

    /** "Start (m)" → name "Start", unit "m". */
    private static final Pattern UNIT = Pattern.compile("(.+) \\((.+)\\)");

    /** Left + right padding of a column header label, see desktop.css, plus rounding slack. */
    private static final double HEADER_PADDING = 14;
    /** Thickness of a scroll bar, see desktop.css. */
    private static final double SCROLL_BAR = 10;

    private Ui() {
    }

    /** Uppercase section title with the red accent bar. */
    static Label title(String text) {
        Region bar = new Region();
        bar.getStyleClass().add("accent-bar");
        Label l = new Label(text.toUpperCase(Locale.ROOT), bar);
        l.getStyleClass().add("panel-title");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    /** Panel with a title row; {@code extras} (readout, legend) follow the title in that row. */
    static VBox card(String title, Node content, Node... extras) {
        HBox head = new HBox(14, title(title));
        head.getChildren().addAll(extras);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, head, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        card.getStyleClass().add("card");
        return card;
    }

    /** Tab pane drawn as a card; its tab strip doubles as the section title. */
    static TabPane cardTabs(Tab... tabs) {
        TabPane pane = new TabPane(tabs);
        pane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        pane.getStyleClass().add("card-tabs");
        return pane;
    }

    static Tab tab(String text, Node content) {
        return new Tab(text.toUpperCase(Locale.ROOT), content);
    }

    /** KPI tile: small caption over a large value; {@code accent} adds a coloured edge. */
    static VBox tile(String caption, Label value, String accent) {
        Label c = new Label(caption.toUpperCase(Locale.ROOT));
        c.getStyleClass().add("tile-title");
        value.getStyleClass().add("tile-value");
        VBox box = new VBox(0, c, value);
        box.getStyleClass().add("tile");
        if (accent != null) {
            box.getStyleClass().add(accent);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Legend entry: a coloured dot ({@code dot-ref}, {@code dot-cmp}) and its text. */
    static Label legend(String text, String dot) {
        Region mark = new Region();
        mark.getStyleClass().addAll("legend-dot", dot);
        Label l = new Label(text, mark);
        l.getStyleClass().add("legend");
        return l;
    }

    static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        return l;
    }

    static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Numeric column, right aligned; a unit in parentheses goes to a second header line. */
    static <T> TableColumn<T, String> col(String name, Function<T, String> value) {
        TableColumn<T, String> c = column(name, value);
        c.getStyleClass().add("num");
        return c;
    }

    /** Label column (sector, zone), left aligned. */
    static <T> TableColumn<T, String> keyCol(String name, Function<T, String> value) {
        return column(name, value);
    }

    /**
     * Columns share the table width while their minimum widths fit; a table too narrow for them
     * scrolls sideways instead of cutting off its last columns (constrained tables never scroll).
     */
    static <T> void fitColumns(TableView<T> table) {
        InvalidationListener update = o -> {
            double need = SCROLL_BAR + table.snappedLeftInset() + table.snappedRightInset();
            for (TableColumn<T, ?> c : table.getColumns()) {
                need += c.getMinWidth();
            }
            Callback<TableView.ResizeFeatures, Boolean> policy = need <= table.getWidth()
                    ? TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS
                    : TableView.UNCONSTRAINED_RESIZE_POLICY;
            if (table.getColumnResizePolicy() != policy) {
                if (policy == TableView.UNCONSTRAINED_RESIZE_POLICY) {
                    // scroll no further than needed
                    for (TableColumn<T, ?> c : table.getColumns()) {
                        c.setPrefWidth(c.getMinWidth());
                    }
                }
                table.setColumnResizePolicy(policy);
            }
        };
        table.widthProperty().addListener(update);
        for (TableColumn<T, ?> c : table.getColumns()) {
            c.minWidthProperty().addListener(update);
        }
        update.invalidated(null);
    }

    /**
     * Splits a column into the track map (top) and a tab card (bottom). The bottom pane gets the
     * height {@code bottomHeight} asks for, given the most it may take while the map keeps
     * {@code minTop} of the column; {@code NaN} leaves the divider alone. Re-fitted whenever the
     * column is resized and when the returned action runs; the divider stays draggable.
     */
    static Runnable fitBottom(SplitPane split, double minTop, DoubleUnaryOperator bottomHeight) {
        Runnable fit = () -> {
            double total = split.getHeight() - split.snappedTopInset() - split.snappedBottomInset();
            Node divider = split.lookup(".split-pane-divider");
            if (total <= 0 || divider == null) {
                return;
            }
            // the skin sizes dividers by their horizontal padding in both orientations, and
            // centres a divider on its position
            double half = divider.prefWidth(-1) / 2;
            double topMin = split.getItems().getFirst().minHeight(-1);
            double max = Math.min(total * (1 - minTop) - half, total - topMin - 2 * half);
            double bottom = bottomHeight.applyAsDouble(max);
            if (!Double.isNaN(bottom)) {
                split.setDividerPositions(Math.max(minTop, (total - half - Math.min(bottom, max)) / total));
            }
        };
        // after the layout pass, in which the skin spreads a size change over the panes itself
        Runnable later = () -> Platform.runLater(fit);
        split.heightProperty().addListener((o, a, h) -> later.run());
        return later;
    }

    /** Height of a card tab pane around its content: borders, tab strip and content padding. */
    static double tabChrome(TabPane pane) {
        Node strip = pane.lookup(".tab-header-area");
        Node area = pane.lookup(".tab-content-area");
        if (!(strip instanceof Region s) || !(area instanceof Region a) || s.getHeight() <= 0) {
            return Double.NaN;
        }
        return pane.snappedTopInset() + pane.snappedBottomInset() + s.getHeight()
                + a.snappedTopInset() + a.snappedBottomInset();
    }

    /**
     * Height of {@code rows} equal rows ({@code row} high, {@code gap} apart) below a fixed
     * {@code head}: all of them when they fit into {@code max}, otherwise as many whole rows as
     * fit, so a scrolled list ends on a row edge instead of cutting one in half.
     */
    static double wholeRows(double max, double head, double row, double gap, int rows) {
        int fit = (int) Math.floor((max - head + gap) / (row + gap));
        int shown = Math.clamp(fit, 1, Math.max(1, rows));
        return head + shown * (row + gap) - gap;
    }

    /**
     * Table cell that raises its column's minimum width to the rendered text, so values are
     * never cut off at any font size; see {@link #fitColumns} for tables too narrow for that.
     */
    static class FitCell<T> extends TableCell<T, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item);
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            TableColumn<T, String> col = getTableColumn();
            if (col != null && getText() != null) {
                widen(col, textWidth(getText(), getFont()) + snappedLeftInset() + snappedRightInset());
            }
        }
    }

    private static <T> TableColumn<T, String> column(String name, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>();
        Matcher m = UNIT.matcher(name);
        String head = m.matches() ? m.group(1) : name;
        Label top = new Label(head);
        top.getStyleClass().add("col-name");
        Label unit = new Label(m.matches() ? m.group(2) : " ");
        unit.getStyleClass().add("col-unit");
        VBox header = new VBox(0, top, unit);
        header.getStyleClass().add("col-head");
        c.setGraphic(header);
        // the header text sets the first minimum (once CSS has given the labels their font)
        for (Label l : List.of(top, unit)) {
            l.fontProperty().addListener((o, a, f) -> widen(c, textWidth(l.getText(), f) + HEADER_PADDING));
        }
        c.setMinWidth(40);
        c.setPrefWidth(60);
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue())));
        c.setCellFactory(col -> new FitCell<>());
        c.setSortable(false);
        c.setReorderable(false);
        return c;
    }

    private static void widen(TableColumn<?, ?> col, double width) {
        double w = Math.ceil(width) + 1;
        if (w > col.getMinWidth()) {
            col.setMinWidth(w);
            col.setPrefWidth(Math.max(col.getPrefWidth(), w));
        }
    }

    static double textWidth(String text, Font font) {
        Text t = new Text(text);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }
}
