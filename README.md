# NSC-SWENG

POS and Inventory Management System for **Nickel Sales Center** — a desktop
app (Java + JavaFX) that runs offline against a local MySQL database.

This page gets you from nothing to a working app. No prior Java
experience needed. It takes about 20–30 minutes the first time, mostly
waiting for installers.

> **Installing it at the store?** You don't need any of this page. Give the store
> `NickelSalesSetup-<version>.exe` (one file with the app, Java and MySQL inside) and run it on
> their PC. How to build it and how it works: [installer/README.md](installer/README.md).
>
> **On a Mac or Linux?** Skip to [macOS / Linux](#macos--linux).
> **Writing code for the project?** After this page, read
> [docs/development/](docs/development/ARCHITECTURE.md) (architecture, module template, naming,
> contributing) and [nickel-sales/CONTRIBUTING.md](nickel-sales/CONTRIBUTING.md) (setup and tests).

---

## What you'll end up with

Double-clicking `run-demo.bat` opens the **Nickel Sales Center login
screen**. You log in with a username and password and land on a home screen
with a button for each part of the system your role may use:

- **Point of Sale** (cashiers and admins): ring up sales with a barcode
  scanner or the keyboard, retail / wholesale prices, 12% VAT, cash or
  GCash / Maya / QRPh, and a printed receipt. See
  [Ringing up a sale](#ringing-up-a-sale).
- **Manage users** (admins): add, edit and activate users.
- **Products** (admins and inventory staff): add, edit and discontinue products,
  set retail and wholesale prices, and manage categories and suppliers. Point of Sale
  sells these real products.
- **Reports** (admins: sales reports; admins and inventory staff: inventory report): transaction
  history, daily / monthly / yearly sales summary, top products, sales by cashier and terminal.
- **Inventory** (admins and inventory staff): stock levels, stock-in and adjustments, and every
  stock movement.
- **System & sync** (admins): activity logs, inventory logs, sync logs, and open items and price
  edits. **Settings** has the printers, and for admins System Status and Backup & Restore. The app also
  backs the database up on its own **every hour** (only the newest copy is kept, in a hidden system
  folder), and every one shows in Backup & Restore as "Auto Backup".
- **Several registers:** every register keeps selling on its own and syncs at once
  through the Main POS: sales, stock, products, users, logs. See
  [nickel-sales/src/sync/README.md](nickel-sales/src/sync/README.md).

Everyone can change their own password and log out.

## What you need (Windows)

| Thing | Do I install it myself? |
| ----- | ----------------------- |
| **Java JDK 21 or newer** (the code uses Java 21 features; the setup script still prints "17+") | The setup script offers to install Temurin 21 for you |
| **JavaFX** (the window/UI library) | No — downloaded automatically |
| **MySQL Server** (the database) | **Yes** — step 2 below |
| **MySQL Workbench** (to look at the database) | Optional, but recommended |
| Internet connection | Only for the first setup |

You do **not** need IntelliJ, Eclipse, Maven or Gradle just to run the app.

---

## Step 1 — Get the project onto your computer

Pick **one**:

**A. With GitHub Desktop** (recommended if you'll be committing changes)
1. Install [GitHub Desktop](https://desktop.github.com/) and sign in.
2. **File → Clone repository → URL**, paste
   `https://github.com/Redaw-t/NSC-SWENG`, and choose where to save it.
3. Use the **Current branch** button at the top to switch to the branch
   your team is working on.

**B. As a ZIP** (just to try it out)
1. Open <https://github.com/Redaw-t/NSC-SWENG>.
2. Pick the branch from the branch dropdown, then
   **Code → Download ZIP**.
3. Right-click the ZIP → **Extract All…**. Work from the extracted folder,
   not from inside the ZIP.

Inside the project you'll see a folder called **`nickel-sales`** — that's
where everything in the next steps happens.

---

## Step 2 — Install MySQL Server

The app stores its users and sales in MySQL, so you need it running on your computer.

1. Go to <https://dev.mysql.com/downloads/installer/> and download
   **MySQL Installer for Windows** (pick the larger, full download).
   You can click *"No thanks, just start my download"* instead of logging in.
2. Run it. When asked what to install, choose **Server only** — or
   **Custom** and tick both **MySQL Server** and **MySQL Workbench**.
3. Click through with the defaults. Two screens matter:
   - **Port:** leave it as **3306**.
   - **Root password:** type a password and **write it down**. You'll need
     it in the next step. (Forgot it later? See
     [Troubleshooting](#troubleshooting).)
4. Leave *"Start the MySQL Server at System Startup"* ticked and finish.

> Already using **XAMPP**? Its MySQL works too — just press **Start** next
> to MySQL in the XAMPP Control Panel before continuing.

---

## Step 3 — Run the setup script

1. Open the **`nickel-sales`** folder.
2. Double-click **`setup.bat`**.

   *If Windows shows "Windows protected your PC", click **More info →
   Run anyway**. This happens for any downloaded script.*

3. A black window checks each requirement and prints a line for each:

   ```
   [setup] Java JDK 17+      : OK      javac 21.0.5
   [setup] JavaFX 21.0.5     : OK      nickel-sales\lib\javafx
   [setup] jBCrypt, Connector/J: OK      bundled in nickel-sales\lib
   [setup] MySQL Server        : OK      running
   ```

4. Answer its questions:

   | It asks… | What to do |
   | -------- | ---------- |
   | *Install Eclipse Temurin JDK 21 now using winget? (Y/N)* | Press **Y**. When it finishes, **close the window and run `setup.bat` again** |
   | *MySQL password for user 'root':* | Type the password from step 2 and press Enter. Nothing appears while you type — that's normal |
   | *Create it now from database\NickelSales-Database-Config.sql? (Y/N)* | **Y** — this creates the database, then adds the demo users and test products from `database\NickelSales-Demo-Data.sql` |
   | *Save this MySQL password for the app…? (Y/N)* | **Y** — otherwise the app can't log in to MySQL |

5. Keep running `setup.bat` until the last lines say:

   ```
   [setup] Everything is ready. Double-click run-demo.bat to open the login screen.
   ```

   If it says something is **MISSING** or **NOT RUNNING** instead, it tells
   you exactly what to install or start and where to download it. Do that,
   then run `setup.bat` again.

---

## Step 4 — Run the app

1. In the `nickel-sales` folder, double-click **`run-demo.bat`**.
2. A black window shows `Compiling...`, then the app opens.
   (The black window stays open behind it — that's normal; errors show up
   there. Close it after you close the app.)
3. **The first time**, the **Installation Setup** screens open instead of the
   login screen. On the Main POS: choose **Main POS**, register the API key,
   confirm this computer's IP address, then **create the admin account**:
   type the store admin's first and last name, and the next screen shows
   the username made from them (first initial + last name, e.g.
   `JCubillan`, as Add user does) and asks for the password. That account
   is the store's first user, `NSC-00001`, and has every role.
4. Log in with that username and the password you chose. The database
   starts empty (no demo users, products or categories): add users in
   **Manage users**, and categories, suppliers and products in **Products**.

   Usernames ignore upper/lower case; passwords don't. Use the username,
   **not** the ID shown on the home screen (`NSC-00001`) — the ID won't log
   you in.

   Handy on the login screen: **SHOW** reveals what you typed in the
   password box, and a **"Caps Lock is on"** warning appears if Caps Lock is
   on. If nobody touches the app for 30 minutes it logs you out with
   *"Your session expired. Please log in again."*

🎉 That's it — you're set up. Next time, just double-click `run-demo.bat`.

### Other ways to run it

Open a terminal in `nickel-sales` (click the folder's address bar, type
`cmd`, press Enter) and run:

| Command | What it does |
| ------- | ------------ |
| `run-demo.bat` | The app (same as double-clicking) |
| `run-demo.bat --console` | A text-only demo with built-in test users — **works without MySQL** |
| `run-demo.bat --scripted` | Runs all the automatic self-tests; should end with **ALL CHECKS PASSED** |

---

## Ringing up a sale

Log in as a cashier (e.g. `JDelaCruz`), press **1** (Point of Sale), then:
scan or type a barcode and press **Enter** for each item · **F2** to type a
quantity first · **F3** to find an item without a barcode · **F12** to pay
(type the cash received, or **F5** for GCash / Maya / QRPh and the reference
number) · **Enter** to finish, then **Enter** again to print the receipt
(**Esc** skips printing). **F7** reprints a past receipt, **F9** cancels the
sale, **Esc** goes back.

Prices **include VAT**: the customer pays the sum of the line prices, and the
screen and receipt show how much of it is VAT (VATable sales = price / 1.12).

The receipt (logo, store details, items, totals, VAT breakdown, invoice barcode)
is drawn as one picture, so the preview and the printout are identical. It prints
on the receipt printer chosen in Settings > Printers. Receipts are always 58 mm
paper (32 characters a line).

The **store details** at the top of the receipt (name, address, proprietor, TIN,
phone) are kept in the store database, in the `StoreDetails` log: every change adds
a row with who performed it, and the newest row is the one printed. The first row
is made by Installation Setup on the Main POS (**Store Register**, right after the
admin account, performed by `NSC-00001`); after that only an admin can change them,
in **Settings > General > System Configuration > Store Details**, which also shows the change log. Until a row exists
(an older database, or a developer PC that never ran setup), receipts use the
`NSC_STORE_*` settings if set, else Nickel Sales Center's own details.
Products live in MySQL (`Product`, `Category`, `Supplier`). The demo data script adds test
products (barcodes `4800000000019`, `4800000000026`, `4800000000040`, ...). If your database was
created before the Products module, run `setup.bat` again to add the product tables.

## Looking at the database

With **MySQL Workbench**:

1. Click **+** next to *MySQL Connections*. Hostname `127.0.0.1`, port
   `3306`, username `root`. Click **Store in Vault…**, enter your password,
   then **Test Connection → OK**.
2. Open the connection. In **Schemas** (left), expand
   **nickelsales_database → Tables**, right-click **Users → Select Rows**.

The `UserPassword` column shows scrambled text starting with `$2a$10$` —
those are **bcrypt hashes**, not the real passwords. That's on purpose.

The database lives inside MySQL on **your** computer, so each teammate has
their own copy. Users you add don't appear on anyone else's machine.

### Adding your own user

**The easy way — in the app.** Log in as the admin you created in Installation Setup, click
**Manage users → Add user**, fill in the name, tick the roles and set a
temporary password (8–72 characters, with an uppercase letter and a
number). The app creates the username for you (first initial + last name,
e.g. `KChong`) and shows it when you save.

**By hand in MySQL** (only if you need to). Passwords must be stored as
bcrypt hashes, so it's two steps:

1. **Make the hash.** In a terminal inside `nickel-sales`:
   ```bat
   jshell -q --class-path lib\jbcrypt-0.4.jar
   ```
   then paste (with your password):
   ```java
   System.out.println(org.mindrot.jbcrypt.BCrypt.hashpw("YourPassword", org.mindrot.jbcrypt.BCrypt.gensalt(10)))
   ```
   Copy the `$2a$10$...` line it prints, then type `/exit`.

2. **Insert it** in Workbench (click ⚡ to run):
   ```sql
   USE NickelSales_Database;
   INSERT INTO Users (UserID, UserLoginName, UserPassword, UserName, UserLastname, UserRole, UserStatus)
   VALUES ('NSC-00007', 'FLast', '$2a$10$...your-hash...', 'First', 'Last', 'C', 'Active');
   ```
   `UserLoginName` is what you'll type on the login screen; it must be
   unique.
   Roles: `A` Admin, `C` Cashier, `I` Inventory — combine with `;`
   (e.g. `A;C;I`). Status: `Active`, `Inactive` or `Locked`.

---

## Troubleshooting

| Problem | Fix |
| ------- | --- |
| `run-demo.bat` window opens and closes instantly | Open a terminal in `nickel-sales`, run `run-demo.bat`, and read the error |
| "Something went wrong while logging in" for **every** account | The app can't reach MySQL. Run `setup.bat` — it checks MySQL is running, the database exists and your saved password works |
| "The database is out of date…", or errors **after you pulled new changes** | Close the app and run `setup.bat` — it adds any new tables and columns to your database and keeps your data. Still failing? A table changed shape: in MySQL Workbench run `DROP DATABASE NickelSales_Database;`, then run `setup.bat` and answer **Y** to create it again (this resets it to the demo users and removes saved sales) |
| "A database error occurred…" | The black window shows the real cause on a line starting with `[DATABASE]` — send that line to the team |
| "No printer is set up on this computer" when printing a receipt | In the app, open Settings → Printer settings and choose your receipt printer (and barcode label printer). Or set a default printer in Windows Settings → Printers, or set the NSC_PRINTER_NAME environment variable to your receipt printer's exact Windows name (for example POS-58). It must be a thermal receipt printer; "Microsoft Print to PDF" will not print a readable receipt |
| Receipt is cut off at the sides on a receipt printer | Receipts are made for 58 mm paper: check the printer uses 58 mm rolls and is the one chosen in Settings → Printers |
| "Invalid username or password." | Use the **username** chosen in Installation Setup (or in Manage users), not the ID (`NSC-00001`) — passwords are case-sensitive (watch for the Caps Lock warning) |
| "Your session expired. Please log in again." | Nobody used the app for 30 minutes, so it logged out for safety. Just log in again |
| `setup.bat` says MySQL is **NOT RUNNING** | Press Win+R, type `services.msc`, find the MySQL service, right-click → **Start** |
| MySQL keeps rejecting your password | You may have forgotten it — see [Resetting the MySQL root password](https://dev.mysql.com/doc/refman/en/resetting-permissions.html), or uninstall MySQL, delete `C:\ProgramData\MySQL`, and reinstall (you only lose the demo data, which `setup.bat` recreates) |
| `'jshell' is not recognized` | Use the full path, e.g. `"C:\Program Files\Eclipse Adoptium\jdk-21…\bin\jshell.exe"`. `setup.bat` prints your JDK folder on its first line |
| JDK installed but `setup.bat` still says MISSING | Close the window and run `setup.bat` again — new installs aren't visible to already-open windows |
| `Module javafx.controls not found` | The JavaFX download failed. Check your internet and run `setup.bat` again |

These are for development runs (`run-demo`). For the **installed** app at a store (logs, the
`NickelSalesDB` service, restoring a backup), see
[installer/README.md → Helping a store](installer/README.md#5-helping-a-store-when-something-goes-wrong).

Still stuck? Copy the full text from the black window and send it to the
team.

---

## macOS / Linux

1. Install **MySQL Server 8+** and start it (macOS: the installer from
   <https://dev.mysql.com/downloads/mysql/> or `brew install mysql`;
   Ubuntu/Debian: `sudo apt install mysql-server`).
2. Run the setup script. Like `setup.bat`, it checks Java (and on macOS
   offers to install it with Homebrew), downloads JavaFX, creates the
   database and offers to save your MySQL password in your shell profile:
   ```bash
   cd nickel-sales
   chmod +x setup.sh setup-database.sh run-demo.sh   # first time only
   ./setup.sh
   ```
3. Open a new terminal (so the saved password is loaded), then run:
   ```bash
   cd nickel-sales
   ./run-demo.sh          # add --console or --scripted for the no-database demos
   ```

---

## For developers

What's where:

```
NSC-SWENG/
  README.md          this guide
  docs/              REPOSITORY_MAP.md, development/ (architecture, templates, rules), archive/
  database/          NickelSales-Database-Config.sql (the schema; setup and the installer run it)
                     NickelSales-Demo-Data.sql (demo users + test products; development only)
  installer/         builds the Windows installer for stores (see installer/README.md)
  nickel-sales/      the Java app
    src/             production code, one folder per module:
      app/             the app itself (login -> home screen -> modules); Main is the entry point
      shared/          money, quantities, time, database, settings, log file, version - used by everyone
      auth/            login, users, permissions
      pos/             POS & sales
      product/         Product Management
      inventory/       Inventory Management
      barcode/         barcodes and labels
      reports/         Transaction & Reports
      system/          Audit & System (logs, backups, status)
      dashboard/       the home screen
      install/         what the installer runs after copying files (MySQL, databases, settings)
      sync/            sync between registers, through the Main POS (and its API)
    test/            self-tests, stand-ins (stubs/) and the dev launcher (app/DevApp)
    run-demo.bat     build and run (run-demo.bat --scripted = all self-tests)
```


- **[docs/development/](docs/development/ARCHITECTURE.md)** — the architecture rules, the module
  template, naming rules, and the git/commit/pull-request rules. **[docs/REPOSITORY_MAP.md](docs/REPOSITORY_MAP.md)**
  says which folder may depend on which.
- **[nickel-sales/CONTRIBUTING.md](nickel-sales/CONTRIBUTING.md)** — IntelliJ/Eclipse setup,
  environment variables, how to run the self-tests before you push, and troubleshooting.
- **[installer/README.md](installer/README.md)** — how the store installer is built, what it does on
  the store PC, how to release a new version, and what to check when a store has a problem.
- Never put a real password in the code — the repo is public. In development the app reads it
  from the `NSC_DB_PASSWORD` environment variable; on a store PC, from the settings file the
  installer writes.
