@echo off
setlocal enableextensions

rem Starts ONLY the bundled MariaDB (port 3307), without the app.
rem Use the start.cmd in the bundle root for the normal one-shot launch.
rem Paths are relative to this folder, so the bundle can live anywhere.

set "DB_BIN=%~dp0..\mariadb\bin"
set "DB_BASE=%~dp0..\mariadb"
set "DB_INI=%~dp0my.ini"
set "DB_DATA=%~dp0data"

if not exist "%DB_BIN%\mariadbd.exe" (
    echo FAILED - %DB_BIN%\mariadbd.exe is missing.
    exit /b 1
)

"%DB_BIN%\mariadb-admin.exe" -h 127.0.0.1 -P 3307 -uroot -p123456 ping 2>nul | findstr /c:"alive" >nul
if %errorlevel%==0 (
    echo MariaDB is already running on 127.0.0.1:3307
    exit /b 0
)

start "alumni-link-portable MariaDB" /min "%DB_BIN%\mariadbd.exe" --defaults-file="%DB_INI%" --console --basedir="%DB_BASE%" --datadir="%DB_DATA%" --port=3307 --plugin-dir="%DB_BASE%\lib\plugin"
echo Waiting for MariaDB on 127.0.0.1:3307...

set "tries=0"
:wait
ping -n 2 127.0.0.1 >nul
"%DB_BIN%\mariadb-admin.exe" -h 127.0.0.1 -P 3307 -uroot -p123456 ping 2>nul | findstr /c:"alive" >nul
if %errorlevel%==0 (
    echo MariaDB ready on 127.0.0.1:3307
    exit /b 0
)
set /a tries+=1
if %tries% geq 30 (
    echo FAILED - no answer within 30s. Read the minimized MariaDB window.
    exit /b 1
)
goto wait
