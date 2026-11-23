@echo off
rem ---------------------------------------------------------------------------
rem  Builds The Linked Desk for Windows WITH Java included, so it runs on a PC
rem  that has no Java installed. Needs the JDK 21+ (for jpackage) on THIS PC only.
rem
rem  - If the WiX Toolset 3.x is installed (candle.exe on PATH), you get an
rem    installer: dist\The Linked Desk-1.5.0.msi  (Start-menu entry, desktop
rem    shortcut, uninstaller; installs for your user only, no admin rights).
rem  - Otherwise you get a ready-to-run folder, zipped:
rem    dist\TheLinkedDesk-1.5.0-windows.zip  -> unzip, run "The Linked Desk.exe".
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"
call build.bat --skip-tests || exit /b 1
if exist dist rmdir /s /q dist
mkdir dist\input
copy /y LinkedDesk.jar dist\input\ >nul

set MODULES=java.base,java.desktop,java.net.http,java.xml,java.logging,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs
set COMMON=--name "The Linked Desk" --app-version 1.5.0 --vendor "The Linked Desk" --description "World news, downloaded daily and linked" --input dist\input --main-jar LinkedDesk.jar --main-class com.linkeddesk.app.App --java-options -Xmx2g --add-modules %MODULES% --dest dist

where candle >nul 2>nul
if %errorlevel%==0 (
  echo Building the .msi installer...
  jpackage %COMMON% --type msi --win-menu --win-menu-group "The Linked Desk" --win-shortcut --win-dir-chooser --win-per-user-install --win-upgrade-uuid 3b5c1f0e-6d2a-4b8e-9f41-2c7a5e9d1b64 || exit /b 1
  echo Done: dist\The Linked Desk-1.5.0.msi
) else (
  echo WiX Toolset not found, so building a ready-to-run folder instead of an installer.
  jpackage %COMMON% --type app-image || exit /b 1
  powershell -NoProfile -Command "Compress-Archive -Force -Path 'dist\The Linked Desk' -DestinationPath 'dist\TheLinkedDesk-1.5.0-windows.zip'" || exit /b 1
  echo Done: dist\TheLinkedDesk-1.5.0-windows.zip  ^(unzip and run "The Linked Desk.exe"^)
)
endlocal
