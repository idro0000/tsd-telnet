@echo off
rem Runs the telnet / VT100 logic on a PC: no data terminal device needed.
rem Compiles the Android-free part of the app together with a stub of android.view.KeyEvent.
setlocal

set "PROJ=%~dp0..\.."
set "APP=%PROJ%\app\src\main\java\com\example\helloworld"
set "OUT=%TEMP%\tsd-selftest"
set "JAVABIN=C:\Program Files\Android\Android Studio\jbr\bin"
if not exist "%JAVABIN%\javac.exe" set "JAVABIN=C:\Program Files\Java\latest\bin"
if not exist "%JAVABIN%\javac.exe" goto nojava

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%\classes"
mkdir "%OUT%\src\com\example\helloworld"
mkdir "%OUT%\src\android\view"

copy /y "%APP%\TerminalScreen.java" "%OUT%\src\com\example\helloworld\" >nul
copy /y "%APP%\AnsiParser.java" "%OUT%\src\com\example\helloworld\" >nul
copy /y "%APP%\CharsetKit.java" "%OUT%\src\com\example\helloworld\" >nul
copy /y "%APP%\TelnetConfig.java" "%OUT%\src\com\example\helloworld\" >nul
copy /y "%APP%\KeyMapper.java" "%OUT%\src\com\example\helloworld\" >nul
copy /y "%APP%\TelnetClient.java" "%OUT%\src\com\example\helloworld\" >nul
copy /y "%APP%\LogKit.java" "%OUT%\src\com\example\helloworld\" >nul
copy /y "%~dp0src\com\example\helloworld\Harness.java" "%OUT%\src\com\example\helloworld\" >nul
copy /y "%~dp0src\android\view\KeyEvent.java" "%OUT%\src\android\view\" >nul

dir /s /b "%OUT%\src\*.java" > "%OUT%\files.txt"
"%JAVABIN%\javac.exe" -encoding UTF-8 -nowarn -d "%OUT%\classes" "@%OUT%\files.txt"
if errorlevel 1 goto failed

echo.
"%JAVABIN%\java.exe" "-Dfile.encoding=UTF-8" -cp "%OUT%\classes" com.example.helloworld.Harness "%PROJ%"
goto end

:nojava
echo javac not found. Point JAVABIN in this file to your JDK bin folder.
goto end

:failed
echo Compilation failed.

:end
endlocal
