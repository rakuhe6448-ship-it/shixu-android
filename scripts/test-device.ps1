param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sdkRoot = if (Test-Path (Join-Path $projectRoot '.tools\android-sdk')) { Join-Path $projectRoot '.tools\android-sdk' } else { $env:ANDROID_HOME }
if (-not $sdkRoot) { throw 'Set ANDROID_HOME to your Android SDK.' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
foreach ($apk in @('app\build\outputs\apk\debug\app-debug.apk', 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) {
    & $adb -s $Serial install -r (Join-Path $projectRoot $apk)
    if ($LASTEXITCODE -ne 0) { throw ('Install failed: ' + $apk) }
}
$output = & $adb -s $Serial shell am instrument -w cn.shixu.app.test/cn.shixu.app.TestRunner
$output | Write-Output
$logFolder = Join-Path $projectRoot 'app\build\reports\device'
New-Item -ItemType Directory -Path $logFolder -Force | Out-Null
[IO.File]::WriteAllLines((Join-Path $logFolder 'instrumentation.txt'), $output, (New-Object Text.UTF8Encoding $false))
if (($output -join "`n") -notmatch 'OK \(\d+ tests?\)' -or ($output -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw 'Device tests failed. Read app/build/reports/device/instrumentation.txt.' }
