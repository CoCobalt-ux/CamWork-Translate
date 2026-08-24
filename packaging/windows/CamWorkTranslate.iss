#ifndef AppVersion
  #define AppVersion "1.2.0"
#endif

#ifndef PackageVersion
  #define PackageVersion "1.2.0"
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

[Run]
Filename: "{app}\{#AppExeName}"; Description: "Запустить {#AppName}"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent
