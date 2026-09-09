<#
Device acceptance (not an offline test). Requires an already authorized backend.
Default verification only returns to the existing page using am start. It does
NOT prove Activity recreation or process cold start. No force-stop mode is offered.
After an enable tap is attempted, failure triggers bounded, best-effort cleanup
through this App's stop button. ADB disconnect / inaccessible UI means UNCONFIRMED,
not success. Never stop the permission manager or kill a device process.
Offline regression: powershell -NoProfile -File tests/verify-stop.Tests.ps1
#>
param(
    [string]$Serial,
    [string]$Apk,
    [ValidateSet("Auto", "Shizuku", "Stellar")]
    [string]$Backend = "Auto",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $projectRoot ".tools\android-sdk\platform-tools\adb.exe"
. (Join-Path $PSScriptRoot 'build-common.ps1')
$metadata = Get-ProjectMetadata -ProjectRoot $projectRoot
if (-not $Apk) { $Apk = Join-Path $projectRoot ("dist/MiBrowserRedirector-debug-debug-signed-v{0}.apk" -f $metadata.VersionName) }
$packageName = $metadata.PackageName
$stellarProcess = "$packageName`:redirector"
$shizukuProcess = "$packageName`:redirector_shizuku"
# Unique file; it is removed on exit. Never dump UI XML to the console.
$remoteXml = "/sdcard/codex-redirector-$([guid]::NewGuid().ToString('N')).xml"

function Get-MonotonicMilliseconds {
    return [long]([Diagnostics.Stopwatch]::GetTimestamp() * 1000.0 / [Diagnostics.Stopwatch]::Frequency)
}

function ConvertTo-NativeArgument {
    param([string]$Value)
    # ProcessStartInfo.ArgumentList is unavailable on Windows PowerShell 5.1.
    # Quote without a shell, preserving embedded quotes and trailing backslashes.
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Invoke-AdbProcess {
    param([string[]]$Arguments, [int]$TimeoutMilliseconds, [string]$Executable = $adb)
    if ($TimeoutMilliseconds -le 0) { throw 'ADB deadline exhausted.' }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = [Diagnostics.ProcessStartInfo]::new()
    $process.StartInfo.FileName = $Executable
    $process.StartInfo.Arguments = ($Arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    $process.StartInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Could not start the local ADB client.' }
        # Drain both pipes concurrently; a full stderr pipe must not block exit.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) { throw 'ADB command timed out; device state is unknown.' }
        if (-not $stdout.Wait(1000) -or -not $stderr.Wait(1000)) { throw 'ADB output did not close in time.' }
        return @{ ExitCode = $process.ExitCode; Output = $stdout.Result + $stderr.Result }
    } finally {
        if ($started) {
            try {
                if (-not $process.HasExited) {
                    # Only this local client/verifier is terminated. No adb kill-server,
                    # device process kill, manager stop, or process-tree kill.
                    $process.Kill()
                    [void]$process.WaitForExit(1000)
                }
            } catch { }
        }
        $process.Dispose()
    }
}

function Throw-VerificationError {
    param([string]$Message)
    $errorObject = [Exception]::new($Message)
    $errorObject.Data['VerifyStopSafe'] = $true
    throw $errorObject
}

function Assert-VerificationApkSignature {
    param([string]$Apk, [string]$SdkRoot)
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { Throw-VerificationError 'APK does not exist.' }
    $jar = Join-Path $SdkRoot 'build-tools/35.0.0/lib/apksigner.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { Throw-VerificationError 'Missing project-local Android build-tools 35.0.0.' }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME.Trim('"') 'bin/java.exe' } else { (Get-Command java.exe -CommandType Application -ErrorAction Stop).Source }
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { Throw-VerificationError 'Java is unavailable for APK signature verification.' }
    # Same apksigner 35.0.0 verify --verbose check as build-common.ps1, directly
    # launch its Java entry point so a hung verifier (not just a batch wrapper)
    # can be reaped. No private signing key is loaded. No tools are downloaded.
    $remaining = [int][Math]::Min(30000, $phaseDeadline - (Get-MonotonicMilliseconds))
    $result = Invoke-AdbProcess -Executable $java -Arguments @('-Xmx1024M', '-Xss1m', '-jar', $jar, 'verify', '--verbose', $Apk) -TimeoutMilliseconds $remaining
    if ($result.ExitCode -ne 0) { Throw-VerificationError 'APK signature verification failed; installation was not attempted.' }
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowMissingProcess, [int]$CommandTimeoutMilliseconds = 15000)
    $remaining = $phaseDeadline - (Get-MonotonicMilliseconds)
    if ($remaining -le 0) { Throw-VerificationError 'The current verification/recovery deadline expired.' }
    $timeout = [int][Math]::Min($remaining, $CommandTimeoutMilliseconds)
    try { $result = Invoke-AdbProcess -Arguments (@($deviceArgs) + $Arguments) -TimeoutMilliseconds $timeout }
    catch { Throw-VerificationError 'ADB command failed or timed out; raw output suppressed.' }
    $output = [string]$result.Output
    if ($AllowMissingProcess -and $result.ExitCode -eq 1 -and [string]::IsNullOrWhiteSpace($output)) { return '' }
    if ($result.ExitCode -ne 0) { Throw-VerificationError 'ADB command failed; raw output suppressed.' }
    return $output
}

function Get-WindowXml {
    [void](Invoke-Adb @('shell', 'uiautomator', 'dump', $remoteXml))
    return Invoke-Adb @('exec-out', 'cat', $remoteXml)
}

function Get-NodeCenter {
    param([System.Xml.XmlElement]$Node)
    $bounds = [string]$Node.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { Throw-VerificationError 'Unexpected UI bounds.' }
    return @([int](([int]$Matches[1] + [int]$Matches[3]) / 2), [int](([int]$Matches[2] + [int]$Matches[4]) / 2))
}

function Tap-Node {
    param([System.Xml.XmlElement]$Node, [switch]$MayEnableService)
    $center = Get-NodeCenter -Node $Node
    if ($MayEnableService) {
        # Mark BEFORE dispatch: a timeout can mean the tap reached the phone.
        $runState.EnableAttempted = $true
    }
    [void](Invoke-Adb @('shell', 'input', 'tap', "$($center[0])", "$($center[1])"))
}

function Tap-Text {
    param([string]$Text, [switch]$MayEnableService)
    $node = Find-AppNode -XPath "//node[@package='$packageName' and @text='$Text' and @clickable='true' and @enabled='true']" -FirstDirection Up
    if ($null -eq $node) { Throw-VerificationError "App button '$Text' is not available." }
    Tap-Node -Node $node -MayEnableService:$MayEnableService
}

function Scroll-AppPage {
    param([xml]$Document, [ValidateSet('Up', 'Down')][string]$Direction)
    # Derive a gesture entirely inside this App's current visible scroll viewport.
    # Never fall back to another app, an overlay or hard-coded screen coordinates.
    $page = $Document.SelectSingleNode("//node[@package='$packageName' and @resource-id='$packageName`:id/page_scroll' and @scrollable='true']")
    if ($null -eq $page -or [string]$page.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $false }
    $left = [int]$Matches[1]; $top = [int]$Matches[2]
    $width = [int]$Matches[3] - $left; $height = [int]$Matches[4] - $top
    if ($width -lt 2 -or $height -lt 4) { return $false }
    $x = $left + [int]($width / 2)
    $high = $top + [int]($height * 0.2); $low = $top + [int]($height * 0.8)
    $from = if ($Direction -eq 'Down') { $low } else { $high }
    $to = if ($Direction -eq 'Down') { $high } else { $low }
    [void](Invoke-Adb @('shell', 'input', 'swipe', "$x", "$from", "$x", "$to", '250'))
    Start-Sleep -Milliseconds 150
    return $true
}

function Find-AppNode {
    param([string]$XPath, [ValidateSet('Up', 'Down')][string]$FirstDirection = 'Down')
    $phaseDeadline = [Math]::Min($phaseDeadline, (Get-MonotonicMilliseconds) + 10000)
    # Search four viewports in one direction, then sweep back through eight.
    # Every retry uses fresh bounds; a missing control is a bounded failure.
    for ($attempt = 0; $attempt -le 12; $attempt++) {
        [xml]$document = Get-WindowXml
        $node = $document.SelectSingleNode($XPath)
        if ($null -ne $node) { return $node }
        if ($attempt -eq 12) { break }
        $direction = if ($attempt -lt 4) { $FirstDirection } elseif ($FirstDirection -eq 'Down') { 'Up' } else { 'Down' }
        if (-not (Scroll-AppPage -Document $document -Direction $direction)) { break }
    }
    return $null
}

function Select-Backend {
    param([string]$Choice)
    $targetText = switch ($Choice) {
        'Shizuku' { 'Shizuku 服务' }
        'Stellar' { 'Stellar（原生 API）' }
        default { '自动（推荐）' }
    }
    $spinner = Find-AppNode -XPath "//node[@package='$packageName' and @resource-id='$packageName`:id/backend_spinner']" -FirstDirection Up
    if ($null -eq $spinner) { Throw-VerificationError 'Backend spinner was not found.' }
    if ([string]$spinner.text -eq $targetText -or $null -ne $spinner.SelectSingleNode(".//node[@text='$targetText']")) { return }
    Tap-Node -Node $spinner
    Start-Sleep -Milliseconds 300
    # Spinner menu labels can be non-clickable children of a selectable row.
    [xml]$document = Get-WindowXml
    $item = $document.SelectSingleNode("//node[@package='$packageName' and @text='$targetText' and @enabled='true']")
    if ($null -eq $item) { Throw-VerificationError 'Requested backend menu item was not found.' }
    Tap-Node -Node $item
}

function Wait-ForUiText {
    param([string]$Pattern, [int]$TimeoutSeconds, [ValidateSet('Up', 'Down')][string]$FirstDirection = 'Down')
    # Scope this tighter deadline to this wait, including both ADB calls per dump.
    $phaseDeadline = [Math]::Min($phaseDeadline, (Get-MonotonicMilliseconds) + $TimeoutSeconds * 1000)
    $scrolls = 0
    do {
        [xml]$document = Get-WindowXml
        # Only App text can satisfy an assertion; not another app's overlay.
        $texts = @($document.SelectNodes("//node[@package='$packageName']") | ForEach-Object { [string]$_.text }) -join "`n"
        if ($texts -match $Pattern) { return }
        if ($scrolls -lt 12) {
            $direction = if ($scrolls -lt 4) { $FirstDirection } elseif ($FirstDirection -eq 'Down') { 'Up' } else { 'Down' }
            if (Scroll-AppPage -Document $document -Direction $direction) { $scrolls++; continue }
            $scrolls = 12
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-MonotonicMilliseconds) -lt $phaseDeadline)
    Throw-VerificationError 'Timed out waiting for the expected App state.'
}

function Expand-Diagnostics {
    $toggle = Find-AppNode -XPath "//node[@package='$packageName' and @resource-id='$packageName`:id/diagnostics_toggle' and @clickable='true' and @enabled='true']"
    if ($null -eq $toggle) { Throw-VerificationError 'App diagnostics toggle is not available.' }
    if ([string]$toggle.text -eq '收起诊断信息 ▾') { return }
    if ([string]$toggle.text -ne '诊断信息 ▸') { Throw-VerificationError 'App diagnostics toggle has an unexpected state.' }
    Tap-Node -Node $toggle
    Wait-ForUiText -Pattern '(?m)^收起诊断信息 ▾$' -TimeoutSeconds 2
}

function Get-ProcessPid {
    param([string]$ProcessName)
    $output = Invoke-Adb -Arguments @('shell', 'pidof', $ProcessName) -AllowMissingProcess
    if ($output -and $output.Trim() -notmatch '^\d+(?:\s+\d+)*$') { Throw-VerificationError 'Unexpected process query response.' }
    return $output.Trim()
}

function Assert-NoUserService {
    $stellarPid = Get-ProcessPid $stellarProcess
    $shizukuPid = Get-ProcessPid $shizukuProcess
    if ($stellarPid -or $shizukuPid) { Throw-VerificationError 'A UserService process is still alive.' }
}

function Confirm-AppStop {
    Tap-Text -Text '停用并退出服务'
    Wait-ForUiText -Pattern '停止完成：服务已退出，不会自动重连' -TimeoutSeconds 15
    Expand-Diagnostics
    Wait-ForUiText -Pattern '已确认 UserService Binder 死亡' -TimeoutSeconds 8
    Assert-NoUserService
}

$runState = @{ EnableAttempted = $false }
$deviceArgs = @()
$deviceSelected = $false
$testPassed = $false
$failure = $null
$recovery = 'NOT_REQUIRED (no enable tap was attempted)'
$stage = 'preflight'
$phaseDeadline = (Get-MonotonicMilliseconds) + 180000
try {
    if (-not (Test-Path -LiteralPath $adb)) { Throw-VerificationError 'Missing project-local adb.' }
    if (-not $SkipInstall) { Assert-VerificationApkSignature -Apk $Apk -SdkRoot (Join-Path $projectRoot '.tools/android-sdk') }
    $deviceRows = Invoke-Adb @('devices')
    $onlineDevices = @($deviceRows -split "`r?`n" | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
    })
    if ($Serial) {
        if ($onlineDevices -notcontains $Serial) { Throw-VerificationError 'The selected ADB device is not online.' }
    } elseif ($onlineDevices.Count -eq 1) { $Serial = $onlineDevices[0] }
    elseif ($onlineDevices.Count -eq 0) { Throw-VerificationError 'No online ADB device. Connect a device first.' }
    else { Throw-VerificationError 'Multiple devices are online. Select one explicitly with -Serial.' }
    $deviceArgs = @('-s', $Serial)
    $deviceSelected = $true
    # Never take ownership of a service left running before this script.
    Assert-NoUserService
    if (-not $SkipInstall) { [void](Invoke-Adb -Arguments @('install', '-r', $Apk) -CommandTimeoutMilliseconds 60000) }

    $stage = 'opening the App and checking backend permission'
    [void](Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$packageName/.MainActivity"))
    Wait-ForUiText -Pattern 'SHIZUKU|开启接管' -TimeoutSeconds 8
    Select-Backend -Choice $Backend
    Tap-Text -Text '刷新状态'
    Start-Sleep -Milliseconds 500
    $initialUi = Get-WindowXml
    if ($initialUi -match '授权：尚未允许') { Throw-VerificationError 'Grant the selected backend permission in the App, then retry.' }
    if ($initialUi -match '未运行|没有可用的权限后端') { Throw-VerificationError 'The selected privilege backend is not running.' }

    $stage = 'enabling the service'
    Tap-Text -Text '开启接管' -MayEnableService
    if ($Backend -eq 'Shizuku') {
        Start-Sleep -Milliseconds 500
        if ((Get-WindowXml) -match 'Binder 来源无法唯一确认') {
            Throw-VerificationError 'Shizuku source is ambiguous. This test does not stop or change permission managers.'
        }
    }
    $stage = 'checking service identity'
    Expand-Diagnostics
    Wait-ForUiText -Pattern ([regex]::Escape("服务版本：$($metadata.BuildLabel)（协议 $($metadata.ProtocolVersion)）")) -TimeoutSeconds 12
    Wait-ForUiText -Pattern ([regex]::Escape("服务代：$($metadata.ServiceGeneration)")) -TimeoutSeconds 8
    Wait-ForUiText -Pattern '控制器：运行中' -TimeoutSeconds 8
    $stage = 'checking backend mutual exclusion'
    $stellarPidBefore = Get-ProcessPid $stellarProcess
    $shizukuPidBefore = Get-ProcessPid $shizukuProcess
    if ($stellarPidBefore -and $shizukuPidBefore) { Throw-VerificationError 'Both backend UserServices are alive; backend mutual exclusion failed.' }
    if (-not $stellarPidBefore -and -not $shizukuPidBefore) { Throw-VerificationError 'Running UI has no UserService PID.' }
    if ($Backend -eq 'Shizuku' -and -not $shizukuPidBefore) { Throw-VerificationError 'Selected Shizuku UserService is missing.' }
    if ($Backend -eq 'Stellar' -and -not $stellarPidBefore) { Throw-VerificationError 'Selected Stellar UserService is missing.' }

    $stage = 'confirming App stop'
    Confirm-AppStop
    $stage = 'returning to the page (not Activity recreation or process cold start)'
    [void](Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$packageName/.MainActivity"))
    Start-Sleep -Seconds 2
    Wait-ForUiText -Pattern '状态：已停止|停止完成：服务已退出，不会自动重连' -TimeoutSeconds 8
    Assert-NoUserService
    $testPassed = $true
} catch {
    # XML / native exceptions may contain full UI text, URLs or device IDs.
    $failure = if ($_.Exception.Data['VerifyStopSafe']) { $_.Exception.Message } else { "Failure during $stage; raw diagnostic output suppressed." }
} finally {
    if ($runState.EnableAttempted -and -not $testPassed) {
        # Separate budget; verification deadline exhaustion cannot skip recovery.
        $phaseDeadline = (Get-MonotonicMilliseconds) + 30000
        $recovery = 'UNCONFIRMED (use the App stop button manually when reachable)'
        for ($attempt = 0; $attempt -lt 2; $attempt++) {
            if ((Get-MonotonicMilliseconds) -ge $phaseDeadline) { break }
            try {
                [void](Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$packageName/.MainActivity"))
                Wait-ForUiText -Pattern '停用并退出服务' -TimeoutSeconds 5 -FirstDirection Up
                Confirm-AppStop
                $recovery = 'CONFIRMED (App reports Binder death; both UserService processes absent)'
                break
            } catch { # Keep the original assertion failure, not raw recovery output.
            }
        }
    }
    if ($deviceSelected) {
        # Remove only this invocation's exact UI dump; no glob, recursive delete,
        # manager mutation, or other device file is touched. Failure is non-fatal.
        $phaseDeadline = (Get-MonotonicMilliseconds) + 2000
        try { [void](Invoke-Adb @('shell', 'rm', '-f', $remoteXml)) } catch { }
    }
}

if ($failure) {
    throw "VERIFICATION FAILED: $failure RECOVERY: $recovery"
}
$activeBackend = if ($shizukuPidBefore) { 'Shizuku' } else { 'Stellar' }
Write-Host "PASS: $($metadata.BuildLabel) backend=$activeBackend; one UserService started; App reports Binder death and both UserService processes are absent; returned to page and stayed stopped."
Write-Host 'NOT TESTED: Activity recreation; real app-process cold start. am start may reuse the existing Activity/process.'
