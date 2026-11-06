param([string]$ApkPath)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($ApkPath)) { $ApkPath = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk' }
$strictUtf8 = [Text.UTF8Encoding]::new($false,$true)
$source = [IO.File]::ReadAllText((Join-Path $projectRoot 'app/src/main/java/cn/shixu/app/widgets/Widgets.kt'),$strictUtf8)
if ($source -match '\?{2,}|"\?"|\uFFFD') { throw 'Widget renderer contains replacement characters or question-mark placeholders.' }
$strings = [xml][IO.File]::ReadAllText((Join-Path $projectRoot 'app/src/main/res/values/widget_strings.xml'),$strictUtf8)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($ApkPath))
try {
    $entry = $archive.GetEntry('resources.arsc')
    if ($null -eq $entry) { throw 'APK has no compiled resources.' }
    $stream = $entry.Open()
    $memory = New-Object IO.MemoryStream
    try { $stream.CopyTo($memory);$bytes = $memory.ToArray() } finally { $stream.Dispose();$memory.Dispose() }
} finally { $archive.Dispose() }
$utf8Table = [Text.Encoding]::UTF8.GetString($bytes)
$utf16Table = [Text.Encoding]::Unicode.GetString($bytes)
$count = 0
foreach ($entry in $strings.SelectNodes('/resources/string')) {
    $name = $entry.GetAttribute('name')
    $value = $entry.InnerText.Replace('\n',"`n")
    if ([string]::IsNullOrEmpty($value) -or $value.Contains('?') -or $value.Contains(([char]0xFFFD).ToString())) { throw ('Invalid widget text: '+$name) }
    if (-not $utf8Table.Contains($value) -and -not $utf16Table.Contains($value)) { throw ('APK text does not match UTF-8 source: '+$name) }
    $count++
}
if ($count -eq 0) { throw 'No widget strings found.' }
Write-Output ("PASS: $count widget strings verified in compiled APK; no replacement placeholders in renderer.")
