$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$releaseRoot = Join-Path $projectRoot 'releases'
$builtApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $builtApk)) { throw 'Build assembleDebug before packaging.' }
New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
$metadata = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\output-metadata.json') | ConvertFrom-Json
$version = $metadata.elements[0].versionName
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Unexpected APK version.' }
$apkPath = Join-Path $releaseRoot ('ShiXu-' + $version + '-debug.apk')
$zipPath = Join-Path $releaseRoot ('ShiXu-' + $version + '-source.zip')
Copy-Item -LiteralPath $builtApk -Destination $apkPath -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$rootFiles = @('.gitignore', '.gitattributes', 'README.md', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app\build.gradle.kts')
$files = @($rootFiles | ForEach-Object { Get-Item -LiteralPath (Join-Path $projectRoot $_) })
foreach ($folder in @('app\src', 'app\schemas', 'gradle', 'docs', 'scripts')) {
    $files += Get-ChildItem -LiteralPath (Join-Path $projectRoot $folder) -File -Recurse
}
$stream = [IO.File]::Open($zipPath, [IO.FileMode]::Create, [IO.FileAccess]::ReadWrite)
$archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, ('shixu-android/' + $relative), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose(); $stream.Dispose() }
$check = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    foreach ($required in @('gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'app/src/main/AndroidManifest.xml', 'app/build.gradle.kts')) {
        if ($null -eq $check.GetEntry('shixu-android/' + $required)) { throw ('Missing archive entry: ' + $required) }
    }
    if ($check.Entries.FullName -match '(^|/)(\.tools|\.gradle|build|local\.properties)(/|$)|\.(jks|keystore)$') { throw 'Unexpected local data in source archive.' }
    Write-Output ('Source archive entries: ' + $check.Entries.Count)
} finally { $check.Dispose() }
$lines = @($apkPath, $zipPath) | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256
    $hash.Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($_)
}
[IO.File]::WriteAllLines((Join-Path $releaseRoot 'SHA256SUMS.txt'), $lines, (New-Object Text.UTF8Encoding $false))
Get-Item -LiteralPath $apkPath, $zipPath | Select-Object Name, Length
