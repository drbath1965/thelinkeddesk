@echo off
rem Builds LinkedDesk-<version>.jar and runs the tests, with only the JDK (Java 21+). No downloads needed.
rem The version comes from AppPaths.VERSION (src\com\linkeddesk\app\AppPaths.java).
cd /d "%~dp0"
for /f tokens^=2^ delims^=^" %%v in ('findstr /c:"String VERSION = " src\com\linkeddesk\app\AppPaths.java') do set VER=%%v
set JAR=LinkedDesk-%VER%.jar
if exist out rmdir /s /q out
if exist out-test rmdir /s /q out-test
mkdir out
mkdir out-test
dir /s /b src\*.java > out\sources.txt
javac --release 21 -encoding UTF-8 -d out @out\sources.txt || exit /b 1
xcopy /s /q /y src\*.tsv out\ >nul || exit /b 1
> out\MANIFEST.MF echo Main-Class: com.linkeddesk.app.App
>> out\MANIFEST.MF echo Implementation-Version: %VER%
if exist LinkedDesk.jar del LinkedDesk.jar
jar --create --file %JAR% --manifest out\MANIFEST.MF -C out com || exit /b 1
echo Built %JAR%
if "%1"=="--skip-tests" goto :eof
dir /s /b test\*.java > out-test\sources.txt
javac --release 21 -encoding UTF-8 -cp out -d out-test @out-test\sources.txt || exit /b 1
java -cp out;out-test com.linkeddesk.TestMain || exit /b 1
