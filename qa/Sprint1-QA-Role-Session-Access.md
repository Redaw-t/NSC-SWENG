# Sprint 1 QA — Role / session / access-control (Rhian)

Owner: **Rhian Claire Yamsuan**  
App: Nickel Sales Center desktop (JavaFX), not a browser app.  
Build under test: Sprint 1 login flow (`LoginDemoApp` + `DashboardRouter` + `AuthorizationService` + `InMemorySessionManager`).

## What developers already shipped (so these cases match the code)

| Area | What exists now | What is still placeholder |
| --- | --- | --- |
| Login + session | JavaFX login, in-memory session, **Log out** button, 30-minute idle timeout | No persisted session after the app closes |
| Role routing | After login, user is sent to one dashboard: Admin, Cashier, or Inventory Staff. **If the user has Admin plus other roles, Admin wins.** | Real dashboard content (POS, inventory, reports) |
| Authorization | `AuthorizationService` permission matrix is implemented | No menus/shortcuts yet to open another role’s feature from the UI |
| Protected pages | There are no URLs. The window swaps the login form for a placeholder dashboard | No deep-link / Back-button behavior |

### Seed accounts (password `Password123` for all)

| User ID | Roles | Status | Expected landing (GUI) |
| --- | --- | --- | --- |
| NSC-00001 | Admin + Cashier + Inventory | Active | **Admin Dashboard** |
| NSC-00002 | Cashier | Active | **Cashier Dashboard** |
| NSC-00003 | Inventory Staff | Active | **Inventory Staff Dashboard** |
| NSC-00004 | Cashier | Inactive | Blocked at login (not Rhian’s login-message cases) |
| NSC-00005 | Cashier | Locked | Blocked at login |

Placeholder dashboard must show: title, `Logged in as {full name} ({user id})`, `Roles: ...`, and a **Log out** button.

### Permission matrix (service-level; UI menus are not built yet)

| Permission | Admin | Cashier | Inventory Staff |
| --- | --- | --- | --- |
| PROCESS_SALE | yes | yes | no |
| MANAGE_PRODUCTS | yes | no | yes |
| UPDATE_INVENTORY | yes | no | yes |
| VIEW_REPORTS | yes | no | yes |
| MANAGE_USERS | yes | no | no |
| MANAGE_SYSTEM | yes | no | no |

Inactive and locked users must be denied even if the role would normally allow the permission.

---

## Automated script (service-level)

From `nickel-sales/`:

```bat
run-demo.bat --qa
```

Expected last line: `RESULTS: 27 tests passed, 0 tests failed.`  
This covers TC-01, TC-02, TC-03, TC-12, TC-13, and TC-15 without opening the GUI. Run the GUI cases below on `run-demo.bat` (LoginDemoApp).

---

## Manual / GUI test cases (Rhian)

| Test Case ID | Feature | QA Owner | Preconditions & Test Data | Test Steps | Expected Result | Actual Result | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TC-01 | Role-based login redirect | Rhian | Active accounts: NSC-00001, NSC-00002, NSC-00003 / Password123. App is on the login screen (`run-demo.bat`). | 1. Log in as NSC-00001. Note the heading. Log out. 2. Repeat for NSC-00002. 3. Repeat for NSC-00003. | NSC-00001 → **Admin Dashboard** (Admin wins over Cashier/Inventory). NSC-00002 → **Cashier Dashboard**. NSC-00003 → **Inventory Staff Dashboard**. Each screen shows the matching user name and roles. | All test accounts with their supported role/s landed on the right page. | Passed |
| TC-02 | Access to authorized landing page | Rhian | Logged in as each active role from TC-01. | After each successful login, read the placeholder dashboard: heading, “Logged in as …”, Roles line, Log out button. | The landing page for that role loads. Sprint 1 expected content is the placeholder (title + identity + roles + Log out), not the real POS/inventory screens. | With the GUI demo, the permitted landing dashboard is shown. Real feature content is not implemented yet. | Testing |
| TC-03 | Block access to unauthorized feature | Rhian | Logged in as Cashier (NSC-00002) and as Inventory (NSC-00003). | Check the window for menus, tabs, or buttons that would open another role’s dashboard or a restricted permission (sale / inventory / users / system). Confirm there is no second dashboard switcher. | Sprint 1 GUI: the user only sees their own landing dashboard; no control opens another role’s screen. Restricted feature pages are not shown. Service-level deny rules are covered by `run-demo.bat --qa`. | GUI has no unauthorized-feature navigation yet; deny rules are confirmed in the QA script. | Testing |
| TC-12 | Unauthenticated access to a protected screen | Rhian | App just started, or user is logged out. This is a **desktop** app (no URL to type). | 1. Launch the app. Confirm only the login screen is visible. 2. Do not log in. Confirm there is no dashboard, no “Logged in as”, and no role content. | The login screen is the only view. Protected dashboard content is not shown. | | Not Started |
| TC-13 | Logging out ends the session | Rhian | Logged in as NSC-00002. | Click **Log out**. Confirm the login screen is shown with empty fields. Log in again with the same account. | Session ends. User is returned to login and must sign in again to see a dashboard. | | Not Started |
| TC-14 | Previous dashboard is not usable after logout | Rhian | Logged in and viewing a dashboard. **Do not use a browser Back button** (this is not a web app). | Log out. Try to get the old dashboard back without logging in: stay in the same window, do not enter credentials, look for any remaining dashboard controls. Optionally close and reopen the app. | After logout the window shows login only. Closing the app also drops the in-memory session; reopen requires a new login. | | Not Started |
| TC-15 | Session timeout | Rhian | Logged in. Default idle timeout in code is **30 minutes**. `getCurrentUser` (any “who is logged in?” check) resets the timer. | GUI: leave the app idle for 30 minutes with no logout, then click **Log out** or otherwise force a session check if a later sprint adds one. Do not wait 30 minutes for every build — run `run-demo.bat --qa`, which uses a 250 ms timeout. | Expired session cannot keep protected access. QA script: idle session becomes invalid; using `getCurrentUser` before expiry keeps it alive. GUI does not yet auto-kick to login on idle; file a follow-up if the window stays on the dashboard after 30 minutes with no prompt. | | Not Started |
| TC-16 | User with no role cannot stay in the app | Rhian | Service-level only this sprint (no seed account has zero roles). Covered by `--qa`. | Run `run-demo.bat --qa` and confirm TC-01 “user with no role has no landing page”. | Router must not open a dashboard; production path logs the user out. | | Not Started |

Trisha’s login-field cases (TC-04 through TC-11) stay as they are. Note: TC-10 actual result currently copies TC-09; both blank fields should still show “Enter your Staff ID and password.”

---

## Out of scope / rewrite notes from the old draft

- **TC-14 browser Back** — removed. JavaFX replaces the scene root; there is no browser history.
- **TC-03 “try a shortcut to a restricted page”** — no such shortcut in Sprint 1 UI. Keep the case, but judge GUI by “only my dashboard is visible,” and judge denies with `--qa`.
- **TC-02 “expected content of the page”** — placeholder copy is the Sprint 1 expected content (`Note: placeholder lng muna for testing lol.`).
