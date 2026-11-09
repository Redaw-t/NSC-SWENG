package shared;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens connections to {@code nickelsales_appconfig}: the app's own configuration and state
 * (e.g. which POS terminals are set up), kept apart from the store's records in
 * NickelSales_Database ({@link Database}). Created by database/NickelSales-AppConfig.sql
 * (setup.bat / setup.sh apply it).
 *
 * Same MySQL server and login as {@link Database}: NSC_DB_URL's server, NSC_DB_USER and
 * NSC_DB_PASSWORD, read through {@link Settings} (so the installed app uses the installer's
 * settings file, port 3307 and its own MySQL user). Override the address with NSC_APPCONFIG_DB_URL.
 */
public final class AppConfigDatabase {

    /** The database's name, as created by the SQL script. */
    public static final String NAME = "nickelsales_appconfig";

    private static final String URL = Settings.get("NSC_APPCONFIG_DB_URL",
            sameServer(Settings.get("NSC_DB_URL", Database.DEFAULT_URL)));
    private static final String USER = Settings.get("NSC_DB_USER", "root");
    private static final String PASSWORD = Settings.get("NSC_DB_PASSWORD", "");

    private AppConfigDatabase() { }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /**
     * NSC_DB_URL with this database's name in place of NickelSales_Database, e.g.
     * jdbc:mysql://localhost:3307/nickelsales_appconfig?... for the installed app.
     */
    static String sameServer(String storeUrl) {
        int slash = storeUrl.indexOf('/', storeUrl.indexOf("//") + 2);
        if (slash < 0) {
            return storeUrl + "/" + NAME;
        }
        int query = storeUrl.indexOf('?', slash);
        return storeUrl.substring(0, slash + 1) + NAME + (query < 0 ? "" : storeUrl.substring(query));
    }
}
