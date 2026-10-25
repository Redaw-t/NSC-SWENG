# Contributing

For machine setup, running the app and the self-tests, see
[nickel-sales/CONTRIBUTING.md](../../nickel-sales/CONTRIBUTING.md) and the root [README](../../README.md).
For where code goes, see [REPOSITORY_MAP.md](../REPOSITORY_MAP.md), [ARCHITECTURE.md](ARCHITECTURE.md),
[MODULE_TEMPLATE.md](MODULE_TEMPLATE.md) and [NAMING_AND_STRUCTURE.md](NAMING_AND_STRUCTURE.md).
For the important existing files see [FILE_INDEX.md](FILE_INDEX.md), and for what changed before
Iteration 2 (and what not to undo) see [ITERATION2_REPO_CHANGES.md](ITERATION2_REPO_CHANGES.md).

## Branches

- `main` is what everyone builds on. **Do not push straight to `main`.**
- Branch name: `<area>/<short-description>`, lowercase, hyphens:
  `product/crud-screen`, `inventory/stock-in`, `pos/db-product-lookup`, `fix/receipt-width`, `docs/architecture`.
- Keep a branch to one module where you can. Pull `main` into it before opening the pull request.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/): `type(scope): what changed`,
imperative, under ~72 characters. Types: `feat`, `fix`, `test`, `docs`, `refactor`, `chore`.
Examples: `feat(product): add product repository`, `refactor(pos): move SQL classes to pos.repository`.
Say in the body *why* if it isn't obvious. If teammates must do something after pulling
(re-run setup, set a variable), add a `BREAKING CHANGE:` footer saying exactly what.

## Pull requests

1. Open a pull request into `main`; get at least one teammate to review it.
2. Describe what changed, which module(s) it touches, and how you tested it.
3. Keep it small and reviewable. Do not mix a refactor (moves/renames) with a feature.
4. Check that the files you changed stay in your module (see the table in
   [REPOSITORY_MAP.md](../REPOSITORY_MAP.md)).

## Tests before merge

1. `run-demo.bat --scripted` (macOS/Linux: `./run-demo.sh --scripted`) must end with
   **ALL CHECKS PASSED**. It needs the database set up (`setup.bat`) for the database checks.
2. Start the app (`run-demo.bat`) and log in with at least one demo account; open the screen you changed.
3. If you change behaviour a check covers, update the check in the same pull request.
4. New module: add a `*Check` for it and list it in the `--scripted` lists of `run-demo.bat` and `run-demo.sh`.

## Do not break other people's work

- **Do not change another module's public interface** (anything in its `contract/` package, or a
  public method other modules call) without agreeing it with that module's owner first.
  Changing a contract means changing every consumer and every stand-in in `test/stubs/`.
- Do not edit another module's tables, repositories or screens. Ask the owner, or ask for a new
  method on their contract.
- Shared files that everyone touches (`app/Modules.java`, `Main.java`, the SQL script) get **small,
  additive** edits: add your line, don't reorganise.
- Adding a class to `shared/` needs team agreement.

## Production code never depends on test code

- Nothing under `src/` may import `stubs.*`, `app.DevApp`, a `*Check`/`*Demo`, or anything in `test/`.
- Stand-ins and mocks go in `test/stubs/`. A demo or dev launcher goes in `test/`.
- Quick check before you push (from `nickel-sales/`): this should print nothing:

  ```
  git grep -nE "import (stubs|test)\." -- src
  ```

## Adding a new module

Follow "Adding a module, step by step" in [MODULE_TEMPLATE.md](MODULE_TEMPLATE.md), then add the
module to [REPOSITORY_MAP.md](../REPOSITORY_MAP.md).

## Changing the database

Everything is in `database/NickelSales-Database-Config.sql` and must stay safe to run again
(`CREATE TABLE IF NOT EXISTS`, `INSERT IGNORE`). A new column on an existing table goes in the
`CREATE TABLE` **and** the "UPGRADES" block at the end of the script. Details:
[nickel-sales/CONTRIBUTING.md](../../nickel-sales/CONTRIBUTING.md#changing-the-database).

## Style

4 spaces, no tabs. SQL only through `PreparedStatement` with `?` parameters. User-facing messages
come from services, and screens only display them. Never put a real password or secret in code or
in the repository.
