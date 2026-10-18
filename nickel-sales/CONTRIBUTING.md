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
contains the **authentication module** plus the **session manager and login
screen**, **authorization and role routing** (`AUTHZ`), and a MySQL-backed
user repository (`DATA`).

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
   `NSC-00001` / `Password123`.

No MySQL yet? Run `run-demo.bat --scripted` in a terminal instead; it uses
built-in stub users and should end with `RESULT: 8 passed, 0 failed`.

You can skip step 2 — `run-demo.bat` runs `setup.bat` itself if anything is
missing. The first run needs internet access (to download JavaFX); after
that everything works offline.

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

   Windows: `setx NSC_DB_PASSWORD "your-mysql-password"`, then open a **new**
   terminal. macOS/Linux: `export NSC_DB_PASSWORD=...` in your shell profile.
   IntelliJ/Eclipse: add it to the run configuration's environment variables.
   **Never put a real password in the code** — the repo is public.

### macOS / Linux

You need a JDK 17+ first (`brew install --cask temurin@21`, or your distro's
`openjdk-21-jdk` package). Then:

```bash
cd nickel-sales
chmod +x run-demo.sh      # first time only
./run-demo.sh             # downloads JavaFX for your OS on the first run
```

### IntelliJ IDEA

1. Run `setup.bat` (or `./run-demo.sh --setup`) once so `lib/javafx/` exists.
2. **File → Open** the repository root folder (`NSC-SWENG-Session-UI`, the
   one containing `NSC-SWENG-Session-UI.iml`).
3. **File → Project Structure → Project → SDK**: pick a JDK 17+.
4. Use the shared run configurations in the top-right dropdown:
   **AuthDemo (console)** and **LoginDemoApp (GUI)**. They already pass the
   JavaFX VM options, so don't run `LoginDemoApp` via the green arrow next to
   `main()` — that run has no JavaFX options and fails with
   *"JavaFX runtime components are missing"*.

The `jbcrypt-0.4` and `javafx` libraries are already configured in
`NSC-SWENG-Session-UI.iml`, and the run configurations live in `.run/`.

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

1. **Look up the user.** Calls `UserRepository.findByUsername(...)`. The
   Users table has no separate username column, so the **User ID
   (`NSC-00001`) is the login id**. Not found → one bcrypt check against a
   dummy hash (so "unknown user" takes exactly as long as "wrong password"),
   then throws `InvalidCredentialsException`.
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
| `InvalidCredentialsException` | `INVALID_CREDENTIALS` | Wrong ID or wrong password — deliberately identical, so nobody can probe which IDs exist |
| `InactiveUserException` | `INACTIVE_USER` | Correct password, inactive account |
| `LockedUserException` | `LOCKED_USER` | Correct password, locked account |

A corrupt/malformed stored hash also counts as `INVALID_CREDENTIALS` rather
than crashing — database damage must not turn into a stack trace on screen.


### 1.4 File map — what is real, what is a contract

```
NSC-SWENG-Session-UI/                  repository root (open this in IntelliJ)
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
      data/                            <- DATA: MySQL implementation
        Database.java                    opens JDBC connections (NSC_DB_* env vars)
        MySqlUserRepository.java         implements contract.UserRepository
      authz/                           <- AUTHZ: DONE
        Permission.java                  the six actions a user can be allowed to do
        AuthorizationService.java        role -> permission matrix; checkAccess()
      ui/
        login/
          LoginScreen.java             <- SESSION/UI: DONE (JavaFX login form)
          LogoutAction.java            <- SESSION/UI: DONE (logout handler + button)
        dashboard/                     <- AUTHZ: DONE
          DashboardRouter.java           picks the screen for the user's role
          PHDashboard.java               placeholder Admin / Cashier / Inventory screen
    test/nickelsales/auth/demo/        THROWAWAY harnesses (see section 2)
      AuthDemo.java                    console demo + stub UserRepository (no DB needed)
      AuthzDemo.java                   console check of the role -> permission matrix
      LoginDemoApp.java                JavaFX demo against MySQL: login -> role's dashboard -> logout
    lib/
      jbcrypt-0.4.jar                  bcrypt hashing (committed)
      mysql-connector-j-9.4.0.jar      MySQL JDBC driver (committed)
      javafx/                          JavaFX jars — downloaded by setup, NOT committed
    setup.bat                          Windows: checks every dependency, says what to install and where;
                                       downloads JavaFX; sets up the MySQL database
    setup-database.ps1                 helper used by setup.bat for the database step
    run-demo.bat                       Windows: setup + compile + run the demo
    run-demo.sh                        macOS/Linux equivalent
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

1. **Login ID = User ID** (`NSC-00001`). The Users table has no username
   column; `UserName`/`UserLastname` are the person's actual name.
2. **`login()` returns `LoginResult`** (user + session) instead of a bare
   `User`, so the login screen receives the sessionId it needs for
   `getCurrentUser()` and `logout()`.
3. **`logout`/`getCurrentUser` take the sessionId** (the diagram says
   userId) — sessions, not users, are what expire, and one user could
   theoretically be logged in on more than one terminal.

---

---

## 2. How to run and test it right now

The authentication module has no `main()` of its own — by design, it needs
the other modules' implementations. The `test/` folder ships three
throwaway harnesses that wire the real modules together:

| Command (from `nickel-sales/`) | What it does |
| ------------------------------ | ------------ |
| `run-demo.bat` / `./run-demo.sh` (default, double-click) | **Login screen** (`LoginDemoApp`): the real JavaFX `LoginScreen` + `InMemorySessionManager` + `LogoutAction` + `DashboardRouter`, reading users from **MySQL** via `MySqlUserRepository`: login → the role's placeholder dashboard (Admin / Cashier / Inventory) → Log out → login |
| `run-demo.bat --console` / `./run-demo.sh --console` | Console demo (`AuthDemo`) with in-memory **stub** users — no database needed: runs the scenarios, then an interactive login prompt |
| `run-demo.bat --scripted` / `./run-demo.sh --scripted` | Console scenarios only — a smoke test; exit code 1 if anything fails |
| `run-demo.bat --qa` / `./run-demo.sh --qa` | role/session/access checks (`RoleSessionAccessDemo`); exit code 1 if anything fails |

Every run recompiles all of `src/` and `test/` from scratch, so you always
run your latest code.

### Compiling by hand

The scripts are the recommended way. If you want the raw commands, run them
from `nickel-sales/` after setup has created `lib/javafx/`:

Windows (cmd):

```bat
dir /s /b src\*.java test\*.java > out\sources.txt
javac -encoding UTF-8 --module-path lib\javafx --add-modules javafx.controls -cp lib\jbcrypt-0.4.jar -d out @out\sources.txt
java --module-path lib\javafx --add-modules javafx.controls -cp "out;lib\jbcrypt-0.4.jar" nickelsales.auth.demo.AuthDemo
```

(Create `out\` first with `mkdir out`. If the project path contains spaces,
use `run-demo.bat` instead — it quotes the paths in the source list.)

macOS / Linux:

```bash
mkdir -p out
javac -encoding UTF-8 --module-path lib/javafx --add-modules javafx.controls \
      -cp lib/jbcrypt-0.4.jar -d out $(find src test -name "*.java")
java --module-path lib/javafx --add-modules javafx.controls \
     -cp "out:lib/jbcrypt-0.4.jar" nickelsales.auth.demo.AuthDemo
```

Note the classpath separator: `;` on Windows, `:` on macOS/Linux. JavaFX is
needed to compile because `ui/login/` and `LoginDemoApp` use it.

### Checking the permission matrix (AUTHZ)

`run-demo` compiles `AuthzDemo` but does not run it. After any `run-demo`
run (so `out/` is fresh), from `nickel-sales/`:

```bat
java -cp "out;lib\jbcrypt-0.4.jar" nickelsales.auth.demo.AuthzDemo
```

(macOS/Linux: `-cp "out:lib/jbcrypt-0.4.jar"`.) It prints every permission
for each demo account, then checks the key rules. Expected last line:
`RESULTS: 10 tests passed, 0 tests failed.` Exit code 1 if any check fails.

### Demo accounts

Password `Password123` for all — test seeds only. The console demos use the
stub users below; the login screen uses the rows inserted by
`database/NickelSales-Database-Config.sql` (same IDs, roles and statuses;
the SQL file also adds `NSC-00006`, an extra A;C;I account).

| User ID   | Name            | Roles   | Status   | Lands on (login screen)     |
| --------- | --------------- | ------- | -------- | --------------------------- |
| NSC-00001 | Jerick Cubillan | A;C;I   | Active   | Admin Dashboard             |
| NSC-00002 | Juan Dela Cruz  | C       | Active   | Cashier Dashboard           |
| NSC-00003 | Maria Santa Ana | I       | Active   | Inventory Staff Dashboard   |
| NSC-00004 | Test Inactive   | C       | Inactive | — (blocked at login)        |
| NSC-00005 | Test Locked     | C       | Locked   | — (blocked at login)        |

The scenarios cover: valid login per role, wrong password, unknown user
(identical error), inactive and locked accounts with correct passwords,
boolean validation, and the session lifecycle. **Delete `test/` once the
real `DATA` and `SESSION/UI` implementations are wired together** — it exists
only so the modules are demonstrable standalone.

---

## 3. Roles and what each one must deliver

The rest of Sprint 1 is four work packages. Each lists the exact signatures
to implement, implementation notes, and the definition of done. A programmer
can write the code themselves, or hand the matching subsection to an AI
assistant to produce it.

| Role | Status |
| ---- | ------ |
| `AUTH` | **Done** |
| `SESSION/UI` | **Done** (session manager + login screen) |
| `DATA` | **In progress** — `MySqlUserRepository` + `Database` in `src/nickelsales/data/` |
| `AUTHZ` | **Done** (permission matrix + role routing; placeholder dashboards) |

### 3.1 Role `DATA` — user storage and retrieval

Implement `nickelsales.auth.contract.UserRepository`:

```java
User findById(String userId);          // null when not found
User findByUsername(String username);  // the login lookup — see notes
void save(User user);                  // insert; fail if the ID already exists
void update(User user);                // fail if the ID does not exist
List<User> findAll();
```

Implementation notes:

- Backend: the `Users` table of database `NickelSales_Database`
  (`database/NickelSales-Database-Config.sql` at the repository root): `UserID` PK varchar(10),
  `UserPassword` varchar(60), `UserName`, `UserLastname`, `UserRole`
  varchar(5), `UserStatus` varchar(8). Use JDBC prepared statements
  (`mysql-connector-j` — see section 5, "Adding a dependency"); never
  concatenate SQL strings.
- `findByUsername` is a case-insensitive, whitespace-trimmed lookup on
  `UserID`, returning `null` (not an exception) when not found.
- Build `User` objects with the ready-made converters — do not hand-parse
  the encodings: `UserRole.parseDbRoles("A;C;I")` /
  `UserRole.toDbCodes(...)`, `UserStatus.fromDbString("Active")` /
  `toDbString()`.
- **Passwords must be bcrypt hashes.** The current SQL seed inserts
  plaintext `"Password"` — those rows cannot log in until re-seeded:
  `BCrypt.hashpw("Password123", BCrypt.gensalt(10))` produces a 60-char
  hash that fits `VARCHAR(60)`.
- Definition of done: users can be saved/retrieved locally; the three role
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
- A session expires after 30 minutes of inactivity. Pass a `Duration` to the
  constructor to change it (tests use a few hundred milliseconds).
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

- `LoginScreen` (labelled "Staff ID"; the value typed is the User ID) does
  NOT pick the dashboard. It hands the `LoginResult` to the callback; role
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
| Empty User ID or password | "Enter your Staff ID and password." (no call to `auth.login`) |
| Any `AuthenticationException` | the exception's own `getMessage()` |
| Login worked but `session()` is null | "...no session could be started. Contact the system administrator." |
| Any other runtime error | "Something went wrong while logging in. Try again, or contact the system administrator." |

The password field is cleared after every failure.

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
  `PROCESS_SALE`, Inventory: `UPDATE_INVENTORY`); `DashboardRouter.openScreen`
  checks the session (`getCurrentUser`) and `checkAccess` before showing it.
- `checkAccess` denies null and non-ACTIVE users (fail closed).
- **Open question for the team:** should inventory staff's `VIEW_REPORTS`
  be limited to inventory reports? (Split the permission, or filter by role
  in the reports screen.)

### 3.4 Role `AUTH` — this module

Already implemented and verified. Remaining responsibilities going forward:
only changes that arise from agreed contract changes, plus the Sprint 2+
extensions in section 4 that live inside this package.

---

## 4. Next sprints — how they plug in

Everything below builds on what already exists. Note how little of it
requires touching `AuthenticationService` — that is the point of the
contract design.

**User account management (next sprint).** A `UserAccountService` that
calls `UserRepository.save/update` for create/activate/deactivate/
assign-role, and `changePassword` which (a) verifies the current password
via `validateCredentials`, (b) checks the new password against
`PasswordPolicy.validate`, (c) stores `BCrypt.hashpw(...)` as the new hash,
(d) audits the change. Zero changes to the `auth/` package — it only *uses*
the contracts.

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

Once `DATA` lands, the only new code is the wiring — usually a few lines in
the application's entry point (`LoginDemoApp.start()` is a working template):

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

## 6. Troubleshooting

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
| `No suitable driver found for jdbc:mysql` | `lib/mysql-connector-j-9.4.0.jar` is missing from the classpath. Use `run-demo.bat` / the IntelliJ run configs, or add the jar to your `-cp`. |
