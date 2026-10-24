# Nickel Sales Center — Contributor Guide (Sprint 1)

*Keep this file up to date as you work on other areas of the project — it is
the guide for the next developer or QA.*

## Who this document is for

This document is for **whoever continues work on this project — a human
programmer or an AI assistant**. It explains:

1. how to get the project running on your machine (start here),
2. how the authentication module works,
3. which parts are done and which parts are still to be built,
4. exactly how to build the missing parts so they plug in without breaking
   anything.

Work in this project is organized by **functional role**, not by person. The
roles are `AUTH`, `DATA`, `SESSION/UI`, and `AUTHZ`. Wherever this document
says a role, read it as: *the person or AI assistant currently assigned to
that role*. If you are an AI assistant, section 3 lists the exact signatures,
notes and definition of done for each role — follow the one matching your
assignment literally.

**Project context:** desktop POS and Inventory Management System for Nickel
Sales Center (offline, local MySQL database). Sprint 1 delivers an
end-to-end login flow: credential validation, bcrypt password verification,
session creation, and role recognition. The `nickel-sales/` folder currently
contains the **authentication module**, the **session manager and login
screen** (with show-password, Caps Lock warning and idle logout),
**authorization and role routing** (`AUTHZ`), a MySQL-backed **user
repository** (`DATA`), and **user account management** (admins add, edit and
activate users; everyone can change their own password).

**Logging in uses a login name** such as `JCubillan` — not the User ID
(`NSC-00001`). The User ID is only the internal key.

---

## 0. Quick start — set up your machine

### What you need

| Tool | Version | Notes |
| ---- | ------- | ----- |
| JDK | **17 or newer** | Windows: `setup.bat` finds one or offers to install Temurin 21 for you |
| JavaFX | 21.0.5 | **Downloaded automatically** into `nickel-sales/lib/javafx/` — don't install it yourself |
| jBCrypt | 0.4 | Already in `nickel-sales/lib/` |
| IntelliJ IDEA | any recent | Optional — the scripts work without it |
| MySQL Server | 8.x | **You install it** — [MySQL Installer](https://dev.mysql.com/downloads/installer/), "Server only". Needed for the login screen (the default demo), not for `--console` / `--scripted`. `setup.bat` then creates the database for you |
| MySQL Connector/J | 9.4.0 | Already in `nickel-sales/lib/` |

No Maven or Gradle is needed.

### Windows

1. Get the project (clone the repo or copy the folder).
2. Open `nickel-sales\` and double-click **`setup.bat`**. It checks every
   dependency and prints what is OK, what is missing, and **where to download
   anything you need to install**:
   - **JDK 17+** — looks in `JAVA_HOME`, `PATH`, `C:\Program Files\...` and
     IntelliJ's JDKs (`%USERPROFILE%\.jdks`). If none: prints the download
     link, and offers to install Temurin JDK 21 for you via `winget`.
   - **JavaFX** — downloads the three jars into `nickel-sales\lib\javafx\`.
   - **MySQL Server** — if it isn't installed or isn't running, tells you
     where to get it / how to start it. Once it is running, it creates
     `NickelSales_Database` (asking first) and offers to save your MySQL
     password as `NSC_DB_PASSWORD` (the password is typed hidden).
3. Install anything it reports as missing, then run `setup.bat` again until
   it says **Everything is ready**.
4. Double-click **`run-demo.bat`**. The login screen opens — log in as
   `JCubillan` / `Password123`.

No MySQL yet? Run `run-demo.bat --scripted` in a terminal instead; it runs
the self-tests (mostly on built-in test data) and should end with
**ALL CHECKS PASSED**.

You can skip step 2 — `run-demo.bat` runs `setup.bat` itself if anything is
missing. The first run needs internet access (to download JavaFX); after
that everything works offline.

> **Pulled new changes and now every login fails?** If someone changed the
> database script, your existing database is out of date (the script only
> creates tables that don't exist yet). See
> [6.4 Changing the database](#64-changing-the-database).

### MySQL (needed for the login screen)

**Windows:** install MySQL Server 8 from
<https://dev.mysql.com/downloads/installer/> ("MySQL Installer for Windows",
choose **Server only**, keep port 3306, remember the root password), then run
`setup.bat` — it creates the database and saves your password for the app.
XAMPP/MariaDB also works as long as its MySQL server is running.

**By hand** (macOS/Linux, or if `setup.bat` can't find `mysql.exe`):

1. Run `database/NickelSales-Database-Config.sql` once (MySQL Workbench:
   File → Open SQL Script → run, or
   `mysql -u root -p < database/NickelSales-Database-Config.sql`).
   It creates `NickelSales_Database` and the demo users.
2. Tell the app your MySQL login. `shared.Database` reads these
   environment variables:

   | Variable | Default |
   | -------- | ------- |
   | `NSC_DB_URL` | `jdbc:mysql://localhost:3306/NickelSales_Database?...` |
   | `NSC_DB_USER` | `root` |
   | `NSC_DB_PASSWORD` | *none — you must set it* |
   | `NSC_IDLE_TIMEOUT_MINUTES` | `30` — minutes without mouse/keyboard activity before automatic logout |
   | `NSC_TERMINAL_ID` | `T01` — this terminal's code in invoice numbers; give each terminal its own (`T02`, ...) |
   | `NSC_STORE_NAME` / `NSC_STORE_ADDRESS` / `NSC_STORE_TIN` / `NSC_STORE_PHONE` | printed at the top of receipts (name defaults to `NICKEL SALES CENTER`; the others are left off if not set) |
   | `NSC_RECEIPT_WIDTH` | `42` — characters per receipt line: 42 for 80 mm paper, 32 for 58 mm |

   Windows: `setx NSC_DB_PASSWORD "your-mysql-password"`, then open a **new**
   terminal. macOS/Linux: `export NSC_DB_PASSWORD=...` in your shell profile.
   IntelliJ/Eclipse: add it to the run configuration's environment variables.
   **Never put a real password in the code** — the repo is public.

### macOS / Linux

`setup.sh` does the same checks as `setup.bat`: JDK (offers a Homebrew
install on macOS), JavaFX download, MySQL check and database creation.

```bash
cd nickel-sales
chmod +x setup.sh setup-database.sh run-demo.sh   # first time only
./setup.sh                # check dependencies, set up the database
./run-demo.sh             # the app (add --console or --scripted for the stub demos)
```

### IntelliJ IDEA

1. Run `setup.bat` (or `./run-demo.sh --setup`) once so `lib/javafx/` exists.
2. **File → Open** the repository root folder (the one containing
   `NSC-SWENG-Session-UI.iml`).
3. **File → Project Structure → Project → SDK**: pick a JDK 17+.
4. Use the shared run configurations in the top-right dropdown:
   **AuthDemo (console)** and **Nickel Sales (DevApp)**. They already pass the
   JavaFX VM options, so don't run `DevApp` or `Main` via the green arrow next to
   `main()` — that run has no JavaFX options and fails with
   *"JavaFX runtime components are missing"*.

The `jbcrypt-0.4`, `mysql-connector-j` and `javafx` libraries are already
configured in `NSC-SWENG-Session-UI.iml`, and the run configurations live in
`.run/`. IntelliJ only sees `NSC_DB_PASSWORD` if it was set **before**
IntelliJ started — restart IntelliJ after running `setup.bat`, or add the
variable to the run configuration.

---

## 1. How this program works

### 1.1 The big picture

The system is built around one rule: **the authentication module owns login
logic and nothing else.** Everything it needs from the outside world is
declared as a *contract* — a Java interface (or a minimal entity shape) in
`src/auth/contract/`. Other modules implement those contracts;
authentication never sees their code, and they never see authentication's
internals. The connections are made once, in a single wiring spot, by
passing implementations into the constructor.

```
                +----------------------------+
   login UI --->|   AuthenticationService    |<--- UserRepository (DATA)
 (SESSION/UI)   |   (this module, DONE)      |<--- SessionManager (SESSION/UI)
                +----------------------------+<--- AuditLogger    (logging)
                            |
                     PasswordPolicy (shared rules)
```

### 1.2 What happens on a login, step by step

`AuthenticationService.login(username, password)`:

1. **Look up the user.** Calls `UserRepository.findByUsername(...)`, which
   matches the `UserLoginName` column (e.g. `JCubillan`), ignoring case and
   surrounding spaces. The User ID (`NSC-00001`) is **not** a login. Not
   found → one bcrypt check against a dummy hash (so "unknown user" takes
   exactly as long as "wrong password"), then throws
   `InvalidCredentialsException`.
2. **Verify the password.** `BCrypt.checkpw(password, user.getPasswordHash())`
   against the stored 60-character bcrypt hash. Plaintext is never stored or
   compared. Mismatch → `InvalidCredentialsException`.
3. **Check account status — only now.** `INACTIVE` →
   `InactiveUserException`; `LOCKED` → `LockedUserException`. Status is
   checked *after* the password is proven, so account state is never
   revealed to someone who does not know the password.
4. **Create a session.** Calls `SessionManager.createSession(userId)` and
   returns `LoginResult(user, session)`. If no SessionManager is wired in,
   `session` is `null` and login still succeeds.
5. **Audit-log the attempt.** One of `LOGIN_SUCCESS`, `LOGIN_FAILED`,
   `LOGIN_BLOCKED_INACTIVE`, `LOGIN_BLOCKED_LOCKED` goes to the
   `AuditLogger`.

The other public methods:

- `validateCredentials(username, password)` → pure boolean check; never
  throws, never creates a session, counts database problems as "not valid".
- `getCurrentUser(sessionId)` → the logged-in `User`, or `null` when the
  session is unknown/expired/logged out. Re-reads the user from the
  repository each call, so role or status changes apply immediately.
- `logout(sessionId)` → invalidates the session, logs `LOGOUT`. Safe on
  unknown or expired ids.

### 1.3 Errors the UI must handle

Every failure is an `AuthenticationException` subclass with a stable
machine-readable `getCode()` — the login screen catches the base type and
switches on the code. The `getMessage()` strings are already user-ready.

| Exception | Code | Meaning |
| --------- | ---- | ------- |
| `InvalidCredentialsException` | `INVALID_CREDENTIALS` | Wrong username or wrong password — deliberately identical, so nobody can probe which usernames exist |
| `InactiveUserException` | `INACTIVE_USER` | Correct password, inactive account |
| `LockedUserException` | `LOCKED_USER` | Correct password, locked account |

A corrupt/malformed stored hash also counts as `INVALID_CREDENTIALS` rather
than crashing — database damage must not turn into a stack trace on screen.


### 1.4 File map — what is real, what is a contract

```
NSC-SWENG/                             repository root (open this in IntelliJ)
  README.md                            setup guide for everyone
  docs/                                project documents (requirements, class diagram, plans)
                                       - PDFs here are kept local, not committed
  database/
    NickelSales-Database-Config.sql    MySQL schema + seed data, safe to re-run
  nickel-sales/                        the Java application
    src/                               the real code, one folder per module
      app/                             <- the app itself (POS Phase 3, DONE) - section 5
        Main.java                        the real app's entry point (the wiring spot)
        NickelSalesApp.java              the JavaFX application: opens the window on the login screen
        AppConfig.java                   terminal ID and idle timeout (NSC_TERMINAL_ID, NSC_IDLE_TIMEOUT_MINUTES)
        AppContext.java                  the shared services every screen gets (auth, sales, ...)
        Modules.java                     THE ONE LIST of home-screen buttons - add your module here
        AppModule.java                   what a module provides: title, permission, its screen
        ScreenContext.java               what a module's screen gets: window, session, user, services, Back
        HomeScreen.java                  after login: a button per module the user may use (keys 1-9)
        DashboardRouter.java             login -> home -> module screens; re-checks access; idle logout
        IdleLogoutTimer.java             restarts on any key/mouse/touch; fires after the idle timeout
        ComingSoonModule.java            placeholder button for modules not built yet
      shared/                          <- used by EVERY module
        Money.java                       BigDecimal, 2 decimals, half-up; format "1,234.50"
        Quantity.java                    BigDecimal, 3 decimals; shown as "3" or "1.5"
        PhTime.java                      stored in UTC, shown / grouped by day in Philippine time
        Database.java                    JDBC connections (NSC_DB_* env vars) + inTransaction(...)
        UiErrors.java                    turns database errors into messages people can act on
      auth/                            <- LOGIN, USERS AND PERMISSIONS: DONE
        contract/                        AGREEMENTS ONLY (no behavior)
          User.java                        entity shape the auth module reads
          UserRole.java                    ADMIN/CASHIER/INVENTORY_STAFF <-> DB codes "A"/"C"/"I"
          UserStatus.java                  ACTIVE/INACTIVE/LOCKED <-> "Active"/"Inactive"
          Session.java                     entity shape (sessionId, userId, timestamps, active)
          UserRepository.java              interface - implemented in storage/
          SessionManager.java              interface - implemented in session/
          PasswordPolicy.java              interface - used by user management
          AuditLogger.java                 interface - implemented by logging
        login/                           all login logic lives here
          AuthenticationService.java       login / logout / current user
          LoginResult.java                 record: user() + session()
          AuthenticationException.java     base error, catch this in UIs
          InvalidCredentialsException.java
          InactiveUserException.java
          LockedUserException.java
        session/
          InMemorySessionManager.java      implements contract.SessionManager
        account/                         user accounts (section 3.5)
          UserAdminService.java            admin: create / edit / activate / deactivate users
          UserAccountService.java          anyone: change own password
          PassPolicy.java                  password rules (8-72 chars, an uppercase letter, a number)
          AccountException.java            user-ready error shown by the admin screens
          PasswordChangeException.java     user-ready error shown by Change password
        permission/
          Permission.java                  the six actions a user can be allowed to do
          AuthorizationService.java        role -> permission matrix; checkAccess()
        storage/
          MySqlUserRepository.java         implements contract.UserRepository (MySQL)
        ui/
          LoginScreen.java                 login form (SHOW/HIDE, Caps Lock warning)
          LogoutAction.java                logout handler + button
          ChangePasswordDialog.java        "Change password" on every dashboard
          UserManagementScreen.java        list users; Add / Edit / Activate-Deactivate
          UserFormDialog.java              the Add user / Edit user form
          UserAdminModule.java             the "Manage users" button (admins)
      pos/                             <- POS & SALES (section 3.6)
        contract/                        how POS talks to the other modules
          ProductInfo / ProductLookup      what POS needs from Product & Inventory
          StockRecorder                    stock-out when a sale completes (Product & Inventory implements)
          InsufficientStockException       "Not enough stock for ...", shown to the cashier
          SaleRecord / SaleLineRecord /    a completed sale, never changed afterwards
            PaymentRecord / PriceType / PaymentMethod
          SaleExport                       what Sync reads (POS implements)
          SaleHistory                      what Reports reads: by ID, invoice no., date range (POS implements)
        cart/                            the sale being rung up (Phase 1, DONE)
          SalesService                     start sale, add / change / remove items, totals, cancel
          Sale / SaleLineItem / SaleStatus the open sale and its lines (one line per product)
          SaleTotals / Vat                 subtotal, VATable, VAT-exempt, VAT (12% added), total
          PricingService / PricingRule /   retail below 6, wholesale from 6 (WHOLESALE_MIN_QTY)
            ThresholdPricingRule
          CartException                    "No product has the barcode ...", shown to the cashier
          StockProblem                     a line above the current stock: Pay stays greyed out
        checkout/                        paying and saving a sale (Phase 2, DONE)
          PaymentService                   cash (change) or GCash / Maya / QRPh (reference no.)
          CheckoutService                  ONE transaction: invoice no. + save + stock-out; logs SALE_COMPLETED
          InvoiceNumberGenerator           T01-2026-000001 from InvoiceSequence (no gaps, no repeats)
          SaleRepository                   save + read completed sales
          MySqlSaleRepository              the MySQL one; also implements SaleHistory and SaleExport
          CheckoutException                "Cash received (...) is less than the total (...)."
        ui/                              the cashier's screen (Phase 4, DONE)
          PosModule.java                   the "Point of Sale" button (cashiers, admins)
          PosScreen.java                   barcode + qty entry, cart table, totals, Pay; all keyboard
          PaymentDialog.java               cash (live change) or GCash / Maya / QRPh (reference no.)
          ProductSearchDialog.java         F3: find products without a barcode
          QuantityDialog.java              F4: change a line's quantity
          SaleCompleteDialog.java          the change to hand back + the receipt (Enter prints)
          ReceiptDialog.java               receipt preview: Enter print, Ctrl+P choose printer, Esc close
          ReprintDialog.java               F7: find a past sale by invoice number
        receipt/                         receipts (Phase 5, DONE)
          Receipt.java                     the receipt as fixed-width lines (same on screen and paper)
          ReceiptBuilder.java              makes a Receipt from the SAVED sale (+ store details, cashier name)
          StoreInfo.java                   store name, address, TIN, phone (from NSC_STORE_*)
          PrinterPort.java                 "print this receipt" - swap in another printer here
          JavaFxReceiptPrinter.java        prints through Windows printing, sized to the paper
          ReceiptView.java                 draws the lines in a monospaced font
          ReceiptPrintException.java       "No printer is set up on this computer..."
      inventory/  sync/  reports/      <- where the other modules go (create when you start)
    test/                              self-tests and stand-ins, same folders as src/
      app/DevApp.java                  the app with TEST products (what run-demo.bat starts until Phase 6)
      app/AppCheck.java                self-test: settings, module list, buttons per role (Phase 3)
      auth/AuthDemo.java               console demo + stub UserRepository (no DB needed)
      auth/AuthzDemo.java              console check of the role -> permission matrix
      auth/UserAdminDemo.java          console check of user management rules
      pos/PosContractCheck.java        self-test for the shared rules and POS contracts
      pos/CartCheck.java               self-test for the cart and pricing (Phase 1)
      pos/CheckoutCheck.java           self-test for payment, invoice numbers, checkout (Phase 2)
      pos/ReceiptCheck.java            self-test: receipts show exactly the saved sale (Phase 5)
      pos/stub/StubInventory.java      TEST stand-in for Product & Inventory (sample products, stock)
    lib/
      jbcrypt-0.4.jar                  bcrypt hashing (committed)
      mysql-connector-j-9.4.0.jar      MySQL JDBC driver (committed)
      javafx/                          JavaFX jars - downloaded by setup, NOT committed
    setup.bat / setup.sh               checks every dependency, says what to install and where;
                                       downloads JavaFX; sets up the MySQL database
    setup-database.ps1 / .sh           helper used by setup for the database step
    run-demo.bat / run-demo.sh         setup + compile + run (Windows / macOS-Linux)
    CONTRIBUTING.md                    this file
```

**Where does my code go?** In `src/<your module>/`, with the same shape as
`auth/` and `pos/`: `contract/` for interfaces other modules use, then one
folder per job (e.g. `inventory/stock/`, `inventory/ui/`). Code every module
needs goes in `shared/`. Packages match the folders: a file in
`src/inventory/stock/` starts with `package inventory.stock;`. Self-tests
and stand-ins go in `test/<your module>/` and are added to `--scripted`.

`out/` folders are build output — never edit or commit them; the run scripts
wipe and rebuild `nickel-sales/out/` every time.

Two rules keep this architecture healthy:

- **Nothing in `contract/` may be changed unilaterally.** Those files are
  agreements between roles. If a contract must change, the change is agreed
  first, then `contract/` and the `auth/` package are updated together.
- **Nothing in the `auth/` package may be reimplemented elsewhere.** Login
  logic exists exactly once.

### 1.5 Agreed deviations from the class diagram

These were accepted to make the design workable; confirm them with the team
before building on top:

1. **Login with a login name, not the User ID.** `Users.UserLoginName`
   (e.g. `JCubillan`) is what people type. When an admin creates a user it
   is generated as first initial + last name (`KChong`, then `KChong2` if
   taken) and never changes on edit, so renaming someone does not break
   their login. `UserName`/`UserLastname` are the person's actual name;
   `UserID` (`NSC-00001`) is the internal key.
2. **`login()` returns `LoginResult`** (user + session) instead of a bare
   `User`, so the login screen receives the sessionId it needs for
   `getCurrentUser()` and `logout()`.
3. **`logout`/`getCurrentUser` take the sessionId** (the diagram says
   userId) — sessions, not users, are what expire, and one user could
   theoretically be logged in on more than one terminal.

---

## 2. How to run and test it right now

The real app starts at `src/app/Main.java`. Until Product & Inventory is
connected, `run-demo.bat` starts `test/app/DevApp.java` instead: the same
app, with the test products from `StubInventory` so POS can be tried.

| Command (from `nickel-sales/`) | What it does |
| ------------------------------ | ------------ |
| `run-demo.bat` / `./run-demo.sh` (default, double-click) | **The app** (`DevApp`): login → home screen with the buttons your role may use → module screens → Log out. Users and sales are in **MySQL**; products are the test ones |
| `run-demo.bat --console` / `./run-demo.sh --console` | Console demo (`AuthDemo`) with in-memory **stub** users — no database needed: runs the scenarios, then an interactive login prompt |
| `run-demo.bat --scripted` / `./run-demo.sh --scripted` | **All self-tests** (below); exit code 1 if anything fails |

Every run recompiles all of `src/` and `test/` from scratch, so you always
run your latest code.

### Compiling by hand

The scripts are the recommended way. If you want the raw commands, run them
from `nickel-sales/` after setup has created `lib/javafx/`:

Windows (cmd):

```bat
dir /s /b src\*.java test\*.java > out\sources.txt
javac -encoding UTF-8 --module-path lib\javafx --add-modules javafx.controls -cp lib\jbcrypt-0.4.jar -d out @out\sources.txt
java --module-path lib\javafx --add-modules javafx.controls -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" app.DevApp
```

(Create `out\` first with `mkdir out`. If the project path contains spaces,
use `run-demo.bat` instead — it quotes the paths in the source list.)

macOS / Linux:

```bash
mkdir -p out
javac -encoding UTF-8 --module-path lib/javafx --add-modules javafx.controls \
      -cp lib/jbcrypt-0.4.jar -d out $(find src test -name "*.java")
java --module-path lib/javafx --add-modules javafx.controls \
     -cp "out:lib/jbcrypt-0.4.jar:lib/mysql-connector-j-9.4.0.jar" app.DevApp
```

Note the classpath separator: `;` on Windows, `:` on macOS/Linux. JavaFX is
needed to compile because the screens use it; the MySQL jar is needed
at run time (replace `app.DevApp` with `auth.AuthDemo` for the console demo).
The self-tests are run with the JavaFX jars on the classpath instead
(`-cp "out;...;lib\javafx\*"`), because `AppCheck` loads the screen classes.

### All self-tests: `--scripted`

One command builds the project and runs **every** self-test, with the same
JDK that compiled it (so an older `java` on your PATH can't get in the way):

```bat
run-demo.bat --scripted        (macOS/Linux: ./run-demo.sh --scripted)
```

| Self-test | Checks | Expected |
| --- | --- | --- |
| `AuthDemo` | login scenarios | `RESULT: 8 passed, 0 failed` |
| `AuthzDemo` | role → permission matrix | `RESULTS: 10 tests passed, 0 tests failed.` |
| `UserAdminDemo` | user management rules | `RESULT: 8 passed, 0 failed` |
| `PosContractCheck` | money/quantity/time rules, POS contracts, stand-ins, POS tables, transactions | `RESULT: 15 passed, 0 failed` |
| `CartCheck` | cart and pricing: wholesale switch, stock / inactive blocking, Pay greyed out on stock problems, VAT totals | `RESULT: 16 passed, 0 failed` |
| `CheckoutCheck` | payments, invoice numbers, checkout rollback, reading sales back, Sync export | `RESULT: 14 passed, 0 failed` |
| `ReceiptCheck` | receipt amounts read back and compared with the saved sale; widths; reprint from MySQL | `RESULT: 7 passed, 0 failed` |
| `AppCheck` | settings, module list, home-screen buttons per role, shared services | `RESULT: 9 passed, 0 failed` |

It ends with **ALL CHECKS PASSED** or **CHECKS FAILED** plus the names of
the ones that failed (exit code 1). `PosContractCheck`, `CheckoutCheck` and
`ReceiptCheck` use MySQL; without it, their database checks (3, 10 and 1)
are skipped, not failed. `CheckoutCheck` and `ReceiptCheck` use a throw-away
terminal ID and delete the sales they made.

Don't run the self-tests by typing `java ...` yourself: if your PATH has an
older Java, you get `UnsupportedClassVersionError`.

### Demo accounts

Password `Password123` for all — test seeds only. The console demos use
built-in copies of these users; the login screen uses the rows inserted by
`database/NickelSales-Database-Config.sql` (the same accounts).

| Log in as   | User ID   | Name               | Roles | Status   | Lands on                  |
| ----------- | --------- | ------------------ | ----- | -------- | ------------------------- |
| `JCubillan` | NSC-00001 | Jerick Cubillan    | A;C;I | Active   | Home: all 5 buttons       |
| `JDelaCruz` | NSC-00002 | Juan Dela Cruz     | C     | Active   | Home: Point of Sale       |
| `MSantaAna` | NSC-00003 | Maria Santa Ana    | I     | Active   | Home: Products, Reports   |
| `RSantos`   | NSC-00004 | Rhian Santos       | C     | Inactive | — (blocked at login)      |
| `KRodriguez`| NSC-00005 | Kimberly Rodriguez | C     | Locked   | — (blocked at login)      |

The `AuthDemo` scenarios cover: valid login per role (login names are
case-insensitive), wrong password, unknown username and User IDs rejected
(identical error), inactive and locked accounts with correct passwords,
boolean validation, and the session lifecycle.

`test/` holds the self-tests and stand-ins, and `DevApp` (the app with test
products) until the real inventory is connected.

---

## 3. Roles and what each one must deliver

Sprint 1 was four work packages; user account management followed. Each lists the exact signatures
to implement, implementation notes, and the definition of done. A programmer
can write the code themselves, or hand the matching subsection to an AI
assistant to produce it.

| Role | Status |
| ---- | ------ |
| `AUTH` | **Done** |
| `SESSION/UI` | **Done** (session manager + login screen) |
| `DATA` | **Done** — `MySqlUserRepository` in `src/auth/storage/`, `Database` in `src/shared/` |
| `AUTHZ` | **Done** (permission matrix + role routing; placeholder dashboards) |
| User accounts | **Done** — add / edit / activate users, change password (section 3.5) |

### 3.1 Role `DATA` — user storage and retrieval (DONE)

`auth.storage.MySqlUserRepository` implements
`auth.contract.UserRepository`:

```java
User findById(String userId);          // null when not found
User findByUsername(String username);  // the login lookup, on UserLoginName
void save(User user);                  // insert; fail if the ID already exists
void update(User user);                // fail if the ID does not exist
void updatePassword(String userId, String newPasswordHash);
List<User> findAll();
```

Implementation notes:

- Backend: the `Users` table of database `NickelSales_Database`
  (`database/NickelSales-Database-Config.sql` at the repository root): `UserID` PK varchar(10),
  `UserLoginName` varchar(30) unique, `UserPassword` varchar(60), `UserName`,
  `UserLastname`, `UserRole` varchar(5), `UserStatus` varchar(8), plus
  `CreatedAt` / `UpdatedAt` timestamps. Use JDBC prepared statements
  (`mysql-connector-j` — see section 5, "Adding a dependency"); never
  concatenate SQL strings.
- `findByUsername` is a case-insensitive, whitespace-trimmed lookup on
  `UserLoginName`, returning `null` (not an exception) when not found.
- Build `User` objects with the ready-made converters — do not hand-parse
  the encodings: `UserRole.parseDbRoles("A;C;I")` /
  `UserRole.toDbCodes(...)`, `UserStatus.fromDbString("Active")` /
  `toDbString()`.
- **Passwords are bcrypt hashes** (`$2a$10$...`, 60 characters), never
  plaintext. The SQL seed stores the hash of `Password123`. Generate hashes
  with jBCrypt (`BCrypt.hashpw(pw, BCrypt.gensalt(10))`) — `$2b$`/`$2y$`
  hashes from other tools are rejected by jBCrypt 0.4.
- Definition of done (met): users can be saved/retrieved locally; the role
  seed accounts exist; plaintext is never stored.

### 3.2 Role `SESSION/UI` — sessions and the login screen (DONE)

Implemented:

```
src/auth/
  session/InMemorySessionManager.java   implements contract.SessionManager
  ui/LoginScreen.java                   JavaFX login form
  ui/LogoutAction.java                  logout handler + "Log out" button
```

The contract it fulfils:

```java
Session createSession(String userId);
Session getSession(String sessionId);        // null when unknown/expired
void invalidateSession(String sessionId);
boolean isSessionValid(String sessionId);
```

**Session rules**

- Session ids are random UUIDs, kept in memory (lost when the app closes).
  Persisting sessions is optional and can come later — the interface does
  not care.
- A session expires after 30 minutes without activity. Pass a `Duration` to
  the constructor to change it (tests use a few hundred milliseconds).
- **Idle logout (screen side):** `DashboardRouter` + `IdleLogoutTimer` log
  the user out after `NSC_IDLE_TIMEOUT_MINUTES` (default 30) without any
  key, mouse or touch activity, close any open dialogs, and show "Your
  session expired. Please log in again." While the user is active, the
  router keeps the session above alive. Keep the idle timeout **≤** the
  session timeout.
- `getSession(id)` counts as activity and resets the timer, so
  `AuthenticationService.getCurrentUser(sessionId)` keeps a session alive.
  `isSessionValid(id)` only checks and never resets the timer.
- Unknown, expired, and logged-out ids return `null`/`false`; they never throw.

**Wiring**

```java
SessionManager sessions = new InMemorySessionManager();
AuthenticationService auth = new AuthenticationService(repo, sessions, null, audit);

LoginScreen login = new LoginScreen(auth, result -> router.openDashboardFor(result));
LogoutAction logout = new LogoutAction(auth, () -> { login.reset(); router.showLogin(); });
```

- `LoginScreen` (fields labelled "Username" and "Password"; the username is
  the `UserLoginName`) does NOT pick the dashboard. It hands the `LoginResult` to the callback; role
  routing is the `AUTHZ` role's job (`result.user().getRoles()`).
- Keep `result.session().getSessionId()` after login. Each dashboard passes
  it to `logout.createButton(sessionId)` and to `auth.getCurrentUser(sessionId)`.
- A screen that gets `null` from `getCurrentUser(sessionId)` must send the
  user back to the login screen.
- The logo is optional: put it at `nickel-sales/resources/images/nickel-logo.png`
  (the run scripts copy `resources/` onto the classpath, where `LoginScreen`
  looks for `/images/nickel-logo.png`). Without it, the screen simply has no logo.

**Messages the login screen shows**

| Situation | Message |
| --------- | ------- |
| Empty username or password | "Enter your username and password." (no call to `auth.login`) |
| Any `AuthenticationException` | the exception's own `getMessage()` |
| Login worked but `session()` is null | "...no session could be started. Contact the system administrator." |
| Any other runtime error | "Something went wrong while logging in. Try again, or contact the system administrator." |

The password field is cleared after every failure. It has a **SHOW/HIDE**
toggle (reset to hidden on logout) and shows **"Caps Lock is on"** while it
has the cursor and Caps Lock is on.

Definition of done (met): username/password can be entered and submitted;
valid login succeeds and the session stays active; invalid login shows the
right message; a logout action calls `auth.logout(sessionId)` and returns to
the login screen.

### 3.3 Role `AUTHZ` — authorization and role routing

Implement `AuthorizationService` on top of the shared vocabulary — never
re-parse the DB role codes:

```java
user.getRoles()                 // Set<UserRole>, multi-role users are NORMAL
user.hasRole(UserRole.ADMIN)
```

Suggested shape (matches the class diagram): `checkAccess(User, Permission)`
and `hasPermission(Set<UserRole>, Permission)`, with a `Permission` enum
(PROCESS_SALE, MANAGE_PRODUCTS, UPDATE_INVENTORY, VIEW_REPORTS,
MANAGE_USERS, MANAGE_SYSTEM) and a role→permission matrix. Multi-role users
(e.g. `A;C;I`) get the union of their roles' permissions; a user holding
ADMIN reaches the admin dashboard.

Definition of done: correct role permissions returned; each role reaches the
correct (placeholder) screen; access outside a role's permissions is denied.

**Implemented (Sprint 1).** Decisions made:

- (Updated in POS Phase 3.) Everyone lands on one **home screen** with a
  button for each module whose permission they have (`Modules.visibleFor`):
  Cashier → Point of Sale; Inventory staff → Products & inventory, Reports;
  Admin → everything. A user with nothing to open (inactive, locked, no
  role) is logged out with a message.
- `DashboardRouter.showHome` re-reads the user (`getCurrentUser`) every
  time, and checks the permission again when a button is pressed, so role
  changes made during a session take effect on the next screen.
- Buttons for things a role can't do are not shown (e.g. **Manage users**
  only with `MANAGE_USERS`), **and** the service behind them checks the
  permission again. Follow both rules for every new screen.
- `checkAccess` denies null and non-ACTIVE users (fail closed).
- **Open question for the team:** should inventory staff's `VIEW_REPORTS`
  be limited to inventory reports? (Split the permission, or filter by role
  in the reports screen.)

### 3.4 Role `AUTH` — this module

Already implemented and verified. Remaining responsibilities going forward:
only changes that arise from agreed contract changes, plus the Sprint 2+
extensions in section 4 that live inside this package.

### 3.5 User account management (DONE)

Built on the contracts — no changes to the `auth/` package.

- **`UserAdminService`** (`auth/account/`) — every method takes the caller's
  sessionId and requires `MANAGE_USERS`:
  `listUsers`, `createUser` (generates the `NSC-#####` id and the login name,
  stores a bcrypt hash of the temporary password), `updateUser` (name and
  roles; the login name never changes), `setActive` (activating also
  unlocks).
  Rules it enforces: at least one active administrator must always remain;
  you cannot deactivate your own account; **you cannot remove your own Admin
  role** (another admin must do it).
- **`UserAccountService.changePassword`** — checks the current password,
  that the new one matches its confirmation and passes `PassPolicy`, then
  stores the new hash.
- **`PassPolicy`** — 8–72 characters (bcrypt only uses 72 bytes), at least
  one uppercase letter and one number.
- **Screens** — `UserManagementScreen` (reloads after every change and
  keeps the selection; if the user loses access it explains and returns to
  their dashboard), `UserFormDialog` (Add / Edit), `ChangePasswordDialog`.
- Self-test: `UserAdminDemo` (section 2).

### 3.6 POS & Sales (IN PROGRESS)

POS talks to the other modules ONLY through `src/pos/contract/`.
Each interface says who implements it and who calls it.

| Contract | Implemented by | Called by | Purpose |
| --- | --- | --- | --- |
| `ProductLookup` → `ProductInfo` | Product & Inventory | POS | find by barcode / ID, search by name; retail and wholesale price, VAT-exempt flag, active, stock |
| `StockRecorder` | Product & Inventory | POS | stock-out of a completed sale, **inside the sale's transaction**; throws `InsufficientStockException` if short |
| `SaleExport` → `SaleRecord` | POS | Sync | completed sales not yet synced; mark them synced |
| `SaleHistory` → `SaleRecord` | POS | Transaction & Reports, receipt reprint | read completed sales by ID, invoice number or date range |

`ProductLookup`, `StockRecorder` and `SaleRecord` are agreements with other
modules: don't change them without agreeing it first (section 1.4).
`SaleExport` and `SaleHistory` are implemented in POS Phase 2; until then,
other modules can code against the interfaces and build `SaleRecord`s by hand
in their own tests.

Agreed rules:

- **Money** is `shared.Money` (`BigDecimal`, 2 decimals, half-up,
  `DECIMAL(12,2)`); **quantities** are `Quantity` (3 decimals, `DECIMAL(12,3)`).
  Never `double`.
- **Prices are BEFORE VAT; 12% VAT is added** on VATable items (changed
  from VAT-inclusive in Oct 2026). Product & Inventory: `ProductInfo`'s retail
  and wholesale prices are before VAT. A sale's line totals add up to the
  subtotal; VATable sales = the VATable lines, VAT = 12% of that (rounded
  once per sale), total = subtotal + VAT = what the customer pays. A sale
  stores the VATable amount, VAT-exempt amount, VAT and total (formulas in
  `SaleRecord`). **Reports:** `total` includes VAT; use `vatableSales +
  vatExemptSales` for sales before VAT. Sales saved under the old rule (VAT
  inside prices) still load and reprint as they were sold.
- **Wholesale:** a sale line of **6 or more** of the same product is charged
  that product's wholesale price; otherwise retail. No wholesale price →
  always retail.
- **Out of stock blocks the sale:** checked when adding to the cart and again
  in `StockRecorder` when the sale completes.
- **Payment methods:** `CASH`, `GCASH`, `MAYA`, `QRPH`. Cash may be more
  than the total (change is given); GCash / Maya / QRPh are not processed
  by the system — the cashier types the **reference number**, which is
  required, and there is no change.
- **Records check themselves.** `SaleRecord`, `SaleLineRecord` and
  `PaymentRecord` refuse to be built if the numbers don't add up (line total
  = quantity × unit price; VAT totals add up to the total; payments cover
  exactly the total). A `SaleRecord` you are handed is always consistent.
- **Completing a sale is one transaction:** `Database.inTransaction(tx -> ...)`.
  Anything joining it uses the given `tx` and never commits, rolls back or
  closes it.
- **A completed sale never changes.** Lines store copies of the name and
  price charged. Only completed sales are stored.
- **Invoice numbers:** `T01-2026-000123` — terminal, year (Philippine time),
  6-digit number restarting each year.
- **Times** are UTC (`Instant`, `DATETIME(3)`), shown in Philippine time.
  Use `shared.PhTime` for this — e.g. a daily report covers
  `PhTime.startOf(day)` to `PhTime.startOf(day.plusDays(1))`.

Tables (POS-owned, in the SQL script): `Sale`, `SaleLine`, `Payment`,
`InvoiceSequence`. Until the real Product & Inventory module exists, POS is
developed against `test/.../pos/stub/StubInventory`, which has sample
products for every case (wholesale, no wholesale price, VAT-exempt, sold by
kg, out of stock, inactive, no barcode).

#### Ringing up a sale (Phase 1: `pos.cart`)

`SalesService` is the only way to change a sale; screens read the `Sale` it
returns. One per terminal:

```java
SalesService pos = new SalesService(productLookup, "T01");   // terminal ID
pos.startSale(cashierUserId);
pos.addItem("4800000000026");                 // one scan = 1
pos.addItem("4800000000026", Quantity.of("12"));   // typed quantity, then scan
pos.addProduct("P-0007");                     // picked from search (no barcode)
pos.updateQuantity("P-0004", Quantity.of("1.5"));
pos.removeItem("P-0002");
SaleTotals t = pos.getTotals();               // total, vatableSales, vatAmount, vatExemptSales
pos.cancelSale();                             // nothing saved, no stock touched
```

- `addItem` / `addProduct` / `updateQuantity` throw **`CartException`** (unknown
  barcode, no longer sold, bad quantity) or **`InsufficientStockException`**;
  both messages are ready to show as-is. Either way the sale is unchanged.
- Database problems from the product lookup come out as `RuntimeException` —
  show them with `shared.UiErrors.message(e, ...)`.
- Stock is checked when a line is added or raised. Lowering a quantity is
  always allowed. Stock can drop while a sale is open (the other terminal
  sold some), so after every change the screen calls
  `pos.findStockProblems()` and shows each problem's `message()` next to its
  line, and enables **Pay only when `pos.canCheckout()`** is true. The cashier
  fixes a problem by lowering the quantity to what is left, or removing the
  line. Checkout checks stock once more when the sale is saved.
- The wholesale threshold is `ThresholdPricingRule.WHOLESALE_MIN_QTY` (6) —
  change it there and nowhere else.

#### Paying and saving (Phase 2: `pos.checkout`)

```java
// once, at start-up (Phase 3 moves this into the app's wiring)
MySqlSaleRepository sales = new MySqlSaleRepository();      // also SaleHistory + SaleExport
CheckoutService checkout = new CheckoutService(sales, new InvoiceNumberGenerator(), stockRecorder, auditLogger);
SalesService pos = new SalesService(productLookup, new PricingService(), "T01", checkout);
PaymentService payments = new PaymentService();

// when the cashier presses Pay (only enabled while pos.canCheckout())
PaymentRecord p = payments.recordPayment(sale, PaymentMethod.CASH, cashReceived, null);
//            or payments.recordPayment(sale, PaymentMethod.GCASH, null, referenceNo);
SaleRecord done = pos.finalizeSale(p);        // done.invoiceNo() = "T01-2026-000001"
```

- `recordPayment` throws **`CheckoutException`** with a message to show
  (not enough cash, missing / bad reference number). It saves nothing, so
  the cashier can change the method or amount and try again.
- `finalizeSale` saves the sale in **one transaction**: next invoice number,
  `Sale` + `SaleLine` + `Payment`, then `StockRecorder.recordSale`. If any of
  it fails — `InsufficientStockException`, `CartException` (a stock problem),
  `CheckoutException` (total changed after payment) or a database error —
  **nothing is saved, no stock changes, the invoice number is not used**, and
  the sale stays open as it was so the cashier can fix it and press Pay again.
- After the commit the sale is logged through `AuditLogger` as
  `SALE_COMPLETED T01-2026-000001` (by the cashier's user ID).
- Invoice numbers come from `InvoiceSequence`, one row per terminal per PH
  year, locked while a sale is saved: no repeats even with two sales at
  once, and no gaps because a rolled-back sale gives its number back.
- Terminal IDs are 1-8 letters, digits or dashes (e.g. `T01`).
- **Sync:** use `MySqlSaleRepository` as your `SaleExport`. **Reports:** use
  it as your `SaleHistory` (both read-only).

#### The cashier's screen (Phase 4: `pos.ui`)

Everything works without the mouse; a barcode scanner types the code and
presses Enter.

| Key | Does |
| --- | --- |
| Enter (barcode box) | add the item (scan again = one more) |
| F2 | quantity box: type 12 or 1.5, Enter, then scan |
| F3 | search by name (products without a barcode) |
| ↑ / ↓ | move in the cart |
| F4 or Enter (cart) | change the selected line's quantity |
| + / − (cart) | one more / one less |
| Delete (cart) | remove the line |
| F7 | reprint a receipt by invoice number (the last one is filled in) |
| F9 | cancel the sale (asks first) |
| F12 | pay — greyed out while the cart is empty or a line is red |
| Esc | back to the home screen; the sale stays open for the same cashier |

In the payment window: type the cash received (the change shows as you
type; F8 = exact amount), or F5 to switch to GCash / Maya / QRPh and type the
reference number; Enter completes the sale. Lines with a stock problem turn
red with "Only N left" / "No longer sold", and the full message is shown
above Pay. Messages from the services are shown as they are; database
problems go through `shared.UiErrors`.

#### Receipts (Phase 5: `pos.receipt`)

After Pay, the cashier sees the change and the receipt; **Enter prints it**
on the default printer, **Ctrl+P** picks another printer, **Esc** closes
without printing. **F7** reprints any past sale by invoice number (marked
`*** REPRINT ***`).

- The receipt is built from the **saved** `SaleRecord` (`ReceiptBuilder`),
  and laid out once as fixed-width text (`Receipt.toText(width)`). The
  preview and the printer both draw exactly those lines, so the paper always
  matches the screen and the saved sale. `ReceiptCheck` reads the amounts
  back out of the text and compares them with the sale.
- Contents: store details, invoice no., date/time (PH), terminal, cashier,
  each line (W = wholesale, E = VAT-exempt), total, VATable / VAT / VAT-exempt,
  cash and change (or e-wallet and reference no.), "THIS IS NOT AN OFFICIAL
  RECEIPT" (the system is not BIR-accredited).
- Printing goes through JavaFX/Windows printing, so any installed printer
  works. Text is sized to the paper's width (max 11 pt; on A4 it stays
  receipt-sized) and continues on another page if the paper is short.
- Set the store details and paper width per terminal with the `NSC_STORE_*`
  and `NSC_RECEIPT_WIDTH` variables (section 0).
- Another kind of printer (e.g. direct ESC/POS) can be added by implementing
  `PrinterPort` and returning it from `AppContext.receiptPrinter()`.

#### POS and the class diagram

POS follows the POS & Sales part of the class diagram. Where the code
differs, it is on purpose:

| Diagram | Code | Why |
| --- | --- | --- |
| `Product` (POS view) | `ProductInfo` | adds unit, VAT-exempt flag, active flag and stock, which POS needs to price and block sales |
| `ProductLookup.findByBarcode` | same, plus `findById` and `search` | products without a barcode must still be sellable (use case, step 3) |
| `StockUpdater.recordSale(saleId, items)` | `StockRecorder.recordSale(sale, tx)` | gets the whole sale and the transaction, so the sale and the stock-out are saved together or not at all |
| `Sale`, `SaleLineItem`, `SaleStatus` | same names, in `pos.cart` | the sale being rung up. When it is paid it becomes a `SaleRecord` / `SaleLineRecord` / `PaymentRecord` (the saved form other modules read) |
| `SalesService.addItem(barcode)` | same, plus `addItem(barcode, qty)` and `addProduct(productId)` | typed quantities for wholesale, and products without a barcode |
| `SalesService` keeps one line per product | same | scanning the same item again adds to its line, so the 6-or-more rule sees the whole quantity |
| `quantity: int` | `BigDecimal` (`Quantity`) | items sold by kg or metre |
| `Payment.amountDue` | `PaymentRecord.amountApplied` | same value; the list allows split payments later |
| `SaleRepository.findById` | `SaleHistory` (read) + saving inside POS | other modules only read sales; only POS writes them |
| `PricingService`, `PricingRule`, `ThresholdPricingRule` (threshold 6) | same names, in `pos.cart` | `PricingRule` takes decimal quantities |
| `SalesService.finalizeSale(payment): Receipt` | `finalizeSale(payment): SaleRecord` | the receipt is built from the saved sale in Phase 5 (`ReceiptBuilder.build(sale)`) |
| `PaymentService.recordPayment(sale, method, tendered, refNo)`, `computeChange`, `validateReference` | same, in `pos.checkout` | |
| `InvoiceNumberGenerator.nextInvoiceNo(terminalId)` | `nextInvoiceNo(tx, terminalId, completedAt)` | takes the sale's transaction (so a failed sale gives the number back) and the time (for the PH year) |
| `SaleRepository` (save, findById) | same, in `pos.checkout`; extends `SaleHistory` | `save` joins the checkout transaction |
| `ReceiptBuilder.build(sale): Receipt` | same, in `pos.receipt` (+ `buildReprint`) | built from the saved `SaleRecord` |
| `Receipt` (invoiceNo, lines, total, paymentMethod, change, issuedAt) | `Receipt(store, sale, cashierName, reprint)` + `toText(width)` | carries the whole saved sale, so every figure on the receipt comes from it |
| `PrinterPort.printReceipt(receipt)` | same; `JavaFxReceiptPrinter` implements it | |
| `POSScreen` (onBarcodeScanned, onQuantityChanged, onPaymentSelected, onConfirmSale, showCart, showError) | `PosScreen` + `PaymentDialog`, in `pos.ui` | same jobs; payment has its own window so it can be done with the keyboard |
| (not in the diagram) `AppModule`, `Modules`, `AppContext` | `src/app/` (Phase 3) | how each module's screen gets a home-screen button and the shared services |

---

## 4. Next sprints — how they plug in

Everything below builds on what already exists. Note how little of it
requires touching `AuthenticationService` — that is the point of the
contract design.

User account management, planned here originally, is done — see 3.5.

**Failed-login lockout.** When the business wants it: count consecutive
`LOGIN_FAILED` audit events per user; after N, set `UserStatus.LOCKED`
through the data layer. `AuthenticationService` then rejects those accounts
via the existing `LOCKED_USER` path with no code change.

**Audit log storage.** Swap the console `AuditLogger` implementation for a
database-backed one (table: event, userId, timestamp). The interface is one
method, so this is a drop-in replacement at the wiring spot.

**User synchronization across terminals** (from the system's sync design):
the sync layer upserts users through `UserRepository.save/update`, using
the same `User`/`UserRole`/`UserStatus` converters — the encodings are
already normalized in one place.

**Remember-the-logged-in-user screens.** Every screen takes the
`sessionId`, calls `getCurrentUser(sessionId)` when it opens, and redirects
to the login screen when it returns `null`. That single pattern is all the
"session handling" each dashboard needs.

---


## 5. Putting it all together: the app (`src/app/`)

`app.Main` starts the real app: it reads the settings (`AppConfig`), builds
the shared services once (`AppContext.create(...)`), and opens the window
(`NickelSalesApp`). After login, `DashboardRouter` shows the **home screen**:
one button per module the user's role may use, from the list in
`Modules.all()`.

### Adding your module's screen (Inventory, Reports, Sync, ...)

1. Write a class in **your** package that implements `app.AppModule`:

   ```java
   package inventory.ui;

   public final class InventoryModule implements AppModule {
       public String title()           { return "Products & inventory"; }
       public String description()     { return "Products, prices, stock in/out, barcodes"; }
       public Permission permission()  { return Permission.UPDATE_INVENTORY; }
       public Parent open(ScreenContext screen) {
           // screen.app()       shared services (auth, saleHistory(), saleExport(), find(...))
           // screen.sessionId() pass to services that check who is asking
           // screen.user()      who is logged in
           // screen.stage()     owner window for dialogs
           Button back = new Button("Back to home");
           back.setOnAction(e -> screen.goHome());
           return new VBox(back /* , your screen ... */);
       }
   }
   ```

2. In `app/Modules.java`, replace your module's `ComingSoonModule` line
   with `new InventoryModule()`. That is the only shared file you edit;
   the home screen, router and other modules don't change.
3. Services your screens share: create them in `Main` and
   `app.register(YourService.class, service)`; read them with
   `screen.app().find(YourService.class)`.
4. **Product & Inventory:** in `Main`, pass your `ProductLookup` and
   `StockRecorder` to `AppContext.create(config, products, stock)` — POS
   then sells from the real inventory (POS Phase 6).
5. **Sync:** read sales from `app.saleExport()`. **Reports:**
   `app.saleHistory()`.

The home screen hides buttons the user may not use, the router checks the
permission again when the button is pressed, and your services should check
it too (`authz.checkAccess(user, ...)`). Number keys 1-9 open the buttons
in list order.

### Adding a dependency

Dependencies are plain jars, no build tool:

- **Small, platform-independent jars** (like `jbcrypt`, or `mysql-connector-j`
  for `DATA`): commit them to `nickel-sales/lib/`, add them to the `-cp` in
  `run-demo.bat` **and** `run-demo.sh`, and add an IntelliJ library for them
  (File → Project Structure → Libraries → +).
- **Large or platform-specific jars** (like JavaFX): download them in
  `setup.bat` / `run-demo.sh` instead, and add their folder to `.gitignore`.

Update the table in section 0 whenever you add one.

When the team adopts a shared build tool (Maven/Gradle), the equivalent
coordinates are `org.mindrot:jbcrypt:0.4` and `org.openjfx:javafx-controls:21.0.5`.

---

## 6. Making changes

### 6.1 Branches and pull requests

- `main` is what everyone builds on. **Don't push straight to `main`.**
- Work on a branch named after the area and the change, e.g.
  `ui/idle-logout`, `data/product-repository`, `fix/login-refresh`
  (existing examples: `Session/UI`, `authorization`, `qa/role-session-tests`).
- Open a pull request into `main` and get at least one teammate to review
  it. Pull `main` into your branch first if it has moved on.

### 6.2 Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):
`type(scope): what changed`, in the imperative, under ~72 characters.

| Type | For |
| ---- | --- |
| `feat` | a new feature (`feat(login): add show-password toggle`) |
| `fix` | a bug fix (`fix(admin): return to dashboard after losing access`) |
| `test` | demos / self-tests only |
| `docs` | README, CONTRIBUTING, comments |
| `refactor` | code changes that don't change behaviour |
| `chore` | build scripts, `.gitignore`, IDE settings |

Add a body when the *why* isn't obvious. If teammates must do something
after pulling (rebuild the database, set a new variable), add a
`BREAKING CHANGE:` footer saying exactly what.

### 6.3 Before you push

1. `run-demo.bat --scripted` → **ALL CHECKS PASSED** (section 2)
2. Double-click `run-demo.bat` and log in with at least one demo account.
3. If you changed behaviour that a demo checks (e.g. how login works),
   update the demo **and** this file in the same pull request.

### 6.4 Changing the database

The script is **safe to run again**: `CREATE TABLE IF NOT EXISTS` and
`INSERT IGNORE` only add what is missing. `setup.bat` / `setup.sh` re-run it
on every existing database, so:

- **A new table** (e.g. the POS tables, or Product & Inventory's): teammates
  just run setup after pulling. Nothing is lost.
- **A new column or index on an existing table**: add it to the
  `CREATE TABLE` *and* to the "UPGRADES" section at the end of the script
  (copy one of the blocks there). It is only added when missing, so
  teammates just run setup after pulling. Nothing is lost.
- **A renamed or changed column** (like `UserLoginName`): the script cannot
  change an existing table, so everyone with an older database must rebuild
  it:

1. MySQL Workbench: `DROP DATABASE NickelSales_Database;`
   (this deletes the data — note any users you added yourself)
2. Run `setup.bat` (or `./setup.sh`) and answer **Y** to "Create it now?".

When you change the schema: update the SQL file, the code that reads it, the
stand-ins in `test/`, and this file. Add new tables with `CREATE TABLE IF NOT
EXISTS` and seed rows with `INSERT IGNORE`. If an existing table changed,
mention the rebuild in the commit (`BREAKING CHANGE:`).

### 6.5 Code style

- 4 spaces for indentation, no tabs.
- User-facing messages come from the services (`AccountException`,
  `AuthenticationException` messages) — screens just display them.
- SQL only through `PreparedStatement` with `?` parameters, never by joining
  strings.
- Never put a real password, or anything secret, in code or in the repo.

---

## 7. Troubleshooting

| Symptom | Cause / fix |
| ------- | ----------- |
| `run-demo.bat` window flashes and closes | Run it from a terminal (`cd nickel-sales` then `run-demo.bat`) to read the error. The script now pauses on errors, so this should be rare. |
| `No JDK found` / `javac is not recognized` | Run `setup.bat` and let it install Temurin 21, or install JDK 17+ yourself and set `JAVA_HOME`. Close and reopen the terminal afterwards. |
| `Module javafx.controls not found` | `lib/javafx/` is missing or empty. Run `setup.bat` (or `./run-demo.sh --setup`). |
| `JavaFX runtime components are missing` (IntelliJ) | You ran `DevApp` or `Main` without VM options. Use the **Nickel Sales (DevApp)** run configuration. |
| `package javafx... does not exist` in IntelliJ | `lib/javafx/` didn't exist when IntelliJ loaded the project. Run setup, then **File → Reload All from Disk** (or reopen the project). |
| JavaFX download fails | No internet, or a proxy/firewall blocks `repo1.maven.org`. Download the three `javafx-*-21.0.5-win.jar` files named in the error into `nickel-sales/lib/javafx/` by hand. |
| `Two versions of module javafx.base found` | Old jars in `lib/javafx/`. The scripts remove other versions automatically; if it persists, delete the folder and rerun setup. |
| Changes don't show up when running | You're running stale classes. The scripts rebuild `out/` each run; in IntelliJ use **Build → Rebuild Project**. |
| `./run-demo.sh: Permission denied` | `chmod +x run-demo.sh` once. |
| Login screen says "Something went wrong while logging in" for every account | The app can't reach MySQL. Double-click `setup.bat`: it checks that MySQL is running, that the database exists, and that your saved password works. The full error is also printed in the `run-demo.bat` window. |
| Errors after pulling (e.g. "A database error occurred", or `Unknown column` / `Table ... doesn't exist` in the console) | Run `setup.bat` first — it adds new tables. If it still fails, a table changed shape: rebuild the database (section 6.4). |
| "Invalid username or password." with `NSC-00001` | Log in with the login name (`JCubillan`), not the User ID. |
| "Your session expired. Please log in again." | Idle logout after `NSC_IDLE_TIMEOUT_MINUTES` (default 30) without activity. Log in again. |
| `No suitable driver found for jdbc:mysql` | `lib/mysql-connector-j-9.4.0.jar` is missing from the classpath. Use `run-demo.bat` / the IntelliJ run configs, or add the jar to your `-cp`. |
