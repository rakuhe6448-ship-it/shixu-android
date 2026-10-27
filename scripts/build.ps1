param([string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$drive = @('Q:', 'R:', 'S:', 'T:', 'U:') | Where-Object { -not (Test-Path ($_ + '\')) } | Select-Object -First 1
if (-not $drive) { throw 'No free temporary drive letter.' }
$oldLocal = if (Test-Path (Join-Path $projectRoot 'local.properties')) { [IO.File]::ReadAllBytes((Join-Path $projectRoot 'local.properties')) } else { $null }
$previousAndroid = $env:ANDROID_HOME
$previousAndroidUser = $env:ANDROID_USER_HOME
$previousGradle = $env:GRADLE_USER_HOME
$previousDebug = $env:DEBUG
$mapped = $false
$pushed = $false
try {
    & subst.exe $drive $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Cannot map build drive.' }
    $mapped = $true
    Push-Location ($drive + '\')
    $pushed = $true
    if (Test-Path '.tools\android-sdk') {
        $env:ANDROID_HOME = $drive + '\.tools\android-sdk'
        [IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), ('sdk.dir=' + $drive + '/.tools/android-sdk' + [Environment]::NewLine), (New-Object Text.UTF8Encoding $false))
    }
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
    $env:DEBUG = ''
    $proxyOptions = @()
    $windowsProxy = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
    if ($windowsProxy.ProxyEnable -eq 1 -and $windowsProxy.ProxyServer -match '^([^:;=]+):(\d+)$') {
        $proxyOptions = @(('-Dhttps.proxyHost=' + $Matches[1]), ('-Dhttps.proxyPort=' + $Matches[2]), ('-Dhttp.proxyHost=' + $Matches[1]), ('-Dhttp.proxyPort=' + $Matches[2]))
    }
    & .\gradlew.bat @Tasks @proxyOptions --no-daemon --console=plain
    $buildResult = $LASTEXITCODE
} finally {
    if ($pushed) { Pop-Location }
    if ($null -ne $oldLocal) { [IO.File]::WriteAllBytes((Join-Path $projectRoot 'local.properties'), $oldLocal) }
    elseif (Test-Path (Join-Path $projectRoot 'local.properties')) { Remove-Item -LiteralPath (Join-Path $projectRoot 'local.properties') }
    $env:ANDROID_HOME = $previousAndroid
    $env:ANDROID_USER_HOME = $previousAndroidUser
    $env:GRADLE_USER_HOME = $previousGradle
    $env:DEBUG = $previousDebug
    if ($mapped) { & subst.exe $drive /D }
}
exit $buildResult
