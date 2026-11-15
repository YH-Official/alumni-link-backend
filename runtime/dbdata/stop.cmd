@echo off
setlocal enableextensions

rem Stops ONLY the bundled MariaDB (port 3307), gracefully.
rem The F:\XYL install on 3306 is never touched: this talks to 3307 only.

set "DB_BIN=%~dp0..\mariadb\bin"

if not exist "%DB_BIN%\mariadb-admin.exe" (
    echo FAILED - %DB_BIN%\mariadb-admin.exe is missing.
    exit /b 1
)

"%DB_BIN%\mariadb-admin.exe" -h 127.0.0.1 -P 3307 -uroot -p123456 ping 2>nul | findstr /c:"alive" >nul
if %errorlevel%==1 (
    echo MariaDB is not running on 127.0.0.1:3307
    exit /b 0
)

echo Asking MariaDB on 3307 to shut down cleanly...
"%DB_BIN%\mariadb-admin.exe" -h 127.0.0.1 -P 3307 -uroot -p123456 shutdown >nul 2>&1
if errorlevel 1 (
    echo FAILED - the server refused the shutdown request, nothing was killed.
    exit /b 1
)

set "tries=0"
:wait
ping -n 2 127.0.0.1 >nul
"%DB_BIN%\mariadb-admin.exe" -h 127.0.0.1 -P 3307 -uroot -p123456 ping 2>nul | findstr /c:"alive" >nul
if %errorlevel%==1 (
    echo MariaDB stopped, data is consistent.
    exit /b 0
)
set /a tries+=1
if %tries% geq 20 (
    echo FAILED - still answering on 3307. Do not kill it; it may be flushing.
    exit /b 1
)
goto wait
