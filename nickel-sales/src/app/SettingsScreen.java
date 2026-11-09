package app;

import auth.ui.ChangePasswordDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import shared.PrinterSettings;
import shared.Styles;
import shared.TabBar;
import shared.Theme;
import system.ui.SystemSettingsTabs;

import java.util.ArrayList;
import java.util.List;

/**
 * The Settings screen (sidebar, bottom entry), with a tab strip like the Products screen:
 * - General (everyone): Account (change password) and Printers (receipt and barcode label printer
 *   for this computer, see {@link PrinterSettingsDialog}).
 * - Store Details (receipt header, with its change log), System Status and Backup &amp; Restore: Admin
 *   only (MANAGE_SYSTEM), from {@link SystemSettingsTabs}.
 * (Activity, inventory and sync logs stay on the Audit Logs screen.)
 * The app's version ({@link shared.AppVersion}) is shown at the bottom right ("App Version: 0.1.0").
 */
public final class SettingsScreen {

    private SettingsScreen() { }

    public static Parent create(ScreenContext screen) {
        List<String> labels = new ArrayList<>();
        List<Parent> views = new ArrayList<>();
        List<Runnable> refreshes = new ArrayList<>();

        labels.add("General");
        views.add(general(screen));
        refreshes.add(() -> { });
        for (SystemSettingsTabs.Tab tab : SystemSettingsTabs.forScreen(screen)) {
            labels.add(tab.label());
            views.add(tab.view());
            refreshes.add(tab.refresh());
        }

        BorderPane body = new BorderPane();
        VBox view = new VBox(20);
        if (labels.size() == 1) {
            body.setCenter(views.get(0));   // only General: no tab strip needed
            view.getChildren().add(body);
        } else {
            TabBar[] tabs = new TabBar[1];
            tabs[0] = new TabBar(labels, n -> {
                tabs[0].select(n);
                body.setCenter(views.get(n));
                refreshes.get(n).run();
            });
            tabs[0].select(0);
            body.setCenter(views.get(0));
            view.getChildren().addAll(tabs[0].getView(), body);
        }
        VBox.setVgrow(body, Priority.ALWAYS);
        view.getChildren().add(versionLine());
        return view;
    }

    /** "App Version: 0.1.0" at the bottom right of every Settings tab (support asks for it). */
    private static HBox versionLine() {
        Label version = new Label("App Version: " + shared.AppVersion.VERSION);
        version.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        HBox line = new HBox(version);
        line.setAlignment(Pos.BOTTOM_RIGHT);
        line.setMinHeight(Region.USE_PREF_SIZE);
        return line;
    }

    /** The General tab: one full-width card per setting, stacked like a settings list. */
    private static Parent general(ScreenContext screen) {
        Button changePassword = new Button("Change Password");
        Styles.primaryButton(changePassword);
        changePassword.setOnAction(e ->
                new ChangePasswordDialog(screen.stage(), screen.app().accounts(), screen.sessionId()).show());

        Label receiptValue = new Label();
        Label labelValue = new Label();
        Runnable showPrinters = () -> {
            receiptValue.setText(printerText(PrinterSettings.Use.RECEIPT));
            labelValue.setText(printerText(PrinterSettings.Use.LABEL));
        };
        showPrinters.run();
        Button printerSettings = new Button("Printer Settings");
        Styles.primaryButton(printerSettings);
        printerSettings.setOnAction(e -> {
            if (PrinterSettingsDialog.show(screen.stage())) {
                showPrinters.run();
            }
        });

        VBox printerRows = new VBox(
                detailRow("Receipt printer", receiptValue, false),
                detailRow("Barcode label printer", labelValue, true));

        VBox list = new VBox(16,
                card("Account", "Change the password you use to log in.", changePassword, null),
                card("Printers", "The printers this computer uses for receipts and barcode labels.",
                        printerSettings, printerRows));
        // Additional POS, Admin only: the Main POS's address it syncs with (found by itself; this is the fallback).
        MainPosAddressCard.create(screen).ifPresent(list.getChildren()::add);
        list.setMaxWidth(820);
        list.setAlignment(Pos.TOP_LEFT);
        return list;
    }

    /** "Default printer (POS-58)" when nothing is chosen, else the chosen printer's name. */
    private static String printerText(PrinterSettings.Use use) {
        String chosen = PrinterSettings.chosen(use);
        if (chosen != null) {
            return chosen;
        }
        String def;
        try {
            def = PrinterSettings.systemDefault();
        } catch (RuntimeException e) {
            def = null;
        }
        return def == null ? "Default printer" : "Default printer (" + def + ")";
    }

    /**
     * A settings card: title and description on the left, the action button on the right, and
     * optional details underneath (separated by a thin line).
     */
    private static VBox card(String title, String text, Button action, Node details) {
        Label name = new Label(title);
        name.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label note = new Label(text);
        note.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        note.setWrapText(true);
        VBox words = new VBox(4, name, note);
        HBox.setHgrow(words, Priority.ALWAYS);

        action.setMinWidth(180);
        HBox header = new HBox(24, words, action);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(header);
        if (details != null) {
            Region line = new Region();
            line.setMinHeight(1);
            line.setPrefHeight(1);
            line.setStyle("-fx-background-color: " + Theme.BORDER + ";");
            VBox.setMargin(line, new Insets(18, 0, 6, 0));
            card.getChildren().addAll(line, details);
        }
        Styles.card(card);
        card.setPadding(new Insets(22, 28, 22, 28));
        return card;
    }

    /** One "name ........ value" line under a card; a divider above it unless it is the first. */
    private static HBox detailRow(String key, Label value, boolean divider) {
        Label name = new Label(key);
        name.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        value.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        HBox row = new HBox(12, name, fill, value);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 0, 10, 0));
        if (divider) {
            row.setStyle("-fx-border-color: " + Theme.BORDER + " transparent transparent transparent; "
                    + "-fx-border-width: 1 0 0 0;");
        }
        return row;
    }
}