@echo off
setlocal enableextensions

rem One-click stop for this portable bundle (alumni-link backend on 7031 + its own MariaDB on 3307).
rem Order matters: the app first (it holds the DB connections), then MariaDB.
rem
rem Paths come from this script's own folder, so the bundle can live on any drive.
rem The app is identified by the pid that OWNS port 7031, so the Gradle daemon and every
rem other java process are left alone; that pid is additionally checked against this
rem bundle's jar before it is killed.
rem The database is never killed - mariadb-admin shuts it down gracefully so InnoDB flushes.

set "ROOT=%~dp0"
set "DB_BIN=%ROOT%runtime\mariadb\bin"
set "tries=0"

if not exist "%DB_BIN%\mariadb-admin.exe" (
    echo [db]   FAILED - %DB_BIN%\mariadb-admin.exe is missing, this is not a complete bundle.
    exit /b 1
)

rem ---------------- 1. alumni-link on 7031 ----------------
set "APPPID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r ":7031[^0-9]" ^| findstr /c:"LISTENING"') do set "APPPID=%%p"

if not defined APPPID (
    echo [app]  not running.
    goto db
)

rem Refuse to kill somebody else's process that merely happens to hold 7031.
rem The check is done inside PowerShell because the command line contains '&' characters
rem (JDBC url), which cmd would re-parse as a command separator if it were echoed.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + %APPPID%); if ($p.CommandLine -like '*%ROOT%*alumni-link-1.0.0-SNAPSHOT-fat.jar*') { exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 1 (
    echo [app]  REFUSED - pid %APPPID% owns 7031 but is not this bundle's alumni-link, left alone.
    echo [app]  stop it with F:\stop.cmd if it belongs to the other install.
    goto db
)

echo [app]  stopping alumni-link, pid %APPPID% ...
taskkill /PID %APPPID% /F >nul 2>&1
if errorlevel 1 (
    echo [app]  FAILED - taskkill was refused. Quit from the tray icon instead,
    echo [app]  or run this script as the same user that started the app.
    exit /b 1
)

:wait_app
set /a tries+=1
netstat -ano | findstr /r ":7031[^0-9]" | findstr /c:"LISTENING" >nul
if not errorlevel 1 (
    if %tries% geq 15 (
        echo [app]  still holding 7031 after 15s.
        exit /b 1
    )
    ping -n 2 127.0.0.1 >nul
    goto wait_app
)
echo [app]  stopped.

rem ---------------- 2. bundled MariaDB on 3307 ----------------
:db
set "tries=0"
set "DBPID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r ":3307[^0-9]" ^| findstr /c:"LISTENING"') do set "DBPID=%%p"

if not defined DBPID (
    echo [db]   not running.
    goto done
)

echo [db]   shutting down MariaDB, pid %DBPID% ...
"%DB_BIN%\mariadb-admin.exe" -h 127.0.0.1 -P 3307 -uroot -p123456 shutdown >nul 2>&1
if errorlevel 1 (
    echo [db]   graceful shutdown failed - server left running, nothing was killed.
    echo [db]   Wrong password in runtime\dbdata, or a remote instance on 3307?
    echo [db]   Do not taskkill mariadbd.exe: an InnoDB flush cut short can corrupt the copy.
    exit /b 1
)

:wait_db
set /a tries+=1
netstat -ano | findstr /r ":3307[^0-9]" | findstr /c:"LISTENING" >nul
if not errorlevel 1 (
    if %tries% geq 30 (
        echo [db]   still listening on 3307 after 30s. Leave it - it is probably flushing.
        exit /b 1
    )
    ping -n 2 127.0.0.1 >nul
    goto wait_db
)
echo [db]   stopped.

:done
echo.
echo everything stopped - restart with "%ROOT%start.cmd"
endlocal
