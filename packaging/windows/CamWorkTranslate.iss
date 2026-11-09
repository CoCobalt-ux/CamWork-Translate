#ifndef AppVersion
  #define AppVersion "1.2.8"
#endif

#ifndef PackageVersion
  #define PackageVersion "1.2.8"
#endif

#ifndef SourceDir
  #error SourceDir не задан
#endif

#ifndef OutputDir
  #error OutputDir не задан
#endif

#ifndef RepoRoot
  #error RepoRoot не задан
#endif

#define AppName "CamWork Translate"
#define AppPublisher "ITWORKSYSTEMS LTD / CamWork"
#define AppExeName "CamWork Translate.exe"
#define AppUrl "https://camwork.club"

[Setup]
AppId={{F90B0496-B038-4066-9C57-A4F9E07CCE14}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppUrl}
AppSupportURL={#AppUrl}
AppUpdatesURL={#AppUrl}
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
AllowNoIcons=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.10240
OutputDir={#OutputDir}
OutputBaseFilename=CamWork-Translate-{#AppVersion}-Setup-windows-x64
SetupIconFile={#RepoRoot}\ui-swing\src\main\resources\icons\app\icon.ico
UninstallDisplayIcon={app}\{#AppExeName}
UninstallDisplayName={#AppName} {#AppVersion}
LicenseFile={#RepoRoot}\LICENSE
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
UsePreviousAppDir=yes
ChangesAssociations=no
ChangesEnvironment=no
VersionInfoVersion={#PackageVersion}
VersionInfoCompany={#AppPublisher}
VersionInfoDescription=Установщик CamWork Translate
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#PackageVersion}
VersionInfoCopyright=Copyright (c) 2026 ITWORKSYSTEMS LTD / CamWork - modifications
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "ukrainian"; MessagesFile: "compiler:Languages\Ukrainian.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Дополнительные ярлыки:"; Flags: unchecked
Name: "startupicon"; Description: "Запускать {#AppName} автоматически при входе в Windows"; GroupDescription: "Автозапуск:"

[InstallDelete]
; Обновляются только внутренние каталоги программы. Пользовательские настройки, история,
; журналы и дополнительные плагины в корне установки сохраняются.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

; HKCU, не HKLM — установщик работает без прав администратора (PrivilegesRequired=lowest),
; и автозапуск должен включаться только для того пользователя Windows, который ставил
; программу. Удаление выполняет код ниже с проверкой владельца: другая переносимая копия
; могла позднее записать своё значение с тем же именем.
[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "{#AppName}"; ValueData: """{app}\{#AppExeName}"""; Tasks: startupicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "Запустить {#AppName}"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent

[Code]
const
  StartupRunKey = 'Software\Microsoft\Windows\CurrentVersion\Run';

var
  ForeignStartupSaved: Boolean;
  ForeignStartupCommand: String;
  ForeignStartupType: Cardinal;

function OpenStartupUserKey(DesiredAccess: Cardinal; var Key: THandle): LongInt;
  external 'RegOpenCurrentUser@advapi32.dll stdcall';
function QueryStartupValueType(Key: THandle; SubKey, ValueName: String;
  Flags: Cardinal; var ValueType: Cardinal; Data: THandle; var DataSize: Cardinal): LongInt;
  external 'RegGetValueW@advapi32.dll stdcall';
function CloseStartupUserKey(Key: THandle): LongInt;
  external 'RegCloseKey@advapi32.dll stdcall';

function StartupValueType(var ValueType: Cardinal): Boolean;
var
  Key: THandle;
  DataSize: Cardinal;
begin
  Result := False;
  if OpenStartupUserKey($0001, Key) <> 0 then
    Exit;
  try
    DataSize := 0;
    { RRF_NOEXPAND: сохраняем исходный тип, не разворачивая переменные окружения. }
    Result := (QueryStartupValueType(Key, StartupRunKey, '{#AppName}',
      $1000FFFF, ValueType, 0, DataSize) = 0) and ((ValueType = 1) or (ValueType = 2));
  finally
    CloseStartupUserKey(Key);
  end;
end;

function IsOwnStartupCommand(const Command: String): Boolean;
var
  Launcher: String;
begin
  Launcher := ExpandConstant('{app}\{#AppExeName}');
  Result := (CompareText(Trim(Command), '"' + Launcher + '"') = 0) or
    (CompareText(Trim(Command), Launcher) = 0);
end;

procedure RemoveOwnStartupEntry;
var
  Command: String;
begin
  if RegQueryStringValue(HKCU, StartupRunKey, '{#AppName}', Command) then
    if IsOwnStartupCommand(Command) then
      if not RegDeleteValue(HKCU, StartupRunKey, '{#AppName}') then
        Log('Не удалось удалить собственную запись автозапуска.');
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and not WizardIsTaskSelected('startupicon') then
    RemoveOwnStartupEntry;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Command: String;
  Restored: Boolean;
begin
  if CurUninstallStep = usUninstall then begin
    ForeignStartupSaved := False;
    if RegQueryStringValue(HKCU, StartupRunKey, '{#AppName}', Command) then begin
      if IsOwnStartupCommand(Command) then
        RemoveOwnStartupEntry
      else if StartupValueType(ForeignStartupType) then begin
        ForeignStartupCommand := Command;
        ForeignStartupSaved := True;
      end;
    end;
  end else if (CurUninstallStep = usPostUninstall) and ForeignStartupSaved then begin
    { Старые версии добавляли uninsdeletevalue в накопленный uninstall log.
      После их очистки возвращаем только исчезнувшее чужое значение, с прежним типом. }
    if not RegValueExists(HKCU, StartupRunKey, '{#AppName}') then begin
      if ForeignStartupType = 2 then
        Restored := RegWriteExpandStringValue(HKCU, StartupRunKey, '{#AppName}', ForeignStartupCommand)
      else
        Restored := RegWriteStringValue(HKCU, StartupRunKey, '{#AppName}', ForeignStartupCommand);
      if not Restored then
        Log('Не удалось восстановить запись автозапуска другой копии.');
    end;
  end;
end;
