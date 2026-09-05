param(
    [ValidateSet('Debug', 'Release')]
    [string]$Variant = 'Release',
    [switch]$LocalTestSigning
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'build-common.ps1')
$metadata = Get-ProjectMetadata -ProjectRoot $projectRoot
$sdkRoot = Join-Path $projectRoot '.tools/android-sdk'
$gradle = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms/android-35/android.jar'))) {
    throw 'Missing project-local Android SDK platform 35. Run scripts/bootstrap.ps1 first.'
}

$signingNames = @('REDIRECTOR_KEYSTORE', 'REDIRECTOR_STORE_PASSWORD', 'REDIRECTOR_KEY_ALIAS', 'REDIRECTOR_KEY_PASSWORD')
$providedSigning = @($signingNames | Where-Object { -not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($_)) })
if (-not $LocalTestSigning -and $providedSigning.Count -ne 0 -and $providedSigning.Count -ne 4) {
    throw 'Set all four REDIRECTOR_* signing variables, or unset all of them for an unsigned release.'
}
if ($LocalTestSigning -and -not (Test-Path -LiteralPath (Join-Path $projectRoot 'keys/redirector-test.jks'))) {
    throw 'The existing keys/redirector-test.jks is required for -LocalTestSigning. Do not replace it with a new key.'
}
$signingLabel = if ($LocalTestSigning) { 'local-test' } elseif ($Variant -eq 'Debug') { 'debug-signed' } elseif ($providedSigning.Count -eq 4) { 'signed' } else { 'unsigned' }

& (Join-Path $PSScriptRoot 'verify-ui.ps1')
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
$tasks = @((":app:test{0}UnitTest" -f $Variant), (":app:lint{0}" -f $Variant), (":app:assemble{0}" -f $Variant))
$gradleArgs = @('--no-daemon', '--stacktrace', '-p', $projectRoot)
if ($LocalTestSigning) { $gradleArgs += '-PlocalTestSigning=true' }
& $gradle @gradleArgs @tasks
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

$variantName = $Variant.ToLowerInvariant()
$outputDirectory = Join-Path $projectRoot "app/build/outputs/apk/$variantName"
$outputMetadata = Get-Content -LiteralPath (Join-Path $outputDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
if ($outputMetadata.applicationId -ne $metadata.PackageName -or $outputMetadata.elements.Count -ne 1) {
    throw 'Unexpected APK output metadata; expected one APK for the configured application ID.'
}
$element = $outputMetadata.elements[0]
if ([string]$element.versionCode -ne $metadata.VersionCode -or $element.versionName -ne $metadata.VersionName) {
    throw 'APK output metadata version differs from app/build.gradle.'
}
$sourceApk = Join-Path $outputDirectory $element.outputFile
$buildTools = Join-Path $sdkRoot 'build-tools/35.0.0'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$badging = (& $aapt2 dump badging $sourceApk) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 could not inspect the APK.' }
$identityPattern = "package: name='$([regex]::Escape($metadata.PackageName))' versionCode='$($metadata.VersionCode)' versionName='$([regex]::Escape($metadata.VersionName))'"
if ($badging -notmatch $identityPattern) { throw 'Packaged APK identity or version does not match the project.' }
$manifestTree = (& $aapt2 dump xmltree $sourceApk --file AndroidManifest.xml) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 could not inspect the packaged manifest.' }
foreach ($entry in @(
    'dev.codex.mibrowserredirector.stellar.NativeStellarProvider',
    'dev.codex.mibrowserredirector.stellar',
    'rikka.shizuku.ShizukuProvider',
    'dev.codex.mibrowserredirector.shizuku',
    'moe.shizuku.manager.permission.API_V23',
    'moe.shizuku.client.V3_SUPPORT'
)) {
    if (-not $manifestTree.Contains($entry)) { throw "Packaged manifest is missing dual-backend entry: $entry" }
}
if ($signingLabel -eq 'unsigned') {
    # Zip archive check covers v1 signatures; apksigner diagnoses the v2/v3 signing block.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($sourceApk)
    try { $v1Signatures = @($archive.Entries | Where-Object { $_.FullName -match '^META-INF/[^/]+\.(RSA|DSA|EC|SF)$' }) } finally { $archive.Dispose() }
    $signatureOutput = (& (Join-Path $buildTools 'apksigner.bat') verify --verbose $sourceApk 2>&1 | Out-String)
    $signatureExit = $LASTEXITCODE
    if ($signatureExit -eq 0 -or $v1Signatures.Count -gt 0 -or $signatureOutput -notmatch 'Missing META-INF/MANIFEST.MF|No signatures|No JAR signatures') {
        throw "Expected a clearly unsigned APK; signer reported an unexpected result: $signatureOutput"
    }
    Write-Host 'Unsigned release verified; it must be signed before installation.'
} else {
    Assert-SignedApk -Apk $sourceApk -SdkRoot $sdkRoot
}

$dist = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$destApk = Join-Path $dist ("MiBrowserRedirector-{0}-{1}-v{2}.apk" -f $variantName, $signingLabel, $metadata.VersionName)
Copy-Item -LiteralPath $sourceApk -Destination $destApk -Force
$hash = (Get-FileHash -LiteralPath $destApk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath ($destApk + '.sha256') -Encoding ascii -Value ("{0}  {1}" -f $hash, (Split-Path $destApk -Leaf))
Write-Host "APK: $destApk"
Write-Host "Signing: $signingLabel"
Write-Host "SHA256: $hash"
