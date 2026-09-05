param(
    [string]$Apk,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'build-common.ps1')
$metadata = Get-ProjectMetadata -ProjectRoot $projectRoot
$sdkRoot = Join-Path $projectRoot '.tools/android-sdk'
$adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
if (-not $Apk) { $Apk = Join-Path $projectRoot ("dist/MiBrowserRedirector-debug-debug-signed-v{0}.apk" -f $metadata.VersionName) }
if (-not (Test-Path -LiteralPath $adb)) { throw 'Missing project-local adb. Run scripts/bootstrap.ps1 first.' }
Assert-SignedApk -Apk $Apk -SdkRoot $sdkRoot
$deviceRows = & $adb devices
if ($LASTEXITCODE -ne 0) { throw "adb devices failed with exit code $LASTEXITCODE" }
$onlineDevices = @($deviceRows | ForEach-Object { if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] } })
if ($Serial) {
    if ($onlineDevices -notcontains $Serial) { throw "ADB device '$Serial' is not online." }
} elseif ($onlineDevices.Count -eq 1) {
    $Serial = $onlineDevices[0]
} else {
    throw 'Connect exactly one authorized device, or specify -Serial <serial> when multiple devices are connected.'
}
& $adb -s $Serial install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed with exit code $LASTEXITCODE. Check the error above; a different signing certificate cannot update the installed app." }
Write-Host "Installed $Apk on $Serial"
