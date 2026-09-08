param(
    [switch]$SkipSdkPackages,
    [switch]$AcceptSdkLicenses
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolRoot = Join-Path $projectRoot '.tools'
$downloadRoot = Join-Path $toolRoot 'downloads'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

function Assert-ProjectPath([string]$Path) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $rootPrefix = $projectRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing filesystem operation outside project: $fullPath"
    }
    return $fullPath
}

# Gradle Wrapper checks its pinned distribution SHA256 before extraction.
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $java)) { throw 'JAVA_HOME must point to an installed JDK 17 or newer.' }
& $java -version
if ($LASTEXITCODE -ne 0) { throw "Java failed with exit code $LASTEXITCODE" }
& (Join-Path $projectRoot 'gradlew.bat') --version
if ($LASTEXITCODE -ne 0) { throw "Gradle Wrapper failed with exit code $LASTEXITCODE" }

$sdkManager = Join-Path $sdkRoot 'cmdline-tools/latest/bin/sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null
    $cmdlineZip = Join-Path $downloadRoot 'commandlinetools-win.zip'
    $cmdlineUrl = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
    # This SHA256 was calculated only after matching the archive to Google's
    # repository2-1.xml SHA1 3d2917302740f476999a091bc5558837c7a863c5 (revision 12).
    $expectedHash = '4d6931209eebb1bfb7c7e8b240a6a3cb3ab24479ea294f3539429574b1eec862'
    if (-not (Test-Path -LiteralPath $cmdlineZip)) {
        Invoke-WebRequest -Uri $cmdlineUrl -OutFile $cmdlineZip -UseBasicParsing
    }
    $actualHash = (Get-FileHash -LiteralPath $cmdlineZip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "Android command line tools checksum mismatch: $cmdlineZip. Inspect or remove this download before retrying."
    }
    $extract = Assert-ProjectPath (Join-Path $toolRoot ('cmdline-tools-extract-' + [guid]::NewGuid().ToString('N')))
    Expand-Archive -LiteralPath $cmdlineZip -DestinationPath $extract
    $source = Assert-ProjectPath (Join-Path $extract 'cmdline-tools')
    $latest = Assert-ProjectPath (Join-Path $sdkRoot 'cmdline-tools/latest')
    if (Test-Path -LiteralPath $latest) { throw "Incomplete tools directory exists: $latest. Inspect it before retrying." }
    New-Item -ItemType Directory -Force -Path (Split-Path $latest -Parent) | Out-Null
    Move-Item -LiteralPath $source -Destination $latest
    Remove-Item -LiteralPath $extract
}

if (-not $SkipSdkPackages) {
    if ($AcceptSdkLicenses) {
        $answers = ((1..40 | ForEach-Object { 'y' }) -join [Environment]::NewLine)
        $answers | & $sdkManager "--sdk_root=$sdkRoot" --licenses
    } else {
        & $sdkManager "--sdk_root=$sdkRoot" --licenses
    }
    if ($LASTEXITCODE -ne 0) { throw "SDK license step failed with exit code $LASTEXITCODE" }
    & $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw "SDK package installation failed with exit code $LASTEXITCODE" }
    foreach ($required in @('platforms/android-35/android.jar', 'build-tools/35.0.0/apksigner.bat', 'platform-tools/adb.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot $required))) { throw "SDK package installation is incomplete: $required" }
    }
}

Write-Host "Bootstrap complete. SDK: $sdkRoot"
Write-Host 'Gradle: pinned 8.9 Wrapper. No signing key was created.'
Write-Host 'Build an installable development APK: .\scripts\build.ps1 -Variant Debug'
