<#
.SYNOPSIS
    论坛帖子压力测试脚本：按固定频率往 alk_forum_post 里插入测试帖。

.DESCRIPTION
    直接连便携包自带的 MariaDB（默认 127.0.0.1:3307 / alumin-link），
    按 -PerSecond 指定的频率（默认每秒 1 条）持续写入标题为「压力测试_test」的帖子。
    user_id 默认每次从 sys_user 里随机挑一个真实用户，category_id 从可用的论坛分区里随机挑。
    全程只保持一条数据库长连接，不反复建连，适合长时间跑。

    中文不会以明文拼进 SQL，而是一律转成十六进制再用 CONVERT(0x.. USING utf8mb4) 交给数据库，
    发到管道的始终是纯 ASCII，因此不受控制台代码页影响（中文 Windows 上是 GBK）。

.PARAMETER PerSecond
    每秒插入多少条，默认 1。可以给小数，比如 0.5 表示两秒一条。

.PARAMETER Duration
    跑多少秒后自动停，0（默认）表示一直跑，Ctrl+C 停止。

.PARAMETER Count
    总共插多少条后停，0（默认）表示不限条数。和 -Duration 谁先到算谁。

.PARAMETER Title
    帖子标题，默认「压力测试_test」。清理时也按这个标题匹配。

.PARAMETER UserId
    固定用某个用户的 id 发帖；不填则每条随机选一个 sys_user 里的用户。

.PARAMETER CategoryId
    固定发到某个分区；不填则每条随机选一个启用中的分区。

.PARAMETER BumpCategoryCount
    同时给 alk_forum_category.post_count 加 1（和 App 里发帖的行为一致）。
    默认不加，纯测写入性能时更快。

.PARAMETER Purge
    不插入，只把标题等于 -Title 的测试帖及其评论/点赞/转发删掉，
    并按实际剩余帖子重算每个分区的 post_count。

.EXAMPLE
    .\stress-test-forum.ps1
    每秒 1 条，一直跑到 Ctrl+C。

.EXAMPLE
    .\stress-test-forum.ps1 -PerSecond 20 -Duration 300
    每秒 20 条，跑 5 分钟。

.EXAMPLE
    .\stress-test-forum.ps1 -Count 100 -PerSecond 2 -BumpCategoryCount
    插 100 条，每秒 2 条，同时维护分区计数。

.EXAMPLE
    .\stress-test-forum.ps1 -Purge
    清理所有「压力测试_test」帖子。
#>
[CmdletBinding()]
param(
    [double]$PerSecond = 1,
    [int]$Duration = 0,
    [int]$Count = 0,
    [string]$Title = '压力测试_test',
    [string]$UserId = '',
    [string]$CategoryId = '',
    [switch]$BumpCategoryCount,
    [switch]$Purge,
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3307,
    [string]$DbUser = 'root',
    [string]$DbPassword = '123456',
    [string]$Database = 'alumin-link'
)

$ErrorActionPreference = 'Stop'

# 让中文在控制台正常显示（5.1 和 7 都适用）
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

# ---------------------------------------------------------------- 准备客户端

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$client = Join-Path $root 'runtime\mariadb\bin\mariadb.exe'
if (-not (Test-Path $client)) {
    $found = Get-Command mariadb -ErrorAction SilentlyContinue
    if (-not $found) { throw "找不到 mariadb 客户端：$client 不存在，PATH 里也没有 mariadb" }
    $client = $found.Source
}

$connArgs = @(
    "--host=$DbHost", "--port=$DbPort", "--user=$DbUser", "--password=$DbPassword",
    "--database=$Database", '--default-character-set=utf8mb4'
)

function Invoke-Sql {
    param([string]$Sql)
    # 统一返回一个字符串（多行用换行拼接）。不要返回数组：PowerShell 返回时会把
    # 单元素数组解包成字符串，调用方再取 [0] 就变成取首字符了（"43" -> "4"）。
    #
    # 另外 5.1 里给原生命令加 2>&1 且 $ErrorActionPreference='Stop' 时，
    # 客户端往 stderr 写一行就会被当成终止性错误把整个脚本打断，
    # 所以这里临时降成 Continue，把 stderr 收进 $out 自己判断。
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $client @connArgs '--batch' '--skip-column-names' '--raw' '--force' '-e' $Sql 2>&1
    } finally {
        $ErrorActionPreference = $prev
    }
    $text = (@($out) -join "`n").Trim()
    if ($text -match 'ERROR \d+') { throw "SQL 执行失败：$text" }
    return $text
}

function Split-Rows {
    param([string]$Text)
    if (-not $Text) { return @() }
    return @($Text -split "`r?`n" | Where-Object { $_.Trim() -ne '' })
}

# 把字符串变成 utf8mb4 的十六进制字面量：发到数据库管道里的永远是纯 ASCII，
# 既躲开了控制台代码页（GBK）的问题，也顺带免掉了单引号/反斜杠转义。
function SqlText {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return "''" }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    return "CONVERT(0x$hex USING utf8mb4)"
}

# 纯 ASCII 的标识符（UUID）用普通引号即可
function SqlQuote {
    param([string]$Text)
    return "'" + ($Text -replace '\\', '\\\\' -replace "'", "''") + "'"
}

# 用在 WHERE 里做等值比较：比 HEX(col) 和十六进制串，两边都是纯 ASCII，
# 绕开排序规则冲突——CONVERT(... USING utf8mb4) 得到的是字符集默认的
# utf8mb4_general_ci，而本库的列是 utf8mb4_unicode_ci，直接 = 会报 ERROR 1267。
function SqlHex {
    param([string]$Text)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    return (-join ($bytes | ForEach-Object { $_.ToString('x2') })).ToUpper()
}

# ---------------------------------------------------------------- 清理模式

if ($Purge) {
    $t = SqlHex $Title
    Write-Host "清理标题为「$Title」的测试帖..." -ForegroundColor Cyan
    $n = Invoke-Sql "select count(*) from alk_forum_post where hex(title) = '$t';"
    Invoke-Sql "delete from alk_forum_comment where post_id in (select id from alk_forum_post where hex(title) = '$t');" | Out-Null
    # 点赞表是泛化的 target_type/target_id（1=帖子，2=评论），没有 post_id
    Invoke-Sql "delete from alk_forum_like where target_type = 1 and target_id in (select id from alk_forum_post where hex(title) = '$t');" | Out-Null
    Invoke-Sql "delete from alk_forum_share where post_id in (select id from alk_forum_post where hex(title) = '$t');" | Out-Null
    Invoke-Sql "delete from alk_forum_collection where post_id in (select id from alk_forum_post where hex(title) = '$t');" | Out-Null
    Invoke-Sql "delete from alk_forum_post where hex(title) = '$t';" | Out-Null
    # 按实际剩余帖子重算，比逐条减更不容易算错
    Invoke-Sql "update alk_forum_category c set post_count = (select count(*) from alk_forum_post p where p.category_id = c.id and p.deleted = 0);" | Out-Null
    Write-Host "已删除 $n 条测试帖，分区计数已重算。" -ForegroundColor Green
    return
}

# ---------------------------------------------------------------- 取基础数据

Write-Host "连接 $DbUser@$DbHost`:$DbPort/$Database ..." -ForegroundColor DarkGray

if ($PerSecond -le 0) { throw "-PerSecond 必须大于 0" }

if ($UserId) {
    $userIds = @($UserId)
} else {
    $userIds = @(Split-Rows (Invoke-Sql "select id from sys_user where deleted = 0 and id <> '00000000-0000-0000-0000-000000000000';") |
        Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
}
if ($userIds.Count -eq 0) { throw "sys_user 里没有可用的用户" }

if ($CategoryId) {
    $categoryIds = @($CategoryId)
} else {
    $categoryIds = @(Split-Rows (Invoke-Sql "select id from alk_forum_category where deleted = 0 and is_active = 1;") |
        Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
}
if ($categoryIds.Count -eq 0) { throw "alk_forum_category 里没有启用中的分区" }

Write-Host ("可用用户 {0} 个，可用分区 {1} 个，目标频率 {2} 条/秒" -f $userIds.Count, $categoryIds.Count, $PerSecond) -ForegroundColor DarkGray

# ---------------------------------------------------------------- 建长连接

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $client
$psi.Arguments = ($connArgs -join ' ') + ' --batch --skip-column-names --force'
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
$psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
# StandardInputEncoding 只有 .NET Core（PowerShell 7）才有，5.1 上设置会报错，
# 所以先判断属性存不存在；SQL 本来就是纯 ASCII，5.1 用默认编码也没问题。
if ($psi.PSObject.Properties['StandardInputEncoding']) {
    $psi.StandardInputEncoding = New-Object System.Text.UTF8Encoding($false)
}
$psi.CreateNoWindow = $true

$db = [System.Diagnostics.Process]::Start($psi)
# 不能用 add_ErrorDataReceived 挂脚本块：回调线程没有 Runspace，会直接把 PowerShell 进程打崩。
# 改成自己拿 Task 轮询，读取进度跟着主循环走。
$errRef = [pscustomobject]@{ Value = $db.StandardError.ReadLineAsync() }
$outRef = [pscustomobject]@{ Value = $db.StandardOutput.ReadLineAsync() }
$errors = New-Object 'System.Collections.Generic.List[string]'

function Receive-DbOutput {
    param($Process, $ErrRef, $OutRef, $Errors)
    # 把已经就绪的行全部取走；两个流都要读空，避免管道缓冲区满导致子进程写阻塞
    while ($ErrRef.Value.IsCompleted) {
        try { $line = $ErrRef.Value.Result } catch { $line = $null }
        if ($null -eq $line) { break }
        # 客户端会把出错的语句也回显出来，只有 ERROR/Warning 开头的才是真错误
        if ($line -match '^(ERROR|Warning)') { $Errors.Add($line) }
        $ErrRef.Value = $Process.StandardError.ReadLineAsync()
    }
    while ($OutRef.Value.IsCompleted) {
        try { $line = $OutRef.Value.Result } catch { $line = $null }
        if ($null -eq $line) { break }
        $OutRef.Value = $Process.StandardOutput.ReadLineAsync()
    }
}

# 注意别用 $input，那是 PowerShell 的自动变量
$stdin = $db.StandardInput
$stdin.WriteLine('SET NAMES utf8mb4;')
$stdin.Flush()

$random = New-Object System.Random
$started = Get-Date
$next = $started
$inserted = 0
$failed = 0
$titleSql = SqlText $Title
$titleHex = SqlHex $Title
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "开始写入，Ctrl+C 停止。" -ForegroundColor Cyan

try {
    while ($true) {
        if ($Count -gt 0 -and $inserted -ge $Count) { break }
        if ($Duration -gt 0 -and ((Get-Date) - $started).TotalSeconds -ge $Duration) { break }

        $uid = $userIds[$random.Next($userIds.Count)]
        $cid = $categoryIds[$random.Next($categoryIds.Count)]
        $id = [guid]::NewGuid().ToString()
        $seq = $inserted + 1
        $stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        $content = "这是压力测试脚本自动生成的第 $seq 条帖子，写入时间 $stamp。" +
                   "内容为随机填充，用于验证论坛在高频写入场景下的稳定性和响应速度，请勿当真，测试结束后可直接删除。"

        $sql = "INSERT INTO alk_forum_post " +
               "(id, category_id, user_id, title, content, images, is_sticky, is_essence, is_locked, " +
               "views_count, likes_count, comments_count, shares_count, repost_from_id, last_replied_at, " +
               "created_at, created_by, updated_at, updated_by, deleted) VALUES (" +
               "$(SqlQuote $id), $(SqlQuote $cid), $(SqlQuote $uid), $titleSql, $(SqlText $content), NULL, " +
               "0, 0, 0, 0, 0, 0, 0, NULL, NULL, NOW(), $(SqlQuote $uid), NOW(), $(SqlQuote $uid), 0);"
        if ($BumpCategoryCount) {
            $sql += " UPDATE alk_forum_category SET post_count = post_count + 1 WHERE id = $(SqlQuote $cid);"
        }

        $stdin.WriteLine($sql)
        $stdin.Flush()
        $inserted++

        Receive-DbOutput -Process $db -ErrRef $errRef -OutRef $outRef -Errors $errors
        if ($errors.Count -gt 0) {
            foreach ($line in $errors) {
                $failed++
                Write-Host "  数据库报错: $line" -ForegroundColor Yellow
            }
            $errors.Clear()
        }

        # 每 10 条报一次进度
        if ($inserted % 10 -eq 0) {
            $elapsed = $stopwatch.Elapsed.TotalSeconds
            $rate = if ($elapsed -gt 0) { $inserted / $elapsed } else { 0 }
            Write-Host ("  已写入 {0} 条，实际 {1:N2} 条/秒" -f $inserted, $rate) -ForegroundColor DarkGray
        }

        # 按目标频率对齐到下一条的时间点；追不上就不补，避免越积越多
        $next = $next.AddSeconds(1.0 / $PerSecond)
        $waitMs = ($next - (Get-Date)).TotalMilliseconds
        if ($waitMs -gt 0) {
            Start-Sleep -Milliseconds ([int][Math]::Ceiling($waitMs))
        } else {
            $next = Get-Date
        }
    }
} finally {
    try { $stdin.WriteLine('SELECT 1;'); $stdin.Flush() } catch { }
    try { $stdin.Close() } catch { }
    Start-Sleep -Milliseconds 200
    Receive-DbOutput -Process $db -ErrRef $errRef -OutRef $outRef -Errors $errors
    if (-not $db.WaitForExit(3000)) { try { $db.Kill() } catch { } }

    foreach ($line in $errors) {
        $failed++
        Write-Host "  数据库报错: $line" -ForegroundColor Yellow
    }
    $errors.Clear()

    $total = $stopwatch.Elapsed.TotalSeconds
    $avg = if ($total -gt 0) { ($inserted - $failed) / $total } else { 0 }
    $left = Invoke-Sql "select count(*) from alk_forum_post where hex(title) = '$titleHex';"
    Write-Host ""
    Write-Host ("本次写入 {0} 条，失败 {1} 条，耗时 {2:N1} 秒，平均 {3:N2} 条/秒" -f $inserted, $failed, $total, $avg) -ForegroundColor Green
    Write-Host ("当前标题为「{0}」的帖子共 {1} 条；清理用：.\stress-test-forum.ps1 -Purge" -f $Title, $left) -ForegroundColor Green
}