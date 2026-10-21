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

No MySQL yet? Run `run-demo.bat --scripted` in a terminal instead; it uses
built-in stub users and should end with `RESULT: 8 passed, 0 failed`.

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
2. Tell the app your MySQL login. `nickelsales.data.Database` reads these
   environment variables:

   | Variable | Default |
   | -------- | ------- |
   | `NSC_DB_URL` | `jdbc:mysql://localhost:3306/NickelSales_Database?...` |
   | `NSC_DB_USER` | `root` |
   | `NSC_DB_PASSWORD` | *none — you must set it* |
   | `NSC_IDLE_TIMEOUT_MINUTES` | `30` — minutes without mouse/keyboard activity before automatic logout |

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
./run-demo.sh             # login screen (add --console or --scripted for the stub demos)
```

### IntelliJ IDEA

1. Run `setup.bat` (or `./run-demo.sh --setup`) once so `lib/javafx/` exists.
2. **File → Open** the repository root folder (the one containing
   `NSC-SWENG-Session-UI.iml`).
3. **File → Project Structure → Project → SDK**: pick a JDK 17+.
4. Use the shared run configurations in the top-right dropdown:
   **AuthDemo (console)** and **LoginDemoApp (GUI)**. They already pass the
   JavaFX VM options, so don't run `LoginDemoApp` via the green arrow next to
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
`src/nickelsales/auth/contract/`. Other modules implement those contracts;
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
  database/
    NickelSales-Database-Config.sql    MySQL schema + seed data (used by DATA)
  nickel-sales/
    src/nickelsales/
      auth/
        auth/                          <- AUTH: DONE (implemented, tested)
          AuthenticationService.java     all login logic lives here
          LoginResult.java               record: user() + session()
          AuthenticationException.java   base error, catch this in UIs
          InvalidCredentialsException.java
          InactiveUserException.java
          LockedUserException.java
        contract/                      <- AGREEMENTS ONLY (no behavior)
          User.java                      entity shape the auth module reads
          UserRole.java                  ADMIN/CASHIER/INVENTORY_STAFF <-> DB codes "A"/"C"/"I"
          UserStatus.java                ACTIVE/INACTIVE/LOCKED <-> "Active"/"Inactive"
          Session.java                   entity shape (sessionId, userId, timestamps, active)
          UserRepository.java            interface — implemented by DATA (data/)
          SessionManager.java            interface — implemented by SESSION/UI (done)
          PasswordPolicy.java            interface — used by user management
          AuditLogger.java               interface — implemented by logging
        session/
          InMemorySessionManager.java    <- SESSION/UI: DONE
        account/                       <- USER ACCOUNTS: DONE (section 3.5)
          UserAdminService.java          admin: create / edit / activate / deactivate users
          UserAccountService.java        anyone: change own password
          PassPolicy.java                password rules (8-72 chars, an uppercase letter, a number)
          AccountException.java          user-ready error shown by the admin screens
          PasswordChangeException.java   user-ready error shown by Change password
      data/                            <- DATA: MySQL implementation
        Database.java                    opens JDBC connections (NSC_DB_* env vars)
        MySqlUserRepository.java         implements contract.UserRepository
      authz/                           <- AUTHZ: DONE
        Permission.java                  the six actions a user can be allowed to do
        AuthorizationService.java        role -> permission matrix; checkAccess()
      ui/
        login/
          LoginScreen.java             <- SESSION/UI: DONE (login form, SHOW/HIDE, Caps Lock warning)
          LogoutAction.java            <- SESSION/UI: DONE (logout handler + button)
        dashboard/                     <- AUTHZ: DONE
          DashboardRouter.java           picks the screen for the user's CURRENT roles; idle logout
          IdleLogoutTimer.java           restarts on any key/mouse/touch; fires after the idle timeout
          PHDashboard.java               placeholder Admin / Cashier / Inventory screen
        admin/                         <- USER ACCOUNTS: DONE
          UserManagementScreen.java      list users; Add / Edit / Activate-Deactivate
          UserFormDialog.java            the Add user / Edit user form
        account/
          ChangePasswordDialog.java      "Change password" on every dashboard
    test/nickelsales/auth/demo/        THROWAWAY harnesses (see section 2)
      AuthDemo.java                    console demo + stub UserRepository (no DB needed)
      AuthzDemo.java                   console check of the role -> permission matrix
      UserAdminDemo.java               console check of user management rules
      LoginDemoApp.java                the app's entry point for now: login -> dashboard -> logout
    lib/
      jbcrypt-0.4.jar                  bcrypt hashing (committed)
      mysql-connector-j-9.4.0.jar      MySQL JDBC driver (committed)
      javafx/                          JavaFX jars — downloaded by setup, NOT committed
    setup.bat / setup.sh               checks every dependency, says what to install and where;
                                       downloads JavaFX; sets up the MySQL database
    setup-database.ps1 / .sh           helper used by setup for the database step
    run-demo.bat / run-demo.sh         setup + compile + run (Windows / macOS-Linux)
    CONTRIBUTING.md                    this file
```

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

The authentication module has no `main()` of its own — by design, it needs
the other modules' implementations. The `test/` folder holds the entry
point used for now (`LoginDemoApp`) and the self-tests:

| Command (from `nickel-sales/`) | What it does |
| ------------------------------ | ------------ |
| `run-demo.bat` / `./run-demo.sh` (default, double-click) | **Login screen** (`LoginDemoApp`): the real JavaFX `LoginScreen` + `InMemorySessionManager` + `LogoutAction` + `DashboardRouter`, reading users from **MySQL** via `MySqlUserRepository`: login → the role's placeholder dashboard (Admin / Cashier / Inventory) → Log out → login |
| `run-demo.bat --console` / `./run-demo.sh --console` | Console demo (`AuthDemo`) with in-memory **stub** users — no database needed: runs the scenarios, then an interactive login prompt |
| `run-demo.bat --scripted` / `./run-demo.sh --scripted` | Console scenarios only — a smoke test; exit code 1 if anything fails |

Every run recompiles all of `src/` and `test/` from scratch, so you always
run your latest code.

### Compiling by hand

The scripts are the recommended way. If you want the raw commands, run them
from `nickel-sales/` after setup has created `lib/javafx/`:

Windows (cmd):

```bat
dir /s /b src\*.java test\*.java > out\sources.txt
javac -encoding UTF-8 --module-path lib\javafx --add-modules javafx.controls -cp lib\jbcrypt-0.4.jar -d out @out\sources.txt
java --module-path lib\javafx --add-modules javafx.controls -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" nickelsales.auth.demo.LoginDemoApp
```

(Create `out\` first with `mkdir out`. If the project path contains spaces,
use `run-demo.bat` instead — it quotes the paths in the source list.)

macOS / Linux:

```bash
mkdir -p out
javac -encoding UTF-8 --module-path lib/javafx --add-modules javafx.controls \
      -cp lib/jbcrypt-0.4.jar -d out $(find src test -name "*.java")
java --module-path lib/javafx --add-modules javafx.controls \
     -cp "out:lib/jbcrypt-0.4.jar:lib/mysql-connector-j-9.4.0.jar" nickelsales.auth.demo.LoginDemoApp
```

Note the classpath separator: `;` on Windows, `:` on macOS/Linux. JavaFX is
needed to compile because the `ui/` screens use it; the MySQL jar is needed
at run time for the login screen (replace `LoginDemoApp` with `AuthDemo` for
the console demo).

### Other self-tests (permission matrix, user management)

`run-demo` compiles these but does not run them. After any `run-demo` run
(so `out/` is fresh), from `nickel-sales/`:

```bat
java -cp "out;lib\jbcrypt-0.4.jar" nickelsales.auth.demo.AuthzDemo
java -cp "out;lib\jbcrypt-0.4.jar" nickelsales.auth.demo.UserAdminDemo
```

(macOS/Linux: `-cp "out:lib/jbcrypt-0.4.jar"`.) Expected last lines:
`RESULTS: 10 tests passed, 0 tests failed.` and `RESULT: 8 passed, 0 failed`.
Neither needs MySQL. Exit code 1 if any check fails.

### Demo accounts

Password `Password123` for all — test seeds only. The console demos use
built-in copies of these users; the login screen uses the rows inserted by
`database/NickelSales-Database-Config.sql` (the same accounts).

| Log in as   | User ID   | Name               | Roles | Status   | Lands on                  |
| ----------- | --------- | ------------------ | ----- | -------- | ------------------------- |
| `JCubillan` | NSC-00001 | Jerick Cubillan    | A;C;I | Active   | Admin Dashboard           |
| `JDelaCruz` | NSC-00002 | Juan Dela Cruz     | C     | Active   | Cashier Dashboard         |
| `MSantaAna` | NSC-00003 | Maria Santa Ana    | I     | Active   | Inventory Staff Dashboard |
| `RSantos`   | NSC-00004 | Rhian Santos       | C     | Inactive | — (blocked at login)      |
| `KRodriguez`| NSC-00005 | Kimberly Rodriguez | C     | Locked   | — (blocked at login)      |

The `--scripted` scenarios cover: valid login per role (login names are
case-insensitive), wrong password, unknown username and User IDs rejected
(identical error), inactive and locked accounts with correct passwords,
boolean validation, and the session lifecycle.

`test/` stays until the real application entry point exists: `LoginDemoApp`
is how the app is started for now, and the demos are the team's self-tests.

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
| `DATA` | **Done** — `MySqlUserRepository` + `Database` in `src/nickelsales/data/` |
| `AUTHZ` | **Done** (permission matrix + role routing; placeholder dashboards) |
| User accounts | **Done** — add / edit / activate users, change password (section 3.5) |

### 3.1 Role `DATA` — user storage and retrieval (DONE)

`nickelsales.data.MySqlUserRepository` implements
`nickelsales.auth.contract.UserRepository`:

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
src/nickelsales/
  auth/session/InMemorySessionManager.java   implements contract.SessionManager
  ui/login/LoginScreen.java                  JavaFX login form
  ui/login/LogoutAction.java                 logout handler + "Log out" button
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

- Landing screen order: ADMIN → Admin dashboard; else CASHIER → Cashier;
  else INVENTORY_STAFF → Inventory. A `C;I` user lands on Cashier.
- Each screen requires a permission (Admin: `MANAGE_SYSTEM`, Cashier:
  `PROCESS_SALE`, Inventory: `UPDATE_INVENTORY`). `DashboardRouter.showHome`
  re-reads the user (`getCurrentUser`) every time, so role changes made
  during a session take effect on the next screen; if access is denied it
  logs out instead of leaving the user stuck.
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


## 5. Putting it all together (the Sprint 1 demo)

The wiring lives in the application's entry point — `LoginDemoApp.start()`
is the working version. In short:

```java
UserRepository repo = /* DATA's implementation */;
SessionManager sess = new InMemorySessionManager();
AuditLogger audit   = (event, userId) -> System.out.println("[AUDIT] " + event + " " + userId);

AuthenticationService auth = new AuthenticationService(repo, sess, null, audit);
// hand `auth` to the LoginScreen; keep the sessionId after each login
```

Everything demonstrated today with the stubs — valid/invalid/inactive/locked
logins, sessions, logout — then runs against the real database and the real
UI with **zero changes to the `auth/` package**.

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

1. `run-demo.bat --scripted` → `RESULT: 8 passed, 0 failed`
2. `AuthzDemo` → `10 tests passed` and `UserAdminDemo` → `8 passed`
   (commands in section 2)
3. Double-click `run-demo.bat` and log in with at least one demo account.
4. If you changed behaviour that a demo checks (e.g. how login works),
   update the demo **and** this file in the same pull request.

### 6.4 Changing the database

`NickelSales-Database-Config.sql` uses `CREATE TABLE IF NOT EXISTS`, so it
**never changes a table that already exists**. After a schema change (e.g.
the `UserLoginName` column), everyone with an older database must rebuild it:

1. MySQL Workbench: `DROP DATABASE NickelSales_Database;`
   (this deletes the data — note any users you added yourself)
2. Run `setup.bat` (or `./setup.sh`) and answer **Y** to "Create it now?".

When you change the schema: update the SQL file, `MySqlUserRepository`, the
stub users in `AuthDemo`, and this file, and mention the rebuild in the
commit (`BREAKING CHANGE:`).

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
| `JavaFX runtime components are missing` (IntelliJ) | You ran `LoginDemoApp` without VM options. Use the **LoginDemoApp (GUI)** run configuration. |
| `package javafx... does not exist` in IntelliJ | `lib/javafx/` didn't exist when IntelliJ loaded the project. Run setup, then **File → Reload All from Disk** (or reopen the project). |
| JavaFX download fails | No internet, or a proxy/firewall blocks `repo1.maven.org`. Download the three `javafx-*-21.0.5-win.jar` files named in the error into `nickel-sales/lib/javafx/` by hand. |
| `Two versions of module javafx.base found` | Old jars in `lib/javafx/`. The scripts remove other versions automatically; if it persists, delete the folder and rerun setup. |
| Changes don't show up when running | You're running stale classes. The scripts rebuild `out/` each run; in IntelliJ use **Build → Rebuild Project**. |
| `./run-demo.sh: Permission denied` | `chmod +x run-demo.sh` once. |
| Login screen says "Something went wrong while logging in" for every account | The app can't reach MySQL. Double-click `setup.bat`: it checks that MySQL is running, that the database exists, and that your saved password works. The full error is also printed in the `run-demo.bat` window. |
| Every login fails with "Something went wrong" **after pulling** | Your database predates a schema change (MySQL error `Unknown column 'UserLoginName'` in the console). Rebuild it — section 6.4. |
| "Invalid username or password." with `NSC-00001` | Log in with the login name (`JCubillan`), not the User ID. |
| "Your session expired. Please log in again." | Idle logout after `NSC_IDLE_TIMEOUT_MINUTES` (default 30) without activity. Log in again. |
| `No suitable driver found for jdbc:mysql` | `lib/mysql-connector-j-9.4.0.jar` is missing from the classpath. Use `run-demo.bat` / the IntelliJ run configs, or add the jar to your `-cp`. |
