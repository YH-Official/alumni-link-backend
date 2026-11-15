@echo off
setlocal enableextensions

rem One-shot launcher for THIS portable bundle (alumni-link backend + MariaDB + JDK).
rem Everything is resolved from the folder this script lives in, so the bundle can be
rem copied anywhere and still start. Nothing is installed as a Windows service.
rem HTTP is 7031 and the DB port is 3307: the DB is this bundle's own, so it never shares
rem 3306 with F:\XYL. Only one backend can own 7031 at a time - stop F:\XYL before starting.

set "ROOT=%~dp0"
set "DB_BIN=%ROOT%runtime\mariadb\bin"
set "DB_BASE=%ROOT%runtime\mariadb"
set "DB_INI=%ROOT%runtime\dbdata\my.ini"
set "DB_DATA=%ROOT%runtime\dbdata\data"
set "JAVA=%ROOT%runtime\jdk21\bin\java.exe"
set "JAR=%ROOT%build\libs\alumni-link-1.0.0-SNAPSHOT-fat.jar"
set "DBPORT=3307"
set "APPPORT=7031"
set "DBURL=jdbc:mariadb://127.0.0.1:3307/alumin-link?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8"
set "DB_URL=%DBURL%"
set "tries=0"

if not exist "%DB_BIN%\mariadbd.exe" (
    echo [db]   FAILED - %DB_BIN%\mariadbd.exe is missing, this is not a complete bundle.
    exit /b 1
)
if not exist "%JAVA%" (
    echo [env]  FAILED - %JAVA% is missing, this is not a complete bundle.
    exit /b 1
)

rem ---------------- 1. bundled MariaDB on %DBPORT% ----------------
call :ping_db
if %errorlevel%==0 (
    echo [db]   already running on 127.0.0.1:%DBPORT%
    goto app
)

echo [db]   starting bundled MariaDB...
start "alumni-link-portable MariaDB" /min "%DB_BIN%\mariadbd.exe" --defaults-file="%DB_INI%" --console --basedir="%DB_BASE%" --datadir="%DB_DATA%" --port=%DBPORT% --plugin-dir="%DB_BASE%\lib\plugin"

:wait_db
call :ping_db
if %errorlevel%==0 (
    echo [db]   ready on 127.0.0.1:%DBPORT%
    goto app
)
set /a tries+=1
if %tries% geq 30 (
    echo [db]   FAILED - server did not answer within 30s.
    echo [db]   The minimized "alumni-link-portable MariaDB" window holds the startup log.
    echo [db]   Then run stop.cmd and retry.
    exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto wait_db

rem ---------------- 2. alumni-link on %APPPORT% ----------------
:app
set "tries=0"
call :port_up %APPPORT%
if %errorlevel%==0 (
    echo [app]  already running on http://127.0.0.1:%APPPORT% - left untouched.
    goto done
)

if not exist "%JAR%" (
    echo [app]  FAILED - jar not found: %JAR%
    echo [app]  Build it in this folder:  cd /d "%ROOT%." ^&^& gradlew bootJar
    exit /b 1
)

echo [app]  starting alumni-link, log window opens separately...
rem CWD must be the bundle root: the app writes work\files and reads application.yaml relative to it.
cd /d "%ROOT%."
start "alumni-link-portable" "%JAVA%" ^
  "-Dserver.port=%APPPORT%" ^
  "-Ddatasource.db.url=%DBURL%" ^
  -Debean.ddl.run=false -Debean.ddl.generate=false ^
  -Debean.db.ddl.run=false -Debean.db.ddl.generate=false ^
  "-Dcors.allowed-origins=http://localhost:%APPPORT%,http://127.0.0.1:%APPPORT%,http://localhost:8080,http://127.0.0.1:8080" ^
  -jar "%JAR%"

:wait_app
call :port_up %APPPORT%
if %errorlevel%==0 (
    echo [app]  ready on http://127.0.0.1:%APPPORT%  - main window should be visible.
    echo [app]  close the main window to hide it to the tray; quit from the tray icon.
    echo [app]  stop everything with stop.cmd in this folder.
    goto done
)
set /a tries+=1
if %tries% geq 90 (
    echo [app]  FAILED - port %APPPORT% never opened. Look at the alumni-link-portable console window.
    exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto wait_app

rem ---------------- helpers ----------------
:ping_db
"%DB_BIN%\mariadb-admin.exe" -h 127.0.0.1 -P %DBPORT% -uroot -p123456 ping 2>nul | findstr /c:"alive" >nul
exit /b %errorlevel%

:port_up
netstat -ano | findstr /r ":%1[^0-9]" | findstr /c:"LISTENING" >nul
exit /b %errorlevel%

:done
endlocal
