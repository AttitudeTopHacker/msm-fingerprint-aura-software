; Script generated for AuraLock Windows Installer (msm.movin)
; Builds: auralock_1.0.0_x64-setup.exe

#define MyAppName "AuraLock"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "msm.movin"
#define MyAppURL "https://github.com/AttitudeTopHacker/msm-fingerprint-aura-software"
#define MyAppExeName "auralock.exe"

[Setup]
AppId={{D68F2314-5A1D-4A2E-8B73-B5DF8E5D7A91}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=..\dist
OutputBaseFilename=auralock_1.0.0_x64-setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "autostart"; Description: "Start AuraLock automatically on Windows login"; GroupDescription: "Startup Options:"

[Files]
Source: "..\target\release\auralock.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\target\release\auralock_cp.dll"; DestDir: "{sys}"; Flags: ignoreversion promptifolder; Check: IsWin64
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName} Pair Phone"; Filename: "{app}\{#MyAppExeName}"; Parameters: "pair"
Name: "{group}\{#MyAppName} Daemon"; Filename: "{app}\{#MyAppExeName}"; Parameters: "daemon"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName} Setup"; Filename: "{app}\{#MyAppExeName}"; Parameters: "pair"; Tasks: desktopicon

[Registry]
; Windows Credential Provider COM Registration
Root: HKLM; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{{A7D8F910-2B3C-4D5E-8F01-23456789ABCD}"; ValueType: string; ValueName: ""; ValueData: "AuraLock Credential Provider"; Flags: uninsdeletekey
Root: HKLM; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "AuraLockDaemon"; ValueData: """{app}\{#MyAppExeName}"" daemon"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExeName}"; Parameters: "pair"; Description: "Launch AuraLock Phone Pairing"; Flags: nowait postinstall skipifsilent
