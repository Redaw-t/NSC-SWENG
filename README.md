# NSC-SWENG

POS and Inventory Management System for **Nickel Sales Center** — a desktop
app (Java + JavaFX) that runs offline against a local MySQL database.

This page gets you from nothing to a working app. No prior Java
experience needed. It takes about 20–30 minutes the first time, mostly
waiting for installers.

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
- **Products & inventory**, **Reports**, **System & sync**: not built yet
  ("coming soon"). Until Products & inventory exists, Point of Sale uses a
  few test products.

Everyone can change their own password and log out.

## What you need (Windows)

| Thing | Do I install it myself? |
| ----- | ----------------------- |
| **Java JDK 17 or newer** | The setup script offers to install it for you |
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
   | *Create it now from database\NickelSales-Database-Config.sql? (Y/N)* | **Y** — this creates the database and the demo users |
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
2. A black window shows `Compiling...`, then the **login screen** opens.
   (The black window stays open behind it — that's normal; errors show up
   there. Close it after you close the app.)
3. Log in with:

   | Username | Password | You'll see |
   | -------- | -------- | ---------- |
   | `JCubillan` | `Password123` | Home screen with every button (this account has every role) |
   | `JDelaCruz` | `Password123` | Home screen with **Point of Sale** |
   | `MSantaAna` | `Password123` | Home screen with **Products & inventory** and **Reports** |
   | `RSantos` | `Password123` | "This account is inactive…" message |
   | `KRodriguez` | `Password123` | "This account is locked…" message |

   Usernames ignore upper/lower case (`jcubillan` works too); passwords
   don't. Use the username, **not** the ID shown on the home screen
   (`NSC-00001`) — the ID won't log you in.

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

Prices are **before VAT**: the screen and receipt show the subtotal, the 12%
VAT added on VATable items, and the total the customer pays.

The receipt prints on the computer's default printer. To put your store's
details on it (Windows, then open a new window):
`setx NSC_STORE_ADDRESS "123 Rizal Ave., Manila"`, and likewise
`NSC_STORE_TIN`, `NSC_STORE_PHONE`, `NSC_STORE_NAME`. For 58 mm receipt
paper: `setx NSC_RECEIPT_WIDTH 32`.
Until the real inventory is connected, the products are test ones
(barcodes `4800000000019`, `4800000000026`, `4800000000040`, ...).

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

**The easy way — in the app.** Log in as an admin (e.g. `JCubillan`), click
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
| "No printer is set up on this computer" when printing a receipt | Set a default printer in Windows Settings → Printers (any printer works, even *Microsoft Print to PDF*), or press **Ctrl+P** in the receipt window to choose one |
| Receipt is cut off at the sides on a receipt printer | For 58 mm paper run `setx NSC_RECEIPT_WIDTH 32`, then open a new window |
| "Invalid username or password." | Use the **username** (e.g. `JCubillan`), not the ID (`NSC-00001`), and check the password is `Password123` — passwords are case-sensitive (watch for the Caps Lock warning) |
| "Your session expired. Please log in again." | Nobody used the app for 30 minutes, so it logged out for safety. Just log in again |
| `setup.bat` says MySQL is **NOT RUNNING** | Press Win+R, type `services.msc`, find the MySQL service, right-click → **Start** |
| MySQL keeps rejecting your password | You may have forgotten it — see [Resetting the MySQL root password](https://dev.mysql.com/doc/refman/en/resetting-permissions.html), or uninstall MySQL, delete `C:\ProgramData\MySQL`, and reinstall (you only lose the demo data, which `setup.bat` recreates) |
| `'jshell' is not recognized` | Use the full path, e.g. `"C:\Program Files\Eclipse Adoptium\jdk-21…\bin\jshell.exe"`. `setup.bat` prints your JDK folder on its first line |
| JDK installed but `setup.bat` still says MISSING | Close the window and run `setup.bat` again — new installs aren't visible to already-open windows |
| `Module javafx.controls not found` | The JavaFX download failed. Check your internet and run `setup.bat` again |

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
  database/          the MySQL script (setup runs it for you)
  nickel-sales/      the Java app
    src/             production code, one folder per module:
      app/             the app itself (login -> home screen -> modules); Main is the entry point
      shared/          money, quantities, time, database - used by everyone
      auth/            login, users, permissions
      pos/             POS & sales
      product/         Product Management (contracts now, rest TODO)
      inventory/       Inventory Management (contracts now, rest TODO)
      barcode/ sync/ reports/ system/   placeholders (README only)
    test/            self-tests, stand-ins (stubs/) and the dev launcher (app/DevApp)
    run-demo.bat     build and run (run-demo.bat --scripted = all self-tests)
```


- **[docs/development/](docs/development/ARCHITECTURE.md)** — the architecture rules, the module
  template, naming rules, and the git/commit/pull-request rules. **[docs/REPOSITORY_MAP.md](docs/REPOSITORY_MAP.md)**
  says which folder may depend on which.
- **[nickel-sales/CONTRIBUTING.md](nickel-sales/CONTRIBUTING.md)** — IntelliJ/Eclipse setup,
  environment variables, how to run the self-tests before you push, and troubleshooting.
- Never put a real password in the code — the repo is public. The app
  reads it from the `NSC_DB_PASSWORD` environment variable.
