# Nickel Sales Center — Authentication Module (Sprint 1)

*I suggest updating this md file as you work through the other areas of the project; it will serve as a guide for other developers/QAs.*

## Who this document is for

This document is for **whoever continues work on this project — a human
programmer or an AI assistant**. It explains:

1. how the authentication module in this folder works,
2. which parts are done and which parts are still to be built,
3. exactly how to build the missing parts so they plug in without breaking
   anything.

Work in this project is organized by **functional role**, not by person. The
roles are `AUTH`, `DATA`, `SESSION/UI`, and `AUTHZ`. Wherever this document
says a role, read it as: *the person or AI assistant currently assigned to
that role*. If you are an AI assistant, you will find ready-to-use
instruction blocks for each role further down — copy the one matching your
assignment and follow it literally.

**Project context:** desktop POS and Inventory Management System for Nickel
Sales Center (offline, local MySQL database). Sprint 1 delivers an
end-to-end login flow: credential validation, bcrypt password verification,
session creation, and role recognition. This folder contains the
**authentication module only** — everything else is other roles' work and
is deliberately NOT implemented here.

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
nickel-sales-auth/
  src/nickelsales/auth/
    auth/                            <- THE DELIVERABLE (implemented, tested)
      AuthenticationService.java       all login logic lives here
      LoginResult.java                record: user() + session()
      AuthenticationException.java    base error, catch this in UIs
      InvalidCredentialsException.java
      InactiveUserException.java
      LockedUserException.java
    contract/                        <- AGREEMENTS ONLY (no behavior)
      User.java                       entity shape the auth module reads
      UserRole.java                   ADMIN/CASHIER/INVENTORY_STAFF <-> DB codes "A"/"C"/"I"
      UserStatus.java                 ACTIVE/INACTIVE/LOCKED <-> "Active"/"Inactive"
      Session.java                    entity shape (sessionId, userId, timestamps, active)
      UserRepository.java             interface — implemented by DATA
      SessionManager.java             interface — implemented by SESSION/UI
      PasswordPolicy.java             interface — used by user management
      AuditLogger.java                interface — implemented by logging
  lib/jbcrypt-0.4.jar                the ONLY dependency (bcrypt hashing)
  run-demo.bat                       double-click: compiles + runs the demo
  test/nickelsales/auth/demo/
      AuthDemo.java                  THROWAWAY harness (see section 2)
  CONTRIBUTING.md                          this file
```

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

## 2. How to run and test it right now

The authentication module has no `main()` of its own — by design, it needs
the other modules' implementations. To run it **before** those exist, this
folder ships a throwaway harness (`test/.../AuthDemo.java`) that feeds the
service two in-memory stubs: a stub `UserRepository` seeded with demo
accounts, and a stub `SessionManager`.

- **Windows:** double-click `run-demo.bat` (finds the JDK automatically).
- **Any terminal** (JDK 17+), from this folder:

```bash
javac -encoding UTF-8 -cp lib/jbcrypt-0.4.jar -d out $(find src test -name "*.java")
java -cp "out;lib/jbcrypt-0.4.jar" nickelsales.auth.demo.AuthDemo
```

Add `--scripted` to run only the scenario list (it doubles as a smoke test:
exit code 1 if anything fails). The interactive prompt accepts real typing —
use it to demo login validation.

Seeded accounts (password `Password123` for all — test seeds only):

| User ID   | Name            | Roles   | Status   |
| --------- | --------------- | ------- | -------- |
| NSC-00001 | Jerick Cubillan | A;C;I   | Active   |
| NSC-00002 | Juan Dela Cruz  | C       | Active   |
| NSC-00003 | Maria Santa Ana | I       | Active   |
| NSC-00004 | Test Inactive   | C       | Inactive |
| NSC-00005 | Test Locked     | C       | Locked   |

The scenarios shown cover: valid login per role, wrong password, unknown
user (identical error), inactive and locked accounts with correct passwords,
boolean validation, and the session lifecycle. **Delete `test/` once the
real `DATA` and `SESSION/UI` implementations land** — it exists only so the
module is demonstrable standalone.

---

## 3. Roles and what each one must deliver

The rest of Sprint 1 is four work packages. Each lists the exact signatures
to implement, implementation notes, and the definition of done. Both
approaches are valid and expected: **a programmer writes the code
themselves, or an AI assistant is given the matching instruction block from
section 5 and produces it.**

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
  (`NickelSales-Database-Config.sql`): `UserID` PK varchar(10),
  `UserPassword` varchar(60), `UserName`, `UserLastname`, `UserRole`
  varchar(5), `UserStatus` varchar(8). Use JDBC prepared statements
  (`mysql-connector-j`); never concatenate SQL strings.
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

### 3.2 Role `SESSION/UI` — sessions and the login screen

Two deliverables.

**(a)** Implement `nickelsales.auth.contract.SessionManager`:

```java
Session createSession(String userId);
Session getSession(String sessionId);        // null when unknown/expired
void invalidateSession(String sessionId);
boolean isSessionValid(String sessionId);
```

An in-memory `Map<String, Session>` with UUID session ids is enough for
Sprint 1; add an inactivity timeout (class diagram suggests 30 minutes)
when convenient. Persisting sessions is optional and can come later — the
interface does not care.

**(b)** Build the login screen on top of the authentication module:

```java
try {
    LoginResult result = auth.login(userIdField.getText(), passwordField.getText());
    String sessionId = result.session().getSessionId();     // keep this
    openDashboardFor(result.user().getRoles());             // AUTHZ decides what this is
} catch (AuthenticationException e) {
    showMessage(e.getMessage());   // already user-ready; or switch on e.getCode()
}
```

Definition of done: username/password can be entered and submitted; valid
login succeeds and the session stays active; invalid login shows the right
message; a logout action calls `auth.logout(sessionId)` and returns to the
login screen.

---
## Session UI (SESSION/UI role)

Implemented: `InMemorySessionManager`, `LoginScreen`, `LogoutAction`.

```
src/nickelsales/
  auth/session/InMemorySessionManager.java   implements contract.SessionManager
  ui/login/LoginScreen.java                   JavaFX login form
  ui/login/LogoutAction.java                  logout handler + "Log out" button
```

### Session rules

- Session ids are random UUIDs, kept in memory (lost when the app closes).
- A session expires after 30 minutes of inactivity. Pass a `Duration` to the
  constructor to change it (tests use a few hundred milliseconds).
- `getSession(id)` counts as activity and resets the timer, so
  `AuthenticationService.getCurrentUser(sessionId)` keeps a session alive.
  `isSessionValid(id)` only checks and never resets the timer.
- Unknown, expired, and logged-out ids return `null`/`false`; they never throw.

### Wiring

```java
SessionManager sessions = new InMemorySessionManager();
AuthenticationService auth = new AuthenticationService(repo, sessions, null, audit);

LoginScreen login = new LoginScreen(auth, result -> router.openDashboardFor(result));
LogoutAction logout = new LogoutAction(auth, () -> { login.reset(); router.showLogin(); });
```

- `LoginScreen` (labelled "Staff ID"; the value typed is the User ID) does NOT pick the dashboard. It hands the `LoginResult` to the
  callback; role routing is the AUTHZ role's job (`result.user().getRoles()`).
- Keep `result.session().getSessionId()` after login. Each dashboard passes it to
  `logout.createButton(sessionId)` and to `auth.getCurrentUser(sessionId)`.
- A screen that gets `null` from `getCurrentUser(sessionId)` must send the user
  back to the login screen.

### Messages the login screen shows

| Situation | Message |
| --------- | ------- |
| Empty User ID or password | "Enter your Staff ID and password." (no call to `auth.login`) |
| Any `AuthenticationException` | the exception's own `getMessage()` |
| Login worked but `session()` is null | "...no session could be started. Contact the system administrator." |
| Any other runtime error | "Something went wrong while logging in. Try again, or contact the system administrator." |

The password field is cleared after every failure.
---

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

## 5. Copy-paste instruction blocks for AI assistants (DEPRACATED)


---

## 6. Putting it all together (the Sprint 1 demo)

Once `DATA` and `SESSION/UI` land, the only new code is the wiring —
usually a few lines in the application's entry point:

```java
UserRepository repo = /* DATA's implementation */;
SessionManager sess = /* SESSION/UI's implementation */;
AuditLogger audit   = (event, userId) -> System.out.println("[AUDIT] " + event + " " + userId);

AuthenticationService auth = new AuthenticationService(repo, sess, null, audit);
// hand `auth` to the login screen; keep the sessionId after each login
```

Everything demonstrated today with the stubs — valid/invalid/inactive/locked
logins, sessions, logout — then runs against the real database and the real
UI with **zero changes to this module**.

### Compiling

No Maven/Gradle required (plain `javac`, JDK 17+):

```bash
cd nickel-sales-auth
javac -encoding UTF-8 -cp lib/jbcrypt-0.4.jar -d out $(find src -name "*.java")
```

When the team adopts a shared build setup, drop `src/nickelsales/auth/**`
into it and add `org.mindrot:jbcrypt:0.4` as a dependency. The only import
outside the JDK is `org.mindrot.jbcrypt.BCrypt`.
