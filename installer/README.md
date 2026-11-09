# Windows installer

This folder makes **`NickelSalesSetup-<version>.exe`**, the one file a store needs to install
Nickel Sales Center on a Windows PC. It contains the app, its own Java, and its own MySQL
database server, so the store PC needs nothing installed beforehand.

> **Just want to run the app while developing?** You don't need this folder. Use
> `nickel-sales/setup.bat` and `run-demo.bat` (see the [main README](../README.md)).
> This folder is only for what gets installed at the **store**.

---

## Contents

1. [Quick start: build the installer](#1-quick-start-build-the-installer)
2. [How it works](#2-how-it-works)
3. [Releasing a new version](#3-releasing-a-new-version)
4. [Things every developer should know](#4-things-every-developer-should-know)
   ([the API key](#the-api-key-how-the-registers-trust-each-other))
5. [Helping a store when something goes wrong](#5-helping-a-store-when-something-goes-wrong)
6. [Testing a change to the installer](#6-testing-a-change-to-the-installer)
7. [Files in this folder](#7-files-in-this-folder)
8. [What's done and what's next](#8-whats-done-and-whats-next)

---

## 1. Quick start: build the installer

### What you need (once)

| Tool | Why | How to get it |
| ---- | --- | ------------- |
| **JDK 21 or newer** with `jpackage` and `jmods` | Compiles the app and makes its own small Java runtime | [Temurin 21](https://adoptium.net/) (`winget install EclipseAdoptium.Temurin.21.JDK`) or any JDK 21+ |
| **Inno Setup 6** | Turns the files into the setup wizard | `winget install JRSoftware.InnoSetup` |
| Internet, the first time | The script downloads MySQL, JavaFX and the Visual C++ runtime | They are cached afterwards |

### Build

From the repository root, in a terminal:

```
powershell -ExecutionPolicy Bypass -File installer\build-installer.ps1
```

It takes a few minutes and ends with:

```
Built ...\installer\dist\NickelSalesSetup-0.1.2.exe (85 MB)
```

**That `.exe` in `installer\dist\` is what you give the store.** Copy it to their PC (USB,
Google Drive...) and double-click it. Nothing else from this repository is needed there.

Options:
- `-JdkHome "C:\Program Files\Eclipse Adoptium\jdk-21..."` builds with a specific JDK
  (otherwise the newest one found is used).
- `-AppOnly` builds only the app folder (`installer\build\app\`), without MySQL or the wizard.
  Quicker when you only want to check the packaged app starts.

---

## 2. How it works

### The build (`build-installer.ps1`)

```
 nickel-sales\src  ──javac──►  nickelsales.jar ─┐
 nickel-sales\lib  (jBCrypt, Connector/J) ──────┤
 JDK + JavaFX jmods ──jlink──► runtime\  ───────┼──jpackage──► build\app\NickelSales\  ─┐
                                                │              (NickelSales.exe + Java) │
 MySQL 8.4 ZIP  ──keep only what's needed──►  build\mysql\  ────────────────────────────┤
 database\NickelSales-Database-Config.sql ─────────────────────────────────────────────┤
 vc_redist.x64.exe (Microsoft) ─────────────────────────────────────────────────────────┤
                                                                                         ▼
                                              NickelSales.iss ──Inno Setup──► dist\NickelSalesSetup-x.y.z.exe
```

- Only `nickel-sales\src` and `resources` are compiled. Tests and `DevApp` are not included.
- The Java runtime is trimmed to the modules the app uses, so the app is about 63 MB, not 300.
- MySQL's 1 GB ZIP is cut to about 130 MB: the server, the `mysql` client, `mysqladmin`,
  `mysqldump`, their DLLs, plugins, error messages and ICU data. Debug files are dropped.
- Downloads are cached in `installer\.cache` and checked before use: JavaFX and MySQL by
  SHA-256, the Visual C++ runtime by Microsoft's signature.

### What the setup wizard asks

Welcome → install folder → shortcuts → Ready → Finish. The wizard asks no questions of its own.
Receipts are always 58 mm paper (32 characters a line).

The wizard does **not** ask whether this PC is the Main POS or an Additional POS, its terminal ID
(T01, T02 ...), the administrator account or the store details. The app's own **Installation
Setup** screen asks those the first time it starts
(`nickel-sales/src/app/install/InstallationSetupScreen.java`): on the Main POS it creates the admin
(`NSC-00001`), then **Store Register** asks for the store details printed on receipts and saves
them in the `StoreDetails` log as performed by that admin. On an Additional POS it asks for the
Main POS's address, gets its terminal ID from it, and copies the store's data, store details
included. Later changes are made by an admin in **Settings > Store Details**. The
Finish page tells the store to start the app to do this. Every PC, Main or Additional, gets the
same installer.

### What setup does after copying the files

The wizard is [`NickelSales.iss`](NickelSales.iss) (Inno Setup's Pascal script). It only asks
questions and copies files. The real work is done in **Java**, by
[`nickel-sales/src/install/InstallTasks.java`](../nickel-sales/src/install/InstallTasks.java),
run with the app's own Java runtime. Java is easier to read and test than Pascal, and it reuses
the app's own code (password hashing, login names, backups).

`InstallTasks setup`:

1. installs the **Visual C++ runtime** if the PC lacks it (MySQL needs it; done by the `.iss`);
2. creates the database files and registers MySQL as the Windows service **`NickelSalesDB`**,
   on port **3307** and reachable from this PC only. A different port from the usual 3306 means
   it never clashes with another MySQL on the PC, including yours;
3. gives MySQL's `root` user a random password, saved in `root.cnf` for support;
4. runs **`NickelSales-Database-Config.sql`** (the store's records) and
   **`NickelSales-AppConfig.sql`** (this PC's terminals and settings, `nickelsales_appconfig`),
   schemas only. **The demo data is never installed;**
5. creates the MySQL user **`nickelsales`** with a random password and rights on
   `NickelSales_Database` and `nickelsales_appconfig` only. The app signs in to MySQL with this
   user, never with root;
6. writes the **settings file** (database URL, user and password).

Everything it does is logged to `install.log` (see [section 5](#5-helping-a-store-when-something-goes-wrong)).

### What ends up on the store PC

| Where | What | Who can read it |
| ----- | ---- | --------------- |
| `C:\Program Files\Nickel Sales Center\` | The program: `NickelSales.exe`, `runtime\` (Java), `app\` (jars), `mysql\` (server), `database\` (schema) | Everyone |
| `C:\ProgramData\NickelSales\nickelsales.properties` | The settings file | Read: the PC's users. Change: administrators |
| `C:\ProgramData\NickelSales\mysql\` | The database files (`data\`), `my.ini`, `root.cnf` (root password), `mysqld.err` (MySQL's log) | **Administrators only** |
| `C:\ProgramData\NickelSales\logs\` | `nickelsales-YYYY-MM-DD.log` (the app, kept 30 days), `install.log` (setup) | Everyone can write |
| `C:\ProgramData\NickelSales\backups\` | Backups made before each update (newest 5 kept) | Read: the PC's users |
| Windows service `NickelSalesDB` | MySQL, started automatically with Windows | |
| Start menu | "Nickel Sales Center"; a "Nickel Sales Center support" folder with the log files, the update backups, and the app with a console | |

`C:\ProgramData` is a hidden folder. Type the path into File Explorer's address bar.

### Updating (running a newer setup)

Setup sees that the store's data is already there, **skips all the questions**, and:

1. asks for Nickel Sales Center to be **closed** if it's open;
2. **backs the whole database up** to `backups\NickelSales-before-update-<old>-to-<new>-<date>.sql.gz`.
   It uses the new version's code on the Java already installed, before any file is replaced.
   The file is in the app's own backup format, so an admin can restore it from
   **Settings → Backup & Restore → Restore from Backup**. **If the backup fails, setup stops and changes nothing;**
3. stops the database, replaces the program files, and runs `InstallTasks setup` again. The
   schema script upgrades the tables, and the data, settings and passwords are kept.

### Checks before anything is copied

| Situation | What setup does |
| --------- | --------------- |
| The app is open | Asks you to close it (Windows can't reliably close a Java app by itself) |
| New install, and something already uses port 3307 | Stops with a message; nothing is installed |
| This setup is **older** than the installed version | Refuses and closes (older code could damage newer data) |
| Same version again | Allowed: works like an update (backup first) |

### Uninstalling

Settings → Apps → Nickel Sales Center → Uninstall. Windows first asks *"Are you sure…?"*
(**No** cancels the whole uninstall). Then it closes the app if needed, removes the
`NickelSalesDB` service and the program files, and asks about the data with two buttons:
**Keep the store's data** (the default) or **Delete all store data**. Kept data is picked up
again by the next install, which then skips the questions and keeps the same users.

### The installed app vs. your development runs

The same code runs in both, but it reads its settings differently (`shared/Settings.java`):

| | Installed app (`app.Main`) | Development (`run-demo`, DevApp, IDE, self-tests) |
| - | -------------------------- | ------------------------------------------------- |
| Settings come from | The settings file in `C:\ProgramData\NickelSales\`; environment variables only fill gaps | Environment variables (`NSC_DB_PASSWORD`...); a settings file only if `NSC_SETTINGS_FILE` names one |
| Database | `NickelSalesDB` on port 3307, user `nickelsales` | Your own MySQL on 3306, usually `root` |
| Users and products | Only the admin made in Installation Setup; the store adds the rest | Demo users (`JCubillan`...) and test products |
| Log file | Yes, in `C:\ProgramData\NickelSales\logs\` | No: output goes to the console |
| Copies allowed | One per Windows user | No limit |

So you can have the installed app and your development setup on the same PC, and neither
affects the other.

---

## 3. Releasing a new version

1. **Make and test your change** as usual (`run-demo.bat --scripted` must end with *ALL CHECKS PASSED*).
2. **Raise the version** in [`nickel-sales/src/shared/AppVersion.java`](../nickel-sales/src/shared/AppVersion.java):
   ```java
   public static final String VERSION = "0.1.3";   // was 0.1.2
   ```
   Always three numbers. Raise it for every build you give a store. It's how setup tells
   an update from an older version, and it's shown in the window title.
3. **Build**: `powershell -ExecutionPolicy Bypass -File installer\build-installer.ps1`.
4. **Try it** on a PC that has the previous version installed: run the new setup, check the
   Finish page mentions the backup, sign in, and check your change.
5. **Give the store** `installer\dist\NickelSalesSetup-0.1.3.exe`. They run it; their data is kept.

Old setups pile up in `installer\dist\`. Keep the last one or two, so you can test updates
from them, and delete the rest.

---

## 4. Things every developer should know

**Database changes must be safe to run again.** Every install and every update runs
`database/NickelSales-Database-Config.sql` on the store's real data. Use
`CREATE TABLE IF NOT EXISTS ... ENGINE=InnoDB`, and add new columns or indexes in an "upgrade"
block that first checks they're missing (copy an existing one). Never `DROP` or empty a table
there. Test rows go in `NickelSales-Demo-Data.sql`, which stores never get. See
[`database/README.md`](../database/README.md).

**Never install over a store without raising the version.** A same-version setup still
works, but you can't tell builds apart afterwards.

**Don't change these:**
- `AppGuid` in `NickelSales.iss`. Windows uses it to recognise the app; a new one makes Windows
  treat it as a different program, and you'd get two entries under Apps.
- The service name `NickelSalesDB` or port `3307`, unless you also handle stores that already
  have the old ones. They're in both `NickelSales.iss` and `InstallTasks.java`.
- The data folder `C:\ProgramData\NickelSales`. `Settings.java`, `LogFile.java` and the `.iss`
  all expect it.

**Adding a library jar:** put it in `nickel-sales/lib/` and add its name to `$Libs` at the top of
`build-installer.ps1`. The installer picks up every jar in the app folder by itself.

**Changing the MySQL version:** in `build-installer.ps1` change `$MySqlVersion`, `$MySqlUrl` and
`$MySqlSha256`. Get the SHA-256 with `Get-FileHash <zip>` after downloading the ZIP from Oracle's
site. Stay on an LTS release (8.4.x now). Test an **update** from the previous installer, not
only a fresh install: MySQL upgrades its data files when it first starts on the new version.

**Changing the JavaFX version:** keep `$FxVersion` in `build-installer.ps1` the same as the jars
in `nickel-sales/lib/javafx`, and update `$FxSha256` (Gluon publishes it next to the download).

**Java version:** the code uses Java 21 features, so build with JDK 21 or newer. The installer
bundles the Java it was built with. A long-term-support version (21 or 25) keeps getting security
fixes for years; short-term ones (like 27) only for about six months.

**Passwords:** setup generates the MySQL `root` and `nickelsales` passwords; nobody types them.
They live only on the store PC, in `mysql\root.cnf` and the settings file. Never commit a
settings file. The repository only has `nickel-sales/config/nickelsales.properties.example`.

**Your shell can't read the database folder** unless it runs as administrator. That's on purpose.

**"Windows protected your PC" (SmartScreen):** the setup isn't code-signed, so Windows warns
the first time. Click **More info → Run anyway**. Signing needs a paid certificate.

### The API key (how the registers trust each other)

**What it is.** The Main POS and the Additional POS PCs talk to each other over the store's
network. To stop any other device on that network from joining in, every message between them
carries a secret key, the **API key**. The Main POS ignores anything that doesn't have it.

**Where you type it.** Nowhere in the installer. The first time the app starts, its
Installation Setup screen asks for it on page 2: **Register API Key** on the Main POS,
**Enter the API Key** on each Additional POS. Every PC in the store gets the same key.

**Where the "right" key is decided.** In the app's code, not on that screen. The app doesn't
contain the key itself, only a fingerprint of it (a SHA-256 hash, in
[`nickel-sales/src/shared/ApiKey.java`](../nickel-sales/src/shared/ApiKey.java), `DEFAULT_SHA256`).
When someone types a key, the app makes the fingerprint of what was typed and compares it:

- they match → the key is accepted and saved on that PC;
- they don't → *"That API key is not right"*, and setup won't go on.

So the setup screen is only where the key is **entered**. You can't make up a new key there.
The Main POS also checks every incoming message against the same built-in fingerprint.

**Who has the key.** The developer who added the sync code chose it. Keep it out of the
repository, chats and screenshots, and give it to the store in person or in a private message.

**Making a new key** (the old one leaked, or nobody has it):

1. Choose a key: upper-case letters and digits, at least 20 characters. Letter case and spaces
   around it don't matter when it's typed in.
2. Get its fingerprint in PowerShell (put your key in place of `YOURKEY`):
   ```
   $k='YOURKEY'; -join ([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($k.ToUpper())) | % { $_.ToString('x2') })
   ```
3. Give the stores the new fingerprint in **one** of these two ways:

   | | **A. Rebuild (recommended)** | **B. Settings file (no rebuild)** |
   | - | --- | --- |
   | What you do | Put the fingerprint in `DEFAULT_SHA256`, raise the version, build the installer ([section 3](#3-releasing-a-new-version)) | On each PC, add `NSC_API_KEY_SHA256=<fingerprint>` to `C:\ProgramData\NickelSales\nickelsales.properties` (as an administrator), then restart the app |
   | Reaches every PC by | Running the new setup on each PC | Editing the file on each PC by hand |
   | Good for | Every new key from now on | A quick fix before the next release |

   On your own development PC you don't need the installer for either: after changing
   `DEFAULT_SHA256`, just run `run-demo.bat` again (it compiles the code fresh every time), or
   use `setx NSC_API_KEY_SHA256 <fingerprint>` and open a new terminal.

4. Give the key itself to the store, and type it into Installation Setup on each PC.

**Every PC must agree.** If the Main POS has the new fingerprint and an Additional POS still
has the old one (or the other way round), they refuse each other. Update all the PCs together.

**In short:** changing the key means changing the app (a new installer) or every PC's settings
file. That's how it was built; letting the Main POS choose the key on its setup screen would
remove the rebuild, but that isn't done.

---

## 5. Helping a store when something goes wrong

**Look at the logs first** (Start menu → *Nickel Sales Center support* → *Log files*, or
`C:\ProgramData\NickelSales\logs\`):

| File | What's in it |
| ---- | ------------ |
| `install.log` | Every setup, update and backup, step by step, with the exact error if one failed |
| `nickelsales-YYYY-MM-DD.log` | What the app printed that day: start-up, settings used, database errors (`[DATABASE] ...`) |
| `C:\ProgramData\NickelSales\mysql\mysqld.err` | MySQL's own log (admin-only), if the service won't start |

| Problem | What to do |
| ------- | ---------- |
| Setup said it "could not finish setting up the database" | Read the end of `install.log`, fix the cause, and **run setup again**: it continues where it stopped |
| The app says it can't connect to the database | Check the service: Win+R → `services.msc` → **NickelSalesDB** should be *Running*. Then read today's `nickelsales-*.log` |
| You want to see what the app is doing | Start menu → *Nickel Sales Center support* → *Nickel Sales Center (with console)*. It's the same app with a window showing its messages live. Closing that window closes the app |
| An update went wrong | Sign in as an admin → **Settings → Backup & Restore → Restore from Backup**, and pick the file in `C:\ProgramData\NickelSales\backups\` |
| You need to look inside the database | As an administrator, `"C:\Program Files\Nickel Sales Center\mysql\bin\mysql.exe" --defaults-file=C:\ProgramData\NickelSales\mysql\root.cnf NickelSales_Database` |
| Port 3307 is taken | Find the program with `netstat -ano \| findstr :3307` and close or remove it, then run setup again |
| The store forgot the admin password | Another admin can reset it in **Manage users**. If there's no other admin, contact the team |

---

## 6. Testing a change to the installer

The self-tests (`run-demo.bat --scripted`, suite `install.InstallCheck`) cover the settings file,
`my.ini`, generated passwords and backup naming. Everything touching Windows (services, UAC,
file permissions) can only be tested by actually running setup. Running it needs administrator
approval, so a person has to click through it.

After a change, run through these, ideally on a **clean Windows VM** without Java or MySQL:

- [ ] Fresh install: the wizard, then start the app: Installation Setup as Main POS, sign in with the login name it shows, make a sale
- [ ] Second PC: same setup, then Installation Setup as Additional POS with the Main POS's address: it gets T02 and the store's data
- [ ] Update from the previous setup: no questions, Finish page names the backup, data still there
- [ ] Older setup over newer: refused
- [ ] Setup or uninstall while the app is open: asks to close it
- [ ] Uninstall → **Keep the store's data**, then install again: same users come back
- [ ] Uninstall → **Delete all store data**: `C:\ProgramData\NickelSales` is gone

To log an uninstall:
`"C:\Program Files\Nickel Sales Center\unins000.exe" /LOG="%TEMP%\nsc-uninstall.log"`.
Setup writes its own log to `%TEMP%\Setup Log <date> #NNN.txt` automatically.

---

## 7. Files in this folder

| File | What it is |
| ---- | ---------- |
| `build-installer.ps1` | The build: compile, Java runtime, app folder, MySQL, wizard |
| `NickelSales.iss` | The setup wizard and uninstaller (Inno Setup script) |
| `README.md` | This page |
| `build\` | Work files of the last build; emptied every build *(ignored by git)* |
| `dist\` | Finished setups, one per version; kept between builds *(ignored by git)* |
| `.cache\` | Downloads (MySQL, JavaFX, Visual C++) *(ignored by git)* |

The installers aren't committed, since each is 85 MB. To share one with the team, attach it
to a GitHub Release.

The Java side is in `nickel-sales/src/install/InstallTasks.java` (tests:
`nickel-sales/test/install/InstallCheck.java`). The app code made for the installer:
`shared/Settings.java` (settings file), `shared/LogFile.java` (log file),
`shared/AppVersion.java` (version) and `app/SingleInstance.java` (one copy at a time). The first
admin and the terminal ID come from the app's Installation Setup (`app/install/`,
`auth/account/FirstAdminService.java`), which reads the same settings file through
`shared/AppConfigDatabase.java`.

---

## 8. What's done and what's next

| Phase | What | State |
| ----- | ---- | ----- |
| 1 | Packaged app with its own Java, version number, log file | ✅ Done |
| 2 | Installer: wizard, MySQL service, `nickelsales` user, settings file, shortcuts, uninstall | ✅ Done, tested on a dev PC (before the in-app setup took over the first admin) |
| 3 | Safe updates: backup first, no downgrades, port check, app must be closed | ✅ Done, tested on a dev PC |
| 4 | **Additional registers**: pairing and the first copy of data are done by the app's Installation Setup and the Main POS's API (`sync/api`). Still missing: a Windows Firewall rule so other PCs can reach the Main POS's API port (MySQL itself is never opened to the network) | Not tested with the installed app |
| 5 | Polish: clean-VM testing (Windows 10 and 11), wizard branding, code signing | To do |

Known limitation: printer and POS settings are saved per **Windows user**. If cashiers sign in
to Windows with separate accounts, each one has to choose the printer again.
