@echo off
rem Opens The Linked Desk. Requires Java 21 or newer (https://adoptium.net).
rem Uses the newest LinkedDesk-<version>.jar in this folder (by date), so an update can simply be dropped in.
set JAR=
for /f "delims=" %%f in ('dir /b /o:d "%~dp0LinkedDesk*.jar" 2^>nul') do set JAR=%%f
if "%JAR%"=="" (
  echo No LinkedDesk jar found next to this file.
  pause
  exit /b 1
)
start "" javaw -jar "%~dp0%JAR%"
