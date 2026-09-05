# Fast, pure-Java regression loop for the browser candidate predicate used by MainActivity.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dependencyRoot = Join-Path $projectRoot '.gradle-user-home/caches/modules-2/files-2.1'
$junit = Get-ChildItem (Join-Path $dependencyRoot 'junit/junit/4.13.2') -Recurse -Filter 'junit-4.13.2.jar' | Select-Object -First 1
$hamcrest = Get-ChildItem (Join-Path $dependencyRoot 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter 'hamcrest-core-1.3.jar' | Select-Object -First 1
if (-not $junit -or -not $hamcrest) { throw 'Run a Gradle unit-test build once to populate the project-local test dependencies.' }
$output = Join-Path $projectRoot 'app/build/browser-filter-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = $junit.FullName + [IO.Path]::PathSeparator + $hamcrest.FullName
& javac --release 17 -encoding UTF-8 -cp $classpath -d $output `
    (Join-Path $projectRoot 'app/src/main/java/dev/codex/mibrowserredirector/BrowserEligibility.java') `
    (Join-Path $projectRoot 'app/src/test/java/dev/codex/mibrowserredirector/BrowserEligibilityTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Browser filter test compilation failed.' }
& java -cp ($output + [IO.Path]::PathSeparator + $classpath) org.junit.runner.JUnitCore dev.codex.mibrowserredirector.BrowserEligibilityTest
if ($LASTEXITCODE -ne 0) { throw 'Browser filter regression is RED.' }
