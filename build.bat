@echo off
rem Builds LinkedDesk.jar and runs the tests, with only the JDK (Java 21+). No downloads needed.
cd /d "%~dp0"
if exist out rmdir /s /q out
if exist out-test rmdir /s /q out-test
mkdir out
mkdir out-test
dir /s /b src\*.java > out\sources.txt
javac --release 21 -encoding UTF-8 -d out @out\sources.txt || exit /b 1
xcopy /s /q /y src\*.tsv out\ >nul || exit /b 1
echo Main-Class: com.linkeddesk.app.App> out\MANIFEST.MF
jar --create --file LinkedDesk.jar --manifest out\MANIFEST.MF -C out com || exit /b 1
echo Built LinkedDesk.jar
if "%1"=="--skip-tests" goto :eof
dir /s /b test\*.java > out-test\sources.txt
javac --release 21 -encoding UTF-8 -cp out -d out-test @out-test\sources.txt || exit /b 1
java -cp out;out-test com.linkeddesk.TestMain || exit /b 1
