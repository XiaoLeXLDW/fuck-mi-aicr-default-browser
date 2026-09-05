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
$remoteXml = "/sdcard/codex-redirector-window.xml"

if (-not (Test-Path -LiteralPath $adb)) { throw "Missing project-local adb." }
if (-not $SkipInstall) { Assert-SignedApk -Apk $Apk -SdkRoot (Join-Path $projectRoot '.tools/android-sdk') }

$deviceRows = & $adb devices
if ($LASTEXITCODE -ne 0) { throw "adb devices failed with exit code $LASTEXITCODE" }
$onlineDevices = @(
    $deviceRows | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
    }
)
if ($Serial) {
    if ($onlineDevices -notcontains $Serial) { throw "ADB device '$Serial' is not online." }
    $deviceArgs = @("-s", $Serial)
} elseif ($onlineDevices.Count -eq 1) {
    $Serial = $onlineDevices[0]
    $deviceArgs = @("-s", $Serial)
} elseif ($onlineDevices.Count -eq 0) {
    throw "No online ADB device. Open Wireless debugging and run adb connect first."
} else {
    throw "Multiple devices are online. Re-run with -Serial <serial>."
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = & $adb @deviceArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed: $($output -join ' ')"
    }
    return ($output -join "`n")
}

function Get-WindowXml {
    [void](Invoke-Adb @("shell", "uiautomator", "dump", $remoteXml))
    return Invoke-Adb @("exec-out", "cat", $remoteXml)
}

function Get-NodeCenter {
    param([System.Xml.XmlElement]$Node)
    $bounds = [string]$Node.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Unexpected UI bounds: $bounds"
    }
    return @(
        [int](([int]$Matches[1] + [int]$Matches[3]) / 2),
        [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    )
}

function Tap-Node {
    param([System.Xml.XmlElement]$Node)
    $center = Get-NodeCenter -Node $Node
    [void](Invoke-Adb @("shell", "input", "tap", "$($center[0])", "$($center[1])"))
}

function Tap-Text {
    param([string]$Text)
    [xml]$document = Get-WindowXml
    $node = $document.SelectSingleNode("//node[@text='$Text' and @clickable='true']")
    if ($null -eq $node) {
        $node = $document.SelectSingleNode("//node[@text='$Text']")
    }
    if ($null -eq $node) { throw "UI element '$Text' was not found." }
    Tap-Node -Node $node
}

function Select-Backend {
    param([string]$Choice)
    $targetText = switch ($Choice) {
        "Shizuku" { "Shizuku 服务" }
        "Stellar" { "兼容服务（原生 API）" }
        default { "自动（推荐）" }
    }
    [xml]$document = Get-WindowXml
    $spinner = $document.SelectSingleNode(
        "//node[@resource-id='$packageName`:id/backend_spinner']")
    if ($null -eq $spinner) { throw "Backend spinner was not found." }
    if ([string]$spinner.text -eq $targetText) { return }
    Tap-Node -Node $spinner
    Start-Sleep -Milliseconds 300
    Tap-Text -Text $targetText
}

function Wait-ForUiText {
    param(
        [string]$Pattern,
        [int]$TimeoutSeconds
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $xmlText = Get-WindowXml
        if ($xmlText -match $Pattern) { return $xmlText }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out after $TimeoutSeconds seconds waiting for UI pattern: $Pattern"
}

function Get-ProcessPid {
    param([string]$ProcessName)
    $output = & $adb @deviceArgs shell pidof $ProcessName 2>&1
    if ($LASTEXITCODE -eq 1 -and -not $output) { return "" }
    if ($LASTEXITCODE -ne 0) { throw "Cannot query process $ProcessName; check the ADB connection." }
    return (($output | Out-String).Trim())
}

if (-not $SkipInstall) {
    [void](Invoke-Adb @("install", "-r", $apk))
}

[void](Invoke-Adb @("shell", "am", "start", "-W", "-n", "$packageName/.MainActivity"))
[void](Wait-ForUiText -Pattern 'SHIZUKU|开启接管' -TimeoutSeconds 8)
Select-Backend -Choice $Backend
Tap-Text -Text "刷新状态"
Start-Sleep -Milliseconds 500
$initialUi = Get-WindowXml
if ($initialUi -match '授权：尚未允许') {
    throw "Grant the selected backend permission in the app, then run this script again."
}
if ($initialUi -match '未运行|没有可用的权限后端') {
    throw "The selected privilege backend is not running. Start it, then retry."
}

Tap-Text -Text "开启接管"
if ($Backend -eq "Shizuku") {
    Start-Sleep -Milliseconds 500
    $sourceCheckUi = Get-WindowXml
    if ($sourceCheckUi -match 'Binder 来源无法唯一确认') {
        throw "Shizuku source is ambiguous because the native compatibility service is running. Stop Stellar, then retry the isolated Shizuku test."
    }
}
[void](Wait-ForUiText -Pattern ([regex]::Escape("服务版本：$($metadata.BuildLabel)（协议 $($metadata.ProtocolVersion)）")) -TimeoutSeconds 12)
[void](Wait-ForUiText -Pattern ([regex]::Escape("服务代：$($metadata.ServiceGeneration)")) -TimeoutSeconds 2)
$stellarPidBefore = Get-ProcessPid -ProcessName $stellarProcess
$shizukuPidBefore = Get-ProcessPid -ProcessName $shizukuProcess
if ($stellarPidBefore -and $shizukuPidBefore) {
    throw "Both backend UserServices are alive; backend mutual exclusion failed."
}
if (-not $stellarPidBefore -and -not $shizukuPidBefore) {
    throw "$($metadata.BuildLabel) reported running, but neither UserService process has a PID."
}
if ($Backend -eq "Shizuku" -and -not $shizukuPidBefore) {
    throw "Shizuku was selected, but the Shizuku UserService process is missing."
}
if ($Backend -eq "Stellar" -and -not $stellarPidBefore) {
    throw "Stellar was selected, but the Stellar UserService process is missing."
}

Tap-Text -Text "停用并退出服务"
[void](Wait-ForUiText -Pattern ([regex]::Escape("停止完成 [$($metadata.BuildLabel)]")) -TimeoutSeconds 15)
$stellarPidAfter = Get-ProcessPid -ProcessName $stellarProcess
$shizukuPidAfter = Get-ProcessPid -ProcessName $shizukuProcess
if ($stellarPidAfter -or $shizukuPidAfter) {
    throw "Stop UI reported success, but a UserService PID is still alive."
}

[void](Invoke-Adb @("shell", "am", "start", "-W", "-n", "$packageName/.MainActivity"))
Start-Sleep -Seconds 2
$stellarPidAfterReopen = Get-ProcessPid -ProcessName $stellarProcess
$shizukuPidAfterReopen = Get-ProcessPid -ProcessName $shizukuProcess
if ($stellarPidAfterReopen -or $shizukuPidAfterReopen) {
    throw "Stopped app recreated a UserService after reopen."
}

$activeBackend = if ($shizukuPidBefore) { "Shizuku" } else { "Stellar" }
Write-Host "PASS: $($metadata.BuildLabel) backend=$activeBackend; one UserService started; stop confirmed Binder death; reopen stayed stopped."
