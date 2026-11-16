@echo off
rem ===========================================================================
rem  alumni-link-backend 便携运行环境一键配置脚本   setup-env.cmd
rem ---------------------------------------------------------------------------
rem  用法:
rem    setup-env.cmd            一键配置, 缺什么补什么, 最后询问是否启动
rem    setup-env.cmd --yes      无人值守: 不暂停, 不询问是否启动
rem    setup-env.cmd --start    配置完成后立即执行 start.cmd
rem    setup-env.cmd --rebuild  忽略已有 jar, 强制重新编译
rem    setup-env.cmd --force    删除并重新部署 runtime\jdk21 与 runtime\mariadb
rem    setup-env.cmd --download 忽略本机已有的便携 JDK/MariaDB, 一律下载官方压缩包
rem    setup-env.cmd --offline  离线模式: 只用本机目录或 runtime\_packages 里的包
rem    setup-env.cmd --portable-gradle  Gradle 缓存也放进 runtime\gradle-home
rem
rem  部署内容, 全部为便携版, 不解包到系统目录, 不写注册表, 不注册 Windows 服务,
rem  也不修改系统 PATH:
rem    runtime\jdk21        Eclipse Temurin JDK 21 便携版
rem    runtime\mariadb      MariaDB 11.4 便携版, 端口 3307, 只监听本机
rem    runtime\gradle-home  Gradle 用户目录, wrapper 与依赖缓存, 可随项目整体搬走
rem    runtime\dbdata       数据库数据目录与 my.ini
rem    build\libs\alumni-link-1.0.0-SNAPSHOT-fat.jar  编译产物
rem
rem  完全离线部署: 把下面两个压缩包放进 runtime\_packages, 再加 --offline 运行
rem    OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip
rem    mariadb-11.4.13-winx64.zip
rem ===========================================================================

setlocal enableextensions enabledelayedexpansion

rem 本脚本以 GBK 保存: 先记下当前代码页, 再切到 936, 保证中文提示不乱码
for /f "usebackq tokens=2 delims=:" %%c in (`chcp`) do set "OLDCP=%%c"
if defined OLDCP set "OLDCP=%OLDCP: =%"
chcp 936 >nul 2>&1

rem ------------------------- 路径与常量 -------------------------
set "ROOT=%~dp0"
set "RUNTIME=%ROOT%runtime"
set "LOGDIR=%RUNTIME%\logs"
set "PKGDIR=%RUNTIME%\_packages"
set "TMPDIR=%RUNTIME%\_tmp"
set "JDK_DIR=%RUNTIME%\jdk21"
set "DB_DIR=%RUNTIME%\mariadb"
set "DBDATA_DIR=%RUNTIME%\dbdata"
set "DB_DATA=%DBDATA_DIR%\data"
set "DB_INI=%DBDATA_DIR%\my.ini"
set "GRADLE_HOME=%RUNTIME%\gradle-home"
set "JAR=%ROOT%build\libs\alumni-link-1.0.0-SNAPSHOT-fat.jar"
set "LOG=%LOGDIR%\setup.log"
set "BUILD_LOG=%LOGDIR%\build.log"

rem 与 start.cmd / application.yaml 保持一致, 不要随意改
set "DBPORT=3307"
set "DBPASS=123456"
set "DBNAME=alumin-link"
set "APPPORT=7031"

rem 与仓库里 runtime\dbdata\data 的生成版本保持一致, 数据目录才能直接沿用
set "MDB_VERSION=11.4.13"
set "JDK_BUILD=21.0.13_9"

set "PKG_MDB=%PKGDIR%\mariadb-%MDB_VERSION%-winx64.zip"
set "PKG_JDK=%PKGDIR%\OpenJDK21U-jdk_x64_windows_hotspot_%JDK_BUILD%.zip"

set "DB_STARTED=0"
set "FAILED_STEP="

rem ------------------------- 参数解析 -------------------------
set "OPT_YES="
set "OPT_START="
set "OPT_REBUILD="
set "OPT_FORCE="
set "OPT_DOWNLOAD="
set "OPT_OFFLINE="
set "OPT_NOPAUSE="
set "OPT_PGRADLE="

:parse_args
if "%~1"=="" goto args_done
set "ARG=%~1"
if /i "%ARG%"=="--yes"      goto arg_yes
if /i "%ARG%"=="-y"         goto arg_yes
if /i "%ARG%"=="/y"         goto arg_yes
if /i "%ARG%"=="--start"    goto arg_start
if /i "%ARG%"=="--rebuild"  goto arg_rebuild
if /i "%ARG%"=="--force"    goto arg_force
if /i "%ARG%"=="--download" goto arg_download
if /i "%ARG%"=="--offline"  goto arg_offline
if /i "%ARG%"=="--no-pause" goto arg_nopause
if /i "%ARG%"=="--portable-gradle" goto arg_pgradle
if /i "%ARG%"=="--help"     goto usage
if /i "%ARG%"=="-h"         goto usage
if /i "%ARG%"=="/?"         goto usage
echo [err] 未知参数: %ARG%
goto usage

:arg_yes
set "OPT_YES=1"
set "OPT_NOPAUSE=1"
shift
goto parse_args
:arg_start
set "OPT_START=1"
shift
goto parse_args
:arg_rebuild
set "OPT_REBUILD=1"
shift
goto parse_args
:arg_force
set "OPT_FORCE=1"
shift
goto parse_args
:arg_download
set "OPT_DOWNLOAD=1"
shift
goto parse_args
:arg_offline
set "OPT_OFFLINE=1"
shift
goto parse_args
:arg_nopause
set "OPT_NOPAUSE=1"
shift
goto parse_args
:arg_pgradle
set "OPT_PGRADLE=1"
shift
goto parse_args

rem ------------------------- 主流程 -------------------------
:args_done
call :init_env
if errorlevel 1 goto fail

call :step_jdk
if errorlevel 1 goto fail

call :step_mariadb
if errorlevel 1 goto fail

call :step_db
if errorlevel 1 goto fail

call :step_build
if errorlevel 1 goto fail

call :summary
call :maybe_start
goto end_ok

rem ------------------------- 初始化 -------------------------
:init_env
call :banner
if not exist "%ROOT%build.gradle.kts" (
    echo [err] 当前目录不是 alumni-link-backend 项目根目录
    echo [err] 请把本脚本放在 build.gradle.kts 同级目录后再运行
    exit /b 1
)
if not exist "%RUNTIME%" mkdir "%RUNTIME%" 2>nul
if not exist "%LOGDIR%" mkdir "%LOGDIR%" 2>nul
if not exist "%PKGDIR%" mkdir "%PKGDIR%" 2>nul
if not exist "%TMPDIR%" mkdir "%TMPDIR%" 2>nul
if not exist "%DBDATA_DIR%" mkdir "%DBDATA_DIR%" 2>nul
>>"%LOG%" echo.
>>"%LOG%" echo ===== setup-env started =====
call :note [env] 项目目录 %ROOT%
call :note [env] 部署目录 %RUNTIME%
call :note [env] 详细日志 %LOG%

where curl.exe >nul 2>&1
if errorlevel 1 (
    set "HAS_CURL="
    call :note [env] 提示: 未找到 curl.exe, 下载将回退到 PowerShell
) else (
    set "HAS_CURL=1"
)
where tar.exe >nul 2>&1
if errorlevel 1 (
    set "HAS_TAR="
    call :note [env] 提示: 未找到 tar.exe, 解压将回退到 PowerShell Expand-Archive
) else (
    set "HAS_TAR=1"
)
exit /b 0

:banner
echo.
echo ============================================================
echo   alumni-link-backend  便携运行环境一键配置
echo   JDK 21  +  MariaDB %MDB_VERSION%  +  Gradle 构建
echo ============================================================
exit /b 0

rem ------------------------- 第 1 步: JDK 21 -------------------------
:step_jdk
call :blank
call :note [jdk] 第 1 步 / 共 4 步: 便携 JDK 21
call :verify_jdk "%JDK_DIR%"
if not errorlevel 1 (
    call :note [jdk] 已就绪, 跳过部署
    call :print_java_version
    exit /b 0
)

if defined OPT_FORCE (
    call :note [jdk] --force: 删除旧目录 %JDK_DIR%
    if exist "%JDK_DIR%\bin\java.exe" call :note [jdk] 若有进程正在用这个 JDK, 删除会失败, 请先关掉
    rmdir /s /q "%JDK_DIR%" 2>nul
)

rem 优先复用本机已有的便携 JDK, 省掉 196 MB 下载; --download 可跳过
if not defined OPT_DOWNLOAD (
    if not defined OPT_OFFLINE (
        for %%d in ("F:\jdk21" "D:\jdk21" "C:\jdk21" "F:\Java\jdk21" "D:\Java\jdk21" "C:\Java\jdk21") do (
            call :verify_jdk "%%~d"
            if not errorlevel 1 (
                call :note [jdk] 发现本机便携 JDK: %%~d, 直接复制
                robocopy "%%~d" "%JDK_DIR%" /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 >nul
                if exist "%JDK_DIR%\bin\javac.exe" goto jdk_ready
            )
        )
    )
)

set "JDKZIP="
for %%z in ("%PKG_JDK%" "%PKGDIR%\OpenJDK21U-jdk_x64_windows_hotspot_*.zip" "%PKGDIR%\jdk21*.zip") do (
    if not defined JDKZIP if exist "%%~z" set "JDKZIP=%%~z"
)
if defined JDKZIP call :note [jdk] 使用本地安装包 %JDKZIP%

if not defined JDKZIP (
    if defined OPT_OFFLINE (
        call :note [jdk] 离线模式且没有可用安装包
        call :note [jdk] 请把 OpenJDK21U-jdk_x64_windows_hotspot_%JDK_BUILD%.zip 放进 %PKGDIR%
        exit /b 1
    )
    call :fetch_jdk
    if errorlevel 1 exit /b 1
)

call :unzip_to "%JDKZIP%" "%TMPDIR%\jdk"
if errorlevel 1 (
    call :note [jdk] 解压失败, 文件可能损坏: %JDKZIP%
    exit /b 1
)
if exist "%JDK_DIR%" rmdir /s /q "%JDK_DIR%" 2>nul
call :flatten_into "%TMPDIR%\jdk" "bin\javac.exe" "%JDK_DIR%"
if errorlevel 1 (
    call :note [jdk] 安装包结构异常, 未找到 bin\javac.exe
    exit /b 1
)
rmdir /s /q "%TMPDIR%\jdk" 2>nul
call :note [jdk] 解压完成

:jdk_ready
call :verify_jdk "%JDK_DIR%"
if errorlevel 1 (
    call :note [jdk] 部署后校验失败: %JDK_DIR%
    exit /b 1
)
call :note [jdk] 就绪 %JDK_DIR%
call :print_java_version
exit /b 0

rem 校验一个目录是否为 JDK 21
:verify_jdk
if "%~1"=="" exit /b 1
if not exist "%~1\bin\java.exe" exit /b 1
if not exist "%~1\bin\javac.exe" exit /b 1
if not exist "%~1\release" exit /b 1
findstr /b /c:"JAVA_VERSION" "%~1\release" | findstr /r /c:"21\.[0-9]" >nul
if errorlevel 1 exit /b 1
exit /b 0

:print_java_version
if not exist "%JDK_DIR%\bin\java.exe" exit /b 0
"%JDK_DIR%\bin\java.exe" -version > "%TMPDIR%\javaver.txt" 2>&1
set "JVER="
if exist "%TMPDIR%\javaver.txt" set /p JVER=<"%TMPDIR%\javaver.txt"
if defined JVER call :note [jdk] 版本 %JVER%
exit /b 0

:fetch_jdk
set "JDKZIP="
call :download "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/OpenJDK21U-jdk_x64_windows_hotspot_%JDK_BUILD%.zip" "%PKG_JDK%"
if not errorlevel 1 (
    call :filesize_ok "%PKG_JDK%" 50000000
    if not errorlevel 1 (
        set "JDKZIP=%PKG_JDK%"
        goto fetch_jdk_done
    )
)
call :note [jdk] 清华镜像不可用, 改用 Adoptium 官方接口
del /f /q "%PKG_JDK%" >nul 2>&1
call :download "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" "%PKG_JDK%"
if not errorlevel 1 (
    call :filesize_ok "%PKG_JDK%" 50000000
    if not errorlevel 1 (
        set "JDKZIP=%PKG_JDK%"
        goto fetch_jdk_done
    )
)
:fetch_jdk_done
del /f /q "%PKG_JDK%.part" >nul 2>&1
if defined JDKZIP exit /b 0
call :note [jdk] 所有下载地址都失败, 请手动下载后放进 %PKGDIR%
exit /b 1

rem ------------------------- 第 2 步: MariaDB -------------------------
:step_mariadb
call :blank
call :note [db] 第 2 步 / 共 4 步: 便携 MariaDB %MDB_VERSION%
call :verify_mariadb "%DB_DIR%"
if not errorlevel 1 (
    call :note [db] 已就绪, 跳过部署
    call :print_mariadb_version
    exit /b 0
)

if defined OPT_FORCE (
    call :note [db] --force: 删除旧目录 %DB_DIR%
    if exist "%DB_DIR%\bin\mariadbd.exe" call :note [db] 如果数据库还在运行, 请先执行 runtime\dbdata\stop.cmd
    rmdir /s /q "%DB_DIR%" 2>nul
)

if not defined OPT_DOWNLOAD (
    if not defined OPT_OFFLINE (
        for %%d in ("F:\mariadb" "D:\mariadb" "C:\mariadb" "F:\MariaDB" "D:\MariaDB") do (
            call :verify_mariadb "%%~d"
            if not errorlevel 1 (
                call :note [db] 发现本机便携 MariaDB: %%~d, 直接复制
                robocopy "%%~d" "%DB_DIR%" /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 /XD "%%~d\data" >nul
                if exist "%DB_DIR%\bin\mariadbd.exe" goto mariadb_ready
            )
        )
    )
)

set "MDBZIP="
for %%z in ("%PKG_MDB%" "%PKGDIR%\mariadb-*-winx64.zip") do (
    if not defined MDBZIP if exist "%%~z" set "MDBZIP=%%~z"
)
if defined MDBZIP call :note [db] 使用本地安装包 %MDBZIP%

if not defined MDBZIP (
    if defined OPT_OFFLINE (
        call :note [db] 离线模式且没有可用安装包
        call :note [db] 请把 mariadb-%MDB_VERSION%-winx64.zip 放进 %PKGDIR%
        exit /b 1
    )
    call :fetch_mariadb
    if errorlevel 1 exit /b 1
)

call :unzip_to "%MDBZIP%" "%TMPDIR%\mariadb"
if errorlevel 1 (
    call :note [db] 解压失败, 文件可能损坏: %MDBZIP%
    exit /b 1
)
if exist "%DB_DIR%" rmdir /s /q "%DB_DIR%" 2>nul
call :flatten_into "%TMPDIR%\mariadb" "bin\mariadbd.exe" "%DB_DIR%"
if errorlevel 1 (
    call :note [db] 安装包结构异常, 未找到 bin\mariadbd.exe
    exit /b 1
)
rmdir /s /q "%TMPDIR%\mariadb" 2>nul
call :note [db] 解压完成

:mariadb_ready
call :verify_mariadb "%DB_DIR%"
if errorlevel 1 (
    call :note [db] 部署后校验失败: %DB_DIR%
    exit /b 1
)
call :note [db] 就绪 %DB_DIR%
call :print_mariadb_version
exit /b 0

:verify_mariadb
if "%~1"=="" exit /b 1
if not exist "%~1\bin\mariadbd.exe" exit /b 1
if not exist "%~1\bin\mariadb.exe" exit /b 1
if not exist "%~1\bin\mariadb-admin.exe" exit /b 1
exit /b 0

:print_mariadb_version
if not exist "%DB_DIR%\bin\mariadbd.exe" exit /b 0
"%DB_DIR%\bin\mariadbd.exe" --version > "%TMPDIR%\mdbver.txt" 2>&1
set "MVER="
if exist "%TMPDIR%\mdbver.txt" set /p MVER=<"%TMPDIR%\mdbver.txt"
if defined MVER call :note [db] 版本 %MVER%
if not defined MVER exit /b 0
echo %MVER% | findstr /c:"%MDB_VERSION%" >nul
if errorlevel 1 call :note [db] 警告: 部署版本不是 %MDB_VERSION%, 与仓库内数据目录版本不一致, 建议先备份 runtime\dbdata
exit /b 0

:fetch_mariadb
set "MDBZIP="
for %%u in (
 "https://mirrors.tuna.tsinghua.edu.cn/mariadb/mariadb-%MDB_VERSION%/winx64-packages/mariadb-%MDB_VERSION%-winx64.zip"
 "https://mirrors.ustc.edu.cn/mariadb/mariadb-%MDB_VERSION%/winx64-packages/mariadb-%MDB_VERSION%-winx64.zip"
 "https://mirrors.aliyun.com/mariadb/mariadb-%MDB_VERSION%/winx64-packages/mariadb-%MDB_VERSION%-winx64.zip"
 "https://archive.mariadb.org/mariadb-%MDB_VERSION%/winx64-packages/mariadb-%MDB_VERSION%-winx64.zip"
) do (
    call :download "%%~u" "%PKG_MDB%"
    if not errorlevel 1 (
        call :filesize_ok "%PKG_MDB%" 30000000
        if not errorlevel 1 (
            set "MDBZIP=%PKG_MDB%"
            goto fetch_mariadb_done
        )
    )
    del /f /q "%PKG_MDB%" >nul 2>&1
)
:fetch_mariadb_done
del /f /q "%PKG_MDB%.part" >nul 2>&1
if defined MDBZIP exit /b 0
call :note [db] 所有下载地址都失败, 请手动下载后放进 %PKGDIR%
exit /b 1

rem ------------------------- 第 3 步: 数据库初始化 -------------------------
:step_db
call :blank
call :note [db] 第 3 步 / 共 4 步: 数据库初始化

if not exist "%DB_INI%" call :write_my_ini
if exist "%DB_DATA%\mysql" goto db_data_ok
call :db_init
if errorlevel 1 exit /b 1

:db_data_ok
call :db_ping
if not errorlevel 1 goto db_up
call :db_start
if errorlevel 1 (
    call :note [db] 数据库没能启动, 请查看最小化的 MariaDB 窗口里的日志
    call :note [db] 常见原因: 端口 %DBPORT% 被占用, 或数据目录属于更高版本的 MariaDB
    exit /b 1
)

:db_up
call :db_ensure_schema
set "RC=!errorlevel!"
call :db_stop_if_started
exit /b !RC!

:db_init
call :note [db] 未发现数据目录, 执行 mariadb-install-db
pushd "%DB_DIR%"
"%DB_DIR%\bin\mariadb-install-db.exe" --datadir="%DB_DATA%" --password=%DBPASS% --allow-remote-root-access > "%LOGDIR%\db-init.log" 2>&1
set "RC=!errorlevel!"
if not "!RC!"=="0" (
    call :note [db] 带远程 root 授权初始化失败, 用基础参数重试
    "%DB_DIR%\bin\mariadb-install-db.exe" --datadir="%DB_DATA%" --password=%DBPASS% > "%LOGDIR%\db-init.log" 2>&1
    set "RC=!errorlevel!"
)
popd
if not "!RC!"=="0" (
    call :note [db] 初始化失败, 日志 %LOGDIR%\db-init.log
    exit /b 1
)
call :note [db] 初始化完成
exit /b 0

:db_ensure_schema
"%DB_DIR%\bin\mariadb.exe" -h127.0.0.1 -P%DBPORT% -uroot -p%DBPASS% -e "CREATE DATABASE IF NOT EXISTS `%DBNAME%` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" >>"%LOG%" 2>&1
if errorlevel 1 (
    call :note [db] 无法连接数据库, 请确认 root 密码是 %DBPASS%, 且 3307 上就是这套数据目录
    exit /b 1
)

set "TABLES="
"%DB_DIR%\bin\mariadb.exe" -h127.0.0.1 -P%DBPORT% -uroot -p%DBPASS% -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='%DBNAME%';" > "%TMPDIR%\tables.txt" 2>nul
if exist "%TMPDIR%\tables.txt" set /p TABLES=<"%TMPDIR%\tables.txt"
if not defined TABLES (
    call :note [db] 表数量查询失败, 跳过表检查
    exit /b 0
)
if not "%TABLES%"=="0" (
    call :note [db] 库 %DBNAME% 正常, 共 %TABLES% 张表
    exit /b 0
)

call :note [db] 注意: 库 %DBNAME% 是空的, 本项目 ddl.run=false 不会自动建表
if exist "%DBDATA_DIR%\%DBNAME%.sql" (
    call :note [db] 发现 dump %DBNAME%.sql, 开始导入
    "%DB_DIR%\bin\mariadb.exe" -h127.0.0.1 -P%DBPORT% -uroot -p%DBPASS% "%DBNAME%" < "%DBDATA_DIR%\%DBNAME%.sql" >>"%LOG%" 2>&1
    if errorlevel 1 (
        call :note [db] 导入失败, 详情见 %LOG%
    ) else (
        call :note [db] 导入完成
    )
) else (
    call :note [db] 解决办法: 把 dump 放到 %DBDATA_DIR%\%DBNAME%.sql 后重跑本脚本
    call :note [db] 或者把仓库里的 runtime\dbdata\data 整个覆盖回来
)
exit /b 0

:write_my_ini
call :note [db] 生成默认 my.ini
> "%DB_INI%" echo # Portable MariaDB for alumni-link. basedir/datadir/port come from start.cmd.
>>"%DB_INI%" echo [client]
>>"%DB_INI%" echo port = %DBPORT%
>>"%DB_INI%" echo default-character-set = utf8mb4
>>"%DB_INI%" echo [mysqld]
>>"%DB_INI%" echo character-set-server = utf8mb4
>>"%DB_INI%" echo collation-server = utf8mb4_general_ci
>>"%DB_INI%" echo max_connections = 200
>>"%DB_INI%" echo innodb_buffer_pool_size = 256M
>>"%DB_INI%" echo sql_mode = "STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION"
exit /b 0

:db_ping
if not exist "%DB_DIR%\bin\mariadb-admin.exe" exit /b 1
"%DB_DIR%\bin\mariadb-admin.exe" -h127.0.0.1 -P%DBPORT% -uroot -p%DBPASS% ping 2>nul | findstr /c:"alive" >nul
if errorlevel 1 exit /b 1
exit /b 0

:db_start
call :note [db] 临时启动 MariaDB, 端口 %DBPORT%
start "alumni-link setup MariaDB" /min "%DB_DIR%\bin\mariadbd.exe" --defaults-file="%DB_INI%" --console --basedir="%DB_DIR%" --datadir="%DB_DATA%" --port=%DBPORT% --plugin-dir="%DB_DIR%\lib\plugin"
set "DB_STARTED=1"
set /a DBTRIES=0

:db_start_wait
call :db_ping
if not errorlevel 1 (
    call :note [db] 已就绪
    exit /b 0
)
set /a DBTRIES+=1
if %DBTRIES% GEQ 30 exit /b 1
ping -n 2 127.0.0.1 >nul
goto db_start_wait

:db_stop_if_started
if not "%DB_STARTED%"=="1" exit /b 0
call :note [db] 关闭本次临时启动的数据库
"%DB_DIR%\bin\mariadb-admin.exe" -h127.0.0.1 -P%DBPORT% -uroot -p%DBPASS% shutdown >nul 2>&1
set /a DBSTOP=0

:db_stop_wait
call :db_ping
if errorlevel 1 (
    call :note [db] 已关闭, 数据已刷盘
    exit /b 0
)
set /a DBSTOP+=1
if %DBSTOP% GEQ 20 (
    call :note [db] 仍在关闭中, 不要 taskkill, 让它自己结束
    exit /b 0
)
ping -n 2 127.0.0.1 >nul
goto db_stop_wait

rem ------------------------- 第 4 步: 编译 -------------------------
:step_build
call :blank
call :note [build] 第 4 步 / 共 4 步: 编译 fat jar

if not defined OPT_REBUILD (
    if exist "%JAR%" (
        call :note [build] jar 已存在, 跳过编译
        call :note [build] 需要重编请加 --rebuild
        exit /b 0
    )
)

set "JAVA_HOME=%JDK_DIR%"
set "PATH=%JDK_DIR%\bin;%PATH%"
rem Gradle 只是构建工具, 缓存默认沿用系统已有的 GRADLE_USER_HOME 以免重复下载
rem 依赖; 想让缓存也随项目搬走, 加 --portable-gradle, 会强制放进 runtime\gradle-home
if defined OPT_PGRADLE set "GRADLE_USER_HOME=%GRADLE_HOME%"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%GRADLE_HOME%"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%" 2>nul

call :note [build] JAVA_HOME=%JAVA_HOME%
call :note [build] GRADLE_USER_HOME=%GRADLE_USER_HOME%
call :note [build] 首次编译要下载 Gradle 8.10.2 与全部依赖, 通常 5 到 20 分钟

pushd "%ROOT%"
call "%ROOT%gradlew.bat" --no-daemon --console=plain bootJar > "%BUILD_LOG%" 2>&1
set "RC=!errorlevel!"
popd

if not "!RC!"=="0" (
    call :note [build] 编译失败, 完整日志 %BUILD_LOG%
    call :note [build] 日志尾部:
    if exist "%BUILD_LOG%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-Content -LiteralPath '%BUILD_LOG%' -Tail 30"
    exit /b 1
)

if not exist "%JAR%" (
    call :note [build] 编译成功但找不到 jar: %JAR%
    exit /b 1
)
for %%A in ("%JAR%") do call :note [build] 已生成 %%~nxA  大小 %%~zA 字节
exit /b 0

rem ------------------------- 汇总 / 收尾 -------------------------
:summary
call :blank
call :note ============================================================
call :note  配置完成
call :note ============================================================
call :print_java_version
call :print_mariadb_version
call :note [app] HTTP 端口 %APPPORT%, 访问 http://127.0.0.1:%APPPORT%
call :note [db] 数据库 127.0.0.1:%DBPORT%  库名 %DBNAME%  账号 root / %DBPASS%
call :note [db] 数据目录 %DB_DATA%
call :note [app] 启动脚本 %ROOT%start.cmd
call :note [app] 停止脚本 %ROOT%stop.cmd
call :note [env] 这些组件全部位于 %RUNTIME%, 没有安装到系统目录
call :note [env] 整个项目目录可以直接打包拷走, 到新机器重跑本脚本即可
exit /b 0

:maybe_start
if defined OPT_START goto do_start
if defined OPT_YES (
    call :note [app] 无人值守模式, 不自动启动
    exit /b 0
)
set "ANSWER="
set /p "ANSWER=现在启动校友录吗? 直接回车启动, 输入 N 跳过: "
if /i "%ANSWER%"=="N" exit /b 0
goto do_start

:do_start
call :blank
call :note [app] 调用 start.cmd
call "%ROOT%start.cmd"
call :note [app] start.cmd 已返回
exit /b 0

rem ------------------------- 通用工具 -------------------------
:note
set "MSG=%*"
if not defined MSG (
    call :blank
    exit /b 0
)
echo %MSG%
>>"%LOG%" echo %MSG%
exit /b 0

:blank
echo.
>>"%LOG%" echo.
exit /b 0

:download
rem %1 = url, %2 = 目标文件
set "DLURL=%~1"
set "DLOUT=%~2"
call :note [pkg] 下载 %DLURL%
if defined HAS_CURL (
    curl.exe -L --fail --retry 2 --retry-delay 2 --connect-timeout 20 --max-time 3600 -o "%DLOUT%.part" "%DLURL%"
    if errorlevel 1 (
        del /f /q "%DLOUT%.part" >nul 2>&1
        exit /b 1
    )
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; try { [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13 } catch {}; Invoke-WebRequest -UseBasicParsing -Uri '%DLURL%' -OutFile '%DLOUT%.part'"
    if errorlevel 1 (
        del /f /q "%DLOUT%.part" >nul 2>&1
        exit /b 1
    )
)
move /y "%DLOUT%.part" "%DLOUT%" >nul
exit /b 0

:filesize_ok
rem %1 = 文件, %2 = 最小字节数
set "FS="
for %%A in ("%~1") do set "FS=%%~zA"
if not defined FS exit /b 1
if %FS% LSS %~2 exit /b 1
exit /b 0

:unzip_to
rem %1 = zip, %2 = 解压目录
if exist "%~2" rmdir /s /q "%~2" 2>nul
mkdir "%~2" 2>nul
call :note [pkg] 解压 %~nx1
if defined HAS_TAR (
    tar.exe -xf "%~1" -C "%~2" >nul 2>&1
    if not errorlevel 1 exit /b 0
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%~1' -DestinationPath '%~2' -Force" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0

:flatten_into
rem %1 = 解压目录, %2 = 特征文件相对路径, %3 = 目标目录
if exist "%~1\%~2" (
    move "%~1" "%~3" >nul
    exit /b 0
)
for /d %%d in ("%~1\*") do (
    if exist "%%d\%~2" (
        move "%%d" "%~3" >nul
        exit /b 0
    )
)
exit /b 1

rem ------------------------- 收尾 -------------------------
:end_ok
call :blank
>>"%LOG%" echo ===== setup-env finished OK =====
rem 临时解压目录和探针文件用完即删, 下载缓存 _packages 保留, 方便下次离线部署
if exist "%TMPDIR%" rmdir /s /q "%TMPDIR%" 2>nul
if not defined OPT_NOPAUSE pause
if defined OLDCP chcp %OLDCP% >nul 2>&1
endlocal
exit /b 0

:fail
call :blank
call :note [err] 配置未完成, 请按上面的提示处理
call :note [err] 日志 %LOG%
if exist "%BUILD_LOG%" call :note [err] 编译日志 %BUILD_LOG%
if "%DB_STARTED%"=="1" call :db_stop_if_started
if not defined OPT_NOPAUSE pause
if defined OLDCP chcp %OLDCP% >nul 2>&1
endlocal
exit /b 1

:usage
echo.
echo 用法: setup-env.cmd [选项]
echo.
echo   --yes       无人值守, 不暂停, 不询问是否启动
echo   --start     配置完成后立即启动
echo   --rebuild   忽略已有 jar, 强制重新编译
echo   --force     删除并重新部署 runtime\jdk21 与 runtime\mariadb
echo   --download  忽略本机已有便携 JDK/MariaDB, 一律下载官方压缩包
echo   --offline   离线模式, 只用本机目录或 runtime\_packages 里的包
echo   --portable-gradle  Gradle 缓存也放进 runtime\gradle-home
echo   --no-pause  结束后不等按键
echo   --help      显示本帮助
echo.
echo 部署目标目录:
echo   %JDK_DIR%
echo   %DB_DIR%
echo   %GRADLE_HOME%
echo   %JAR%
echo.
if not defined OPT_NOPAUSE pause
if defined OLDCP chcp %OLDCP% >nul 2>&1
endlocal
exit /b 0