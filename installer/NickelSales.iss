; =====================================================================
;  Nickel Sales Center - Windows installer (Inno Setup 6)
;
;  Don't compile this directly: build-installer.ps1 builds the app and
;  MySQL folders first, then runs ISCC with AppVersion, BuildDir, OutputDir and SchemaFile.
;
;  The wizard asks the questions; the work after copying the files
;  (MySQL service, database, settings file, first admin) is done by
;  install.InstallTasks in the app itself, with the app's own Java.
;  Running setup again (an update, or after a failure) keeps everything
;  and only finishes what is missing.
; =====================================================================

#ifndef AppVersion
  #error Build with installer\build-installer.ps1
#endif

#define AppName   "Nickel Sales Center"
#define AppExe    "NickelSales.exe"
#define DbService "NickelSalesDB"
#define DbPort    "3307"
; AppGuid must never change: it is how Windows and later versions recognise this app
#define AppGuid   "8C4F2E1A-6B3D-4A7E-9F21-5D0C3B8A7E64"

[Setup]
AppId={{{#AppGuid}}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Nickel Sales Center
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\Nickel Sales Center
DefaultGroupName=Nickel Sales Center
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
WizardStyle=modern
SetupIconFile={#BuildDir}\NickelSales.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
OutputDir={#OutputDir}
OutputBaseFilename=NickelSalesSetup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
CloseApplications=yes
RestartApplications=no
SetupLogging=yes

[Tasks]
Name: desktopicon; Description: "Put a shortcut on the desktop"

[Files]
Source: "{#BuildDir}\app\NickelSales\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#BuildDir}\mysql\*"; DestDir: "{app}\mysql"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SchemaFile}"; DestDir: "{app}\database"; Flags: ignoreversion
Source: "{#BuildDir}\vc_redist.x64.exe"; Flags: dontcopy
; the new version's code, for the backup before an update (run before the old files are replaced)
Source: "{#BuildDir}\app\NickelSales\app\*.jar"; DestDir: "{tmp}\newapp"; Flags: dontcopy

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
Name: "{autoprograms}\{#AppName} support\Log files"; Filename: "{commonappdata}\NickelSales\logs"
Name: "{autoprograms}\{#AppName} support\Backups made before updates"; Filename: "{commonappdata}\NickelSales\backups"
Name: "{autoprograms}\{#AppName} support\{#AppName} (with console)"; Filename: "{app}\NickelSales-Console.exe"

[Run]
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName}"; Flags: nowait postinstall skipifsilent; Check: DatabaseReady

[UninstallRun]
Filename: "{sys}\net.exe"; Parameters: "stop {#DbService}"; Flags: runhidden waituntilterminated; RunOnceId: "StopDatabase"
Filename: "{app}\mysql\bin\mysqld.exe"; Parameters: "--remove {#DbService}"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveDatabase"

[Code]
var
  TypePage: TInputOptionWizardPage;
  StorePage: TInputQueryWizardPage;
  PaperPage: TInputOptionWizardPage;
  TerminalPage: TInputQueryWizardPage;
  AdminPage: TInputQueryWizardPage;
  IsUpdate: Boolean;          { the store's database is already here: no questions, keep everything }
  SetupFailed: Boolean;
  AdminLogin: String;
  OldVersion: String;        { the version installed now, '' if none }
  UpdateBackup: String;      { the backup made before this update }
  BackupAfterCopy: Boolean;  { an update over data whose program was removed: InstallTasks backs up }

function DataDir: String;
begin
  Result := ExpandConstant('{commonappdata}\NickelSales');
end;

function DatabaseReady: Boolean;
begin
  Result := not SetupFailed;
end;

{ ------------------------------------------------------------- versions }

{ The version installed now, from the entry Windows keeps under Apps; '' if none. }
function InstalledVersion: String;
begin
  if not RegQueryStringValue(HKLM64, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{' + '{#AppGuid}' + '}_is1',
                             'DisplayVersion', Result) then
    Result := '';
end;

{ Takes the first number off a MAJOR.MINOR.PATCH string. }
function NextVersionPart(var S: String): Integer;
var
  P: Integer;
begin
  P := Pos('.', S);
  if P = 0 then begin
    Result := StrToIntDef(S, 0);
    S := '';
  end else begin
    Result := StrToIntDef(Copy(S, 1, P - 1), 0);
    Delete(S, 1, P);
  end;
end;

{ -1, 0 or 1: A is older than, the same as, or newer than B }
function CompareVersions(A, B: String): Integer;
var
  I, X, Y: Integer;
begin
  Result := 0;
  for I := 1 to 3 do
    if Result = 0 then begin
      X := NextVersionPart(A);
      Y := NextVersionPart(B);
      if X < Y then Result := -1
      else if X > Y then Result := 1;
    end;
end;

function InitializeSetup: Boolean;
begin
  Result := True;
  IsUpdate := FileExists(DataDir + '\nickelsales.properties') and DirExists(DataDir + '\mysql\data');
  OldVersion := InstalledVersion;
  { Going back to an older version could break the data: its code doesn't know the newer tables. }
  if (OldVersion <> '') and (CompareVersions(OldVersion, '{#AppVersion}') > 0) then begin
    SuppressibleMsgBox('A newer version of {#AppName} (' + OldVersion + ') is already installed on this PC. ' +
      'This setup is the older version {#AppVersion}: installing it could damage the store''s data, so setup ' +
      'will close.' + #13#10#13#10 + 'Use the setup for version ' + OldVersion + ' or newer.',
      mbCriticalError, MB_OK, IDOK);
    Result := False;
  end;
end;

{ ---------------------------------------------------------------- pages }

procedure InitializeWizard;
begin
  TypePage := CreateInputOptionPage(wpWelcome, 'Setup type', 'How will this computer be used?',
    'Choose one, then click Next.', True, False);
  TypePage.Add('Main PC: keeps the store''s database and is also a cash register');
  TypePage.Add('Additional register: connects to the main PC (coming in a later version)');
  TypePage.Values[0] := True;
  TypePage.CheckListBox.ItemEnabled[1] := False;

  StorePage := CreateInputQueryPage(wpSelectDir, 'Store details', 'Printed at the top of every receipt.',
    'Leave a box empty to leave that line off the receipt. You can change these later with support''s help.');
  StorePage.Add('Store name:', False);
  StorePage.Add('Address:', False);
  StorePage.Add('Proprietor:', False);
  StorePage.Add('TIN:', False);
  StorePage.Add('Phone:', False);
  StorePage.Values[0] := 'NICKEL SALES CENTER';
  StorePage.Values[1] := 'San Nicolas St Brgy Washington POB. 8400 Surigao City Surigao del Norte Philippines';
  StorePage.Values[2] := 'JERICK CHUA CUBILLAN';
  StorePage.Values[3] := '178-188-702-00000';

  PaperPage := CreateInputOptionPage(StorePage.ID, 'Receipt printer', 'What paper does the receipt printer use?',
    'Look at the paper roll or the printer''s label.', True, False);
  PaperPage.Add('58 mm (most small receipt printers)');
  PaperPage.Add('80 mm');
  PaperPage.Values[0] := True;

  TerminalPage := CreateInputQueryPage(PaperPage.ID, 'This register', 'A short code for this computer.',
    'Every invoice number from this register starts with it (for example T01-2026-000123), so each register ' +
    'needs its own. The main PC is usually T01. Use 1 to 8 letters, digits or dashes.');
  TerminalPage.Add('Register code:', False);
  TerminalPage.Values[0] := 'T01';

  AdminPage := CreateInputQueryPage(TerminalPage.ID, 'Administrator account', 'The first person who can sign in.',
    'This account adds cashiers and other users later. The login name is made from the names ' +
    '(Juan Dela Cruz signs in as JDelaCruz); setup shows it at the end.' + #13#10 +
    'Password: 8 to 72 characters, with an uppercase letter and a number.');
  AdminPage.Add('First name:', False);
  AdminPage.Add('Last name:', False);
  AdminPage.Add('Password:', True);
  AdminPage.Add('Type the password again:', True);
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := IsUpdate and ((PageID = TypePage.ID) or (PageID = StorePage.ID) or (PageID = PaperPage.ID)
    or (PageID = TerminalPage.ID) or (PageID = AdminPage.ID));
end;

function HasLetterOrDigit(S: String): Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to Length(S) do
    if ((S[I] >= 'A') and (S[I] <= 'Z')) or ((S[I] >= 'a') and (S[I] <= 'z'))
       or ((S[I] >= '0') and (S[I] <= '9')) or (Ord(S[I]) > 127) then
      Result := True;
end;

function ValidTerminalId(S: String): Boolean;
var
  I: Integer;
begin
  Result := (Length(S) >= 1) and (Length(S) <= 8);
  for I := 1 to Length(S) do
    if not (((S[I] >= 'A') and (S[I] <= 'Z')) or ((S[I] >= '0') and (S[I] <= '9')) or (S[I] = '-')) then
      Result := False;
end;

{ The app's own rule (auth.account.PassPolicy): 8-72 characters, an uppercase letter and a digit }
function ValidPassword(S: String): Boolean;
var
  I: Integer;
  Upper, Digit: Boolean;
begin
  Upper := False;
  Digit := False;
  for I := 1 to Length(S) do begin
    if (S[I] >= 'A') and (S[I] <= 'Z') then Upper := True;
    if (S[I] >= '0') and (S[I] <= '9') then Digit := True;
  end;
  Result := (Length(S) >= 8) and (Length(S) <= 72) and Upper and Digit;
end;

function Refuse(Text: String): Boolean;
begin
  MsgBox(Text, mbError, MB_OK);
  Result := False;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = StorePage.ID then begin
    if Trim(StorePage.Values[0]) = '' then
      Result := Refuse('Enter the store name.');
  end else if CurPageID = TerminalPage.ID then begin
    TerminalPage.Values[0] := Uppercase(Trim(TerminalPage.Values[0]));
    if not ValidTerminalId(TerminalPage.Values[0]) then
      Result := Refuse('The register code must be 1 to 8 letters, digits or dashes, for example T01.');
  end else if CurPageID = AdminPage.ID then begin
    if (Trim(AdminPage.Values[0]) = '') or (Trim(AdminPage.Values[1]) = '') then
      Result := Refuse('Enter the administrator''s first and last name.')
    else if (Length(Trim(AdminPage.Values[0])) > 30) or (Length(Trim(AdminPage.Values[1])) > 30) then
      Result := Refuse('Names can be at most 30 characters.')
    else if not HasLetterOrDigit(AdminPage.Values[1]) then
      Result := Refuse('The last name must contain letters.')
    else if Trim(AdminPage.Values[2]) <> AdminPage.Values[2] then
      Result := Refuse('The password can''t start or end with a space.')
    else if not ValidPassword(AdminPage.Values[2]) then
      Result := Refuse('The password must be 8 to 72 characters and include an uppercase letter and a number.')
    else if AdminPage.Values[2] <> AdminPage.Values[3] then
      Result := Refuse('The two passwords are different. Type them again.');
  end;
end;

function PaperWidth: String;
begin
  if PaperPage.Values[1] then Result := '42' else Result := '32';
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo, MemoComponentsInfo,
  MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := MemoDirInfo + NewLine + NewLine;
  if IsUpdate then
    Result := Result + 'Update: the store''s data and settings are kept.' + NewLine + NewLine
  else begin
    Result := Result + 'Main PC (database and cash register)' + NewLine + NewLine +
      'Store:' + NewLine + Space + StorePage.Values[0] + NewLine + Space + StorePage.Values[1] + NewLine + NewLine +
      'Register code: ' + TerminalPage.Values[0] + NewLine +
      'Receipt paper: ' + PaperWidth + ' characters per line' + NewLine + NewLine +
      'Administrator: ' + Trim(AdminPage.Values[0]) + ' ' + Trim(AdminPage.Values[1]) + NewLine + NewLine;
  end;
  Result := Result + 'The database runs as the Windows service {#DbService}, for this PC only.';
  if MemoTasksInfo <> '' then
    Result := Result + NewLine + NewLine + MemoTasksInfo;
end;

{ ------------------------------------------------------------- install }

{ True while NickelSales.exe or NickelSales-Console.exe runs from this install folder. }
function AppRunning: Boolean;
var
  Locator, Wmi, Processes: Variant;
  Folder: String;
begin
  Result := False;
  Folder := ExpandConstant('{app}\');
  StringChangeEx(Folder, '\', '\\', True);   { WQL LIKE needs \ doubled }
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Wmi := Locator.ConnectServer('.', 'root\CIMV2');
    Processes := Wmi.ExecQuery('SELECT ProcessId FROM Win32_Process WHERE (Name = ''{#AppExe}'' OR ' +
      'Name = ''NickelSales-Console.exe'') AND ExecutablePath LIKE ''' + Folder + '%''');
    Result := Processes.Count > 0;
  except
    Result := False;   { WMI unavailable: carry on; files in use are then left for Windows to report }
  end;
end;

{ install.InstallTasks' result file: ADMIN_LOGIN=..., ERROR=... }
function ResultValue(Lines: TArrayOfString; Name: String): String;
var
  I: Integer;
begin
  Result := '';
  for I := 0 to GetArrayLength(Lines) - 1 do
    if Pos(Name + '=', Lines[I]) = 1 then
      Result := Copy(Lines[I], Length(Name) + 2, Length(Lines[I]));
end;
function ServiceExists: Boolean;
var
  Code: Integer;
begin
  Result := Exec(ExpandConstant('{sys}\sc.exe'), 'query {#DbService}', '', SW_HIDE, ewWaitUntilTerminated, Code)
    and (Code = 0);
end;

{ Another program (often another MySQL) listens on the database's port. }
function PortInUse: Boolean;
var
  Code: Integer;
begin
  Result := Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -NonInteractive -Command "if (Get-NetTCPConnection -LocalPort {#DbPort} -State Listen ' +
    '-ErrorAction SilentlyContinue) { exit 1 }"', '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 1);
end;

{ Backs the database up with the NEW version's code on the Java already installed, before any
  file is replaced. '' when done (UpdateBackup = the file), else why not. }
function BackUpBeforeUpdate: String;
var
  Code: Integer;
  Java, ResultFile, Error: String;
  Lines: TArrayOfString;
  Progress: TOutputProgressWizardPage;
begin
  Result := '';
  Java := ExpandConstant('{app}\runtime\bin\java.exe');
  Progress := CreateOutputProgressPage('Backing up', 'Backing up the store''s data before updating.');
  Progress.SetText('This takes a minute. Nothing has been changed yet.', '');
  Progress.ProgressBar.Style := npbstMarquee;
  Progress.Show;
  try
    ExtractTemporaryFiles('{tmp}\newapp\*.jar');
    ResultFile := ExpandConstant('{tmp}\backup-result.properties');
    if not Exec(Java, '-cp "' + ExpandConstant('{tmp}\newapp\*') + '" install.InstallTasks backup "' + DataDir +
                '" "' + OldVersion + '" "{#AppVersion}" "' + ResultFile + '"', '', SW_HIDE, ewWaitUntilTerminated, Code) then
      Code := -1;
    if not LoadStringsFromFile(ResultFile, Lines) then
      SetArrayLength(Lines, 0);
    UpdateBackup := ResultValue(Lines, 'BACKUP_FILE');
    if Code <> 0 then begin
      Error := ResultValue(Lines, 'ERROR');
      if Error = '' then Error := 'Unexpected error (code ' + IntToStr(Code) + ').';
      Result := 'Setup could not back up the store''s data, so it stopped without changing anything.' + #13#10#13#10 +
        Error + #13#10#13#10 + 'Details: ' + DataDir + '\logs\install.log';
    end;
  finally
    Progress.Hide;
  end;
end;

{ Before any file is copied:
  - the app must be closed (Windows can't reliably close a Java app for us);
  - a new install needs the database's port free (checked now, so nothing is half-installed);
  - an update first backs the data up, then stops the database so its files can be replaced. }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Result := '';
  while (Result = '') and AppRunning do
    if WizardSilent or (MsgBox('{#AppName} is open on this PC.' + #13#10#13#10 +
        'Close it (finish any sale first), then click OK. Click Cancel to stop setup.',
        mbError, MB_OKCANCEL) <> IDOK) then
      Result := 'Close {#AppName}, then run setup again.';
  if Result <> '' then
    Exit;

  if not ServiceExists and PortInUse then begin
    Result := 'Another program on this PC already uses port {#DbPort}, which {#AppName}''s database needs. ' +
      'Nothing was installed.' + #13#10#13#10 + 'Close or remove that program (often another MySQL server), ' +
      'or ask support, then run setup again.';
    Exit;
  end;

  if IsUpdate then begin
    if FileExists(ExpandConstant('{app}\runtime\bin\java.exe')) then
      Result := BackUpBeforeUpdate
    else
      BackupAfterCopy := True;   { the program was removed but the data kept: InstallTasks backs up first }
  end;
  if Result = '' then
    Exec(ExpandConstant('{sys}\net.exe'), 'stop {#DbService}', '', SW_HIDE, ewWaitUntilTerminated, Code);
end;

function VcRuntimeInstalled: Boolean;
var
  Installed, Build: Cardinal;
begin
  Result := RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Installed', Installed)
    and (Installed = 1)
    and RegQueryDWordValue(HKLM64, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Bld', Build)
    and (Build >= 35211);
end;

function Prop(Name, Value: String): String;
begin
  StringChangeEx(Value, '\', '\\', True);   { properties files treat \ as an escape }
  Result := Name + '=' + Trim(Value);
end;

procedure WriteAnswers(FileName: String);
var
  Lines: TArrayOfString;
begin
  if IsUpdate then begin
    SetArrayLength(Lines, 0);
    if BackupAfterCopy then begin
      SetArrayLength(Lines, 1);
      Lines[0] := Prop('BACKUP_FIRST', '{#AppVersion}');
    end;
  end else begin
    SetArrayLength(Lines, 10);
    Lines[0] := Prop('NSC_TERMINAL_ID', TerminalPage.Values[0]);
    Lines[1] := Prop('NSC_STORE_NAME', StorePage.Values[0]);
    Lines[2] := Prop('NSC_STORE_ADDRESS', StorePage.Values[1]);
    Lines[3] := Prop('NSC_STORE_PROPRIETOR', StorePage.Values[2]);
    Lines[4] := Prop('NSC_STORE_TIN', StorePage.Values[3]);
    Lines[5] := Prop('NSC_STORE_PHONE', StorePage.Values[4]);
    Lines[6] := Prop('NSC_RECEIPT_WIDTH', PaperWidth);
    Lines[7] := Prop('ADMIN_FIRST_NAME', AdminPage.Values[0]);
    Lines[8] := Prop('ADMIN_LAST_NAME', AdminPage.Values[1]);
    Lines[9] := Prop('ADMIN_PASSWORD', AdminPage.Values[2]);
  end;
  SaveStringsToUTF8File(FileName, Lines, False);
end;


procedure SetUpDatabase;
var
  Code: Integer;
  Answers, ResultFile, Error: String;
  Lines: TArrayOfString;
begin
  if not VcRuntimeInstalled then begin
    WizardForm.StatusLabel.Caption := 'Installing the Microsoft Visual C++ runtime...';
    ExtractTemporaryFile('vc_redist.x64.exe');
    if not Exec(ExpandConstant('{tmp}\vc_redist.x64.exe'), '/install /quiet /norestart', '', SW_HIDE,
                ewWaitUntilTerminated, Code) or not ((Code = 0) or (Code = 3010) or (Code = 1638)) then begin
      SetupFailed := True;
      SuppressibleMsgBox('The Microsoft Visual C++ runtime could not be installed (code ' + IntToStr(Code) + '). ' +
        'Install it from Microsoft (vc_redist.x64.exe), then run setup again.', mbCriticalError, MB_OK, IDOK);
      Exit;
    end;
  end;

  WizardForm.StatusLabel.Caption := 'Setting up the database. This takes a minute...';
  WizardForm.ProgressGauge.Style := npbstMarquee;
  Answers := ExpandConstant('{tmp}\answers.properties');
  ResultFile := ExpandConstant('{tmp}\result.properties');
  WriteAnswers(Answers);
  Exec(ExpandConstant('{app}\runtime\bin\java.exe'),
    '-cp "' + ExpandConstant('{app}\app\*') + '" install.InstallTasks setup "' + ExpandConstant('{app}') + '" "' +
      DataDir + '" "' + Answers + '" "' + ResultFile + '"',
    '', SW_HIDE, ewWaitUntilTerminated, Code);
  DeleteFile(Answers);   { it holds the admin's password }
  WizardForm.ProgressGauge.Style := npbstNormal;

  if not LoadStringsFromFile(ResultFile, Lines) then
    SetArrayLength(Lines, 0);
  AdminLogin := ResultValue(Lines, 'ADMIN_LOGIN');
  if ResultValue(Lines, 'BACKUP_FILE') <> '' then
    UpdateBackup := ResultValue(Lines, 'BACKUP_FILE');
  if Code <> 0 then begin
    SetupFailed := True;
    Error := ResultValue(Lines, 'ERROR');
    if Error = '' then Error := 'Unexpected error (code ' + IntToStr(Code) + ').';
    SuppressibleMsgBox('Setup copied the program but could not finish setting up the database:' + #13#10#13#10 +
      Error + #13#10#13#10 + 'Details: ' + DataDir + '\logs\install.log' + #13#10 +
      'Fix the problem, then run setup again: it carries on where it stopped.', mbCriticalError, MB_OK, IDOK);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    SetUpDatabase;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpFinished then begin
    if SetupFailed then
      WizardForm.FinishedLabel.Caption := '{#AppName} was installed, but its database is not ready yet. ' +
        'Run setup again after fixing the problem it reported. Details: ' + DataDir + '\logs\install.log'
    else if AdminLogin <> '' then
      WizardForm.FinishedLabel.Caption := '{#AppName} is ready.' + #13#10#13#10 +
        'Sign in as ' + AdminLogin + ' with the password you chose. Write the login name down.' + #13#10#13#10 +
        'Add cashiers and other users from Manage users.'
    else
    begin
      WizardForm.FinishedLabel.Caption := '{#AppName} is ready. The store''s data and settings were kept.';
      if UpdateBackup <> '' then
        WizardForm.FinishedLabel.Caption := WizardForm.FinishedLabel.Caption + #13#10#13#10 +
          'A backup from before the update is in ' + UpdateBackup + '. An administrator can restore it ' +
          'from Settings > Backup & Restore if something is wrong.';
    end;
  end;
end;

{ ----------------------------------------------------------- uninstall }

{ The uninstaller can't delete the program while it runs: ask for it to be closed first. }
function InitializeUninstall: Boolean;
begin
  Result := True;
  while Result and AppRunning do
    if UninstallSilent then
      Result := False
    else
      Result := MsgBox('{#AppName} is still open on this PC.' + #13#10#13#10 +
        'Close it (finish any sale first), then click OK. Click Cancel to stop the uninstall.',
        mbError, MB_OKCANCEL) = IDOK;
end;

{ Comes right after Windows' own "Are you sure...?" Yes/No, so it uses labelled buttons rather than a
  second Yes/No that is easy to answer by habit. Keeping the data is the first, default button. }
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if (CurUninstallStep = usPostUninstall) and DirExists(DataDir) and not UninstallSilent then
    if TaskDialogMsgBox('Keep the store''s data?',
         'The program is removed. The store''s sales, products, users, settings and logs are still on this PC, ' +
         'in ' + DataDir + '. Installing again later picks them back up.' + #13#10#13#10 +
         'Deleting them cannot be undone.',
         mbConfirmation, MB_YESNO, ['Keep the store''s data', 'Delete all store data'], 0) = IDNO then
      DelTree(DataDir, True, True, True);
end;
