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
   and offers to save your MySQL password as `NSC_DB_PASSWORD`.
3. IntelliJ: open the repository root (it has `NSC-SWENG-Session-UI.iml`), pick a JDK 17+, and use the
   shared run configurations **Nickel Sales (DevApp)** and **AuthDemo (console)**. Don't use the green
   arrow next to `main()`: it has no JavaFX options. Eclipse/VS Code: open the `nickel-sales/` folder
   (it has its own `.project` and `.classpath`).

### Environment variables

| Variable | Default |
| -------- | ------- |
| `NSC_DB_URL` | `jdbc:mysql://localhost:3306/NickelSales_Database?...` |
| `NSC_DB_USER` | `root` |
| `NSC_DB_PASSWORD` | none: you must set it. **Never put a real password in code or in the repo.** |
| `NSC_IDLE_TIMEOUT_MINUTES` | `30` |
| `NSC_TERMINAL_ID` | `T01` (invoice numbers; each terminal needs its own) |
| `NSC_STORE_NAME` / `NSC_STORE_ADDRESS` / `NSC_STORE_TIN` / `NSC_STORE_PHONE` | printed on receipts |
| `NSC_RECEIPT_WIDTH` | `42` (80 mm paper; 32 for 58 mm) |

Windows: `setx NSC_DB_PASSWORD "..."`, then open a **new** terminal (restart IntelliJ too).

## Run

| Command (from `nickel-sales/`) | What it does |
| ------------------------------ | ------------ |
| `run-demo.bat` / `./run-demo.sh` | Starts `app.DevApp` (test folder): the real app with in-memory **test products**, so POS can be tried until the real Product/Inventory modules are wired into `app.Main`. Users and sales are in MySQL. |
| `run-demo.bat --console` | Console login demo (`auth.AuthDemo`), no database needed |
| `run-demo.bat --scripted` | Compiles `src/` and `test/` into a clean `out/` and runs **all** self-tests. Exit code 1 if any fail. |

`app.Main` (in `src/`) is the production entry point. It does not use `test/`; POS stays disabled
in it until a `ProductLookup` and `StockRecorder` are passed to `AppContext.create`.

## Self-tests (`--scripted`)

`AuthDemo`, `AuthzDemo`, `UserAdminDemo`, `PosContractCheck`, `CartCheck`, `CheckoutCheck`,
`ReceiptCheck`, `AppCheck`. All must pass (`ALL CHECKS PASSED`).
`PosContractCheck`, `CheckoutCheck` and `ReceiptCheck` also exercise MySQL and need the database set up
**with the current schema**. If your database was created by an older script they fail with
`Table ... doesn't exist` or `Sale.SaleID is missing`: run `setup.bat` again, or see below.
Always use `run-demo.bat --scripted`; running `java ...` by hand can pick an older JDK.

Demo accounts (password `Password123`): `JCubillan` (all roles), `JDelaCruz` (cashier),
`MSantaAna` (inventory), `RSantos` (inactive), `KRodriguez` (locked). Log in with the login name,
not the `NSC-0000x` ID.

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
