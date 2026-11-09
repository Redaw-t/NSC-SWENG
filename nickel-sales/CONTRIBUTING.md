# Nickel Sales Center — Setup, run and test

Everything about **where code goes and how to collaborate** is in
[docs/development/](../docs/development/): [ARCHITECTURE](../docs/development/ARCHITECTURE.md),
[MODULE_TEMPLATE](../docs/development/MODULE_TEMPLATE.md),
[NAMING_AND_STRUCTURE](../docs/development/NAMING_AND_STRUCTURE.md) and
[CONTRIBUTING](../docs/development/CONTRIBUTING.md). The map of every folder is in
[docs/REPOSITORY_MAP.md](../docs/REPOSITORY_MAP.md). The original Iteration 1 guide (role specs, POS
design history) is archived in
[docs/archive/ITERATION1_CONTRIBUTOR_GUIDE.md](../docs/archive/ITERATION1_CONTRIBUTOR_GUIDE.md).

This file covers this folder only: setting up a machine, running the app, and running the self-tests.
The step-by-step Windows guide for non-developers is the root [README](../README.md).

## Set up your machine

| Tool | Version | Notes |
| ---- | ------- | ----- |
| JDK | 17 or newer | Windows: `setup.bat` finds one or offers to install Temurin 21 |
| JavaFX | 21.0.5 | Downloaded by setup into `lib/javafx/` (not committed) |
| jBCrypt, MySQL Connector/J | 0.4, 9.4.0 | Already in `lib/` |
| MySQL Server | 8.x | You install it. Needed for login and saved sales, not for `--console` / `--scripted` unit checks |

No Maven or Gradle is used.

1. **Windows:** run `setup.bat` until it says *Everything is ready*. **macOS/Linux:**
   `chmod +x setup.sh setup-database.sh run-demo.sh` once, then `./setup.sh`.
2. It creates `NickelSales_Database` from [`../database/NickelSales-Database-Config.sql`](../database/NickelSales-Database-Config.sql)
   (the schema), adds the demo users and test catalog from [`../database/NickelSales-Demo-Data.sql`](../database/NickelSales-Demo-Data.sql), and offers to save your MySQL password as `NSC_DB_PASSWORD`.
3. IntelliJ: open the repository root (it has `NSC-SWENG-Session-UI.iml`), pick a JDK 17+, and use the
   shared run configurations **Nickel Sales (DevApp)** and **AuthDemo (console)**. Don't use the green
   arrow next to `main()`: it has no JavaFX options. Eclipse/VS Code: open the `nickel-sales/` folder
   (it has its own `.project` and `.classpath`).

### Settings

Every setting below can be an environment variable or a line in a **settings file**.
- Development runs (`run-demo`, DevApp, IDE, self-tests) use **environment variables**, plus a
  settings file only if `NSC_SETTINGS_FILE` names one. They never read the installer's file, so
  installing the app on your PC doesn't change your development database.
- The **installed app** reads the file the installer writes, `%ProgramData%\NickelSales\nickelsales.properties`
  (macOS/Linux: `~/.nickelsales/nickelsales.properties`), and the file wins over environment variables.

See [`config/nickelsales.properties.example`](config/nickelsales.properties.example) and
`shared/Settings.java`. The app prints which settings it used when it starts.

| Setting | Default |
| -------- | ------- |
| `NSC_DB_URL` | `jdbc:mysql://localhost:3306/NickelSales_Database?...` |
| `NSC_DB_USER` | `root` |
| `NSC_DB_PASSWORD` | none: you must set it. **Never put a real password in code or in the repo.** |
| `NSC_IDLE_TIMEOUT_MINUTES` | `30` |
| `NSC_TERMINAL_ID` | `T01` (invoice numbers; each terminal needs its own) |
| `NSC_STORE_NAME` / `NSC_STORE_ADDRESS` / `NSC_STORE_PROPRIETOR` / `NSC_STORE_TIN` / `NSC_STORE_PHONE` | only until store details are saved in the database (Installation Setup's Store Register, or Settings > General > System Configuration > Store Details); then the newest `StoreDetails` row wins. Address, proprietor and TIN default to the store's own |
| `NSC_PRINTER_NAME` | none: the printer chosen in Settings > Printers, else the Windows default |
| `NSC_PEER_URL` | none: no longer used (System Status shows the sync link with the Main POS) |
| `NSC_LOG_DIR` | `logs` next to the settings file (`%ProgramData%\NickelSales\logs`), else `%LOCALAPPDATA%\NickelSales\logs`. Only the installed app (`app.Main`) writes a log file, one per day, kept 30 days |

The version is `shared/AppVersion.java` (shown as "App Version: x.y.z" at the bottom right of Settings; the installer uses it).
Raise it for every build given to a store. Building the Windows app: [`../installer/README.md`](../installer/README.md).

Windows: `setx NSC_DB_PASSWORD "..."`, then open a **new** terminal (restart IntelliJ too).

## Run

| Command (from `nickel-sales/`) | What it does |
| ------------------------------ | ------------ |
| `run-demo.bat` / `./run-demo.sh` | Starts `app.DevApp` (test folder): the real app (real products, users and sales in MySQL) plus a stand-in stock recorder, so POS can sell until the Inventory module's `StockRecorder` is wired into `app.Main`. |
| `run-demo.bat --console` | Console login demo (`auth.AuthDemo`), no database needed |
| `run-demo.bat --scripted` | Compiles `src/` and `test/` into a clean `out/` and runs **all** self-tests. Exit code 1 if any fail. |

`app.Main` (in `src/`) is the production entry point. It does not use `test/`. It wires the Product
module (real products from MySQL), but POS cannot complete a sale in it until the Inventory module's
`StockRecorder` is passed to `wire(...)`. `app.DevApp` (what `run-demo.bat` starts) is `Main` plus a
stand-in recorder, so you can sell real products today.

## Self-tests (`--scripted`)

`AuthDemo`, `AuthzDemo`, `UserAdminDemo`, `PosContractCheck`, `CartCheck`, `CheckoutCheck`,
`ReceiptCheck`, `AppCheck`, `ProductCheck`. All must pass (`ALL CHECKS PASSED`).
`PosContractCheck`, `CartCheck`, `CheckoutCheck`, `ReceiptCheck` and `ProductCheck` use MySQL (the real product catalog;
`CartCheck` skips entirely without it, the others skip their database scenarios). The SQL script seeds nothing, so
they add their own fixture (nine test products P-0001 to P-0009 and an inactive test cashier `TST-00002`) and
reset it around every scenario: run them on a development database, never a live one. The database must be set up
**with the current schema**. If your database was created by an older script they fail with
`Table ... doesn't exist` or `Sale.SaleID is missing`: run `setup.bat` again, or see below.
Always use `run-demo.bat --scripted`; running `java ...` by hand can pick an older JDK.

There are no demo accounts in the database: on first run, Installation Setup (Main POS) creates the
admin account `NSC-00001` with the username and password you choose; add other users in Manage users.
Log in with the login name, not the `NSC-0000x` ID. (`run-demo.bat --console` still has built-in stub users.)

## Changing the database

The script is safe to run again (`CREATE TABLE IF NOT EXISTS`, `INSERT IGNORE`). `setup.*` re-runs it.

- **New table:** teammates just run setup after pulling.
- **New column/index on an existing table:** add it to the `CREATE TABLE` **and** the "UPGRADES" block at
  the end of the script (copy a block there). It is only added when missing.
- **Renamed/changed column:** the script cannot alter it. Everyone with an older database must
  `DROP DATABASE NickelSales_Database;` (this deletes data) and run setup again. Say so in the commit
  with a `BREAKING CHANGE:` footer.

Update the SQL file, the code that reads it, the stand-ins in `test/`, and the docs together.

## Troubleshooting

| Symptom | Fix |
| ------- | --- |
| Window flashes and closes | Run `run-demo.bat` from a terminal to read the error |
| `No JDK found` / `javac is not recognized` | Run `setup.bat`, or install JDK 17+ and set `JAVA_HOME`; reopen the terminal |
| `Module javafx.controls not found` | `lib/javafx/` is missing: run setup |
| `JavaFX runtime components are missing` | Use the **Nickel Sales (DevApp)** run configuration, not the `main()` arrow |
| `package javafx... does not exist` in IntelliJ | Run setup, then **File → Reload All from Disk** |
| JavaFX download fails | Check internet/proxy for `repo1.maven.org`, or download the three `javafx-*-21.0.5-win.jar` files into `lib/javafx/` by hand |
| Changes don't show up | The scripts rebuild `out/` every run; in an IDE use **Rebuild Project** |
| Every login fails | The app can't reach MySQL: run `setup.bat` and read the error printed in the console window |
| "The database is out of date…" after pulling | Run `setup.bat` once (see "Changing the database") |
| `./run-demo.sh: Permission denied` | `chmod +x run-demo.sh` |
