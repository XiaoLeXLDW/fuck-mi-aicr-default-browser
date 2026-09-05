# Shared metadata and signature checks; dot-source from a project script.
function Get-ProjectMetadata {
    param([string]$ProjectRoot)
    $gradleText = Get-Content -LiteralPath (Join-Path $ProjectRoot 'app/build.gradle') -Raw
    $serviceText = Get-Content -LiteralPath (Join-Path $ProjectRoot 'app/src/main/java/dev/codex/mibrowserredirector/ServiceIdentity.java') -Raw
    $metadata = @{}
    $patterns = @{
        PackageName = 'applicationId\s+"([^"]+)"'
        VersionName = 'versionName\s+"([^"]+)"'
        VersionCode = 'versionCode\s+(\d+)'
    }
    foreach ($name in $patterns.Keys) {
        if ($gradleText -notmatch $patterns[$name]) { throw "Cannot read $name from app/build.gradle." }
        $metadata[$name] = $Matches[1]
    }
    $servicePatterns = @{
        BuildLabel = 'BUILD_LABEL\s*=\s*"([^"]+)"'
        ProtocolVersion = 'PROTOCOL_VERSION\s*=\s*([\d_]+)'
        ServiceGeneration = 'USER_SERVICE_GENERATION\s*=\s*([\d_]+)'
    }
    foreach ($name in $servicePatterns.Keys) {
        if ($serviceText -notmatch $servicePatterns[$name]) { throw "Cannot read $name from ServiceIdentity.java." }
        $metadata[$name] = $Matches[1].Replace('_', '')
    }
    if ($metadata.VersionName -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') { throw 'VersionName is not safe for an artifact filename.' }
    if ($metadata.BuildLabel -ne ('v' + $metadata.VersionName)) { throw 'ServiceIdentity.BUILD_LABEL does not match app/build.gradle versionName.' }
    return [pscustomobject]$metadata
}

function Assert-SignedApk {
    param([string]$Apk, [string]$SdkRoot)
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK does not exist: $Apk" }
    $apksigner = Join-Path $SdkRoot 'build-tools/35.0.0/apksigner.bat'
    if (-not (Test-Path -LiteralPath $apksigner)) { throw 'Missing Android build-tools 35.0.0; run scripts/bootstrap.ps1.' }
    & $apksigner verify --verbose $Apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed. Unsigned APKs cannot be installed; build Debug or configure signing.' }
}
