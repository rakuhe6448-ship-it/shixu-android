$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$drive = @('V:', 'W:', 'X:') | Where-Object { -not (Test-Path ($_ + '\')) } | Select-Object -First 1
if (-not $drive) { throw 'No free emulator drive letter.' }
& subst.exe $drive $projectRoot
if ($LASTEXITCODE -ne 0) { throw 'Cannot map emulator directory.' }
$env:ANDROID_HOME = $drive + '\.tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = $drive + '\.tools\android-user'
$env:ANDROID_AVD_HOME = $drive + '\.tools\avd'
$avdPath = Join-Path $env:ANDROID_AVD_HOME 'ShiXu_API_35.avd'
New-Item -ItemType Directory -Force $avdPath,$env:ANDROID_USER_HOME | Out-Null
$utf8 = New-Object Text.UTF8Encoding $false
[IO.File]::WriteAllText((Join-Path $env:ANDROID_AVD_HOME 'ShiXu_API_35.ini'), "avd.ini.encoding=UTF-8`npath=$avdPath`ntarget=android-35`n", $utf8)
$config = @'
AvdId=ShiXu_API_35
avd.ini.displayname=ShiXu Android 15 Test
abi.type=x86_64
tag.id=default
tag.display=Default
image.sysdir.1=system-images/android-35/default/x86_64/
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.audioInput=no
hw.camera.back=none
hw.camera.front=none
disk.dataPartition.size=3G
showDeviceFrame=no
fastboot.forceColdBoot=yes
PlayStore.enabled=false
'@
[IO.File]::WriteAllText((Join-Path $avdPath 'config.ini'),$config,$utf8)
$emulatorPath = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$outputLog = Join-Path $projectRoot '.tools\emulator-output.log'
$errorLog = Join-Path $projectRoot '.tools\emulator-error.log'
$emulatorProcess = Start-Process -FilePath $emulatorPath -ArgumentList '-avd','ShiXu_API_35','-no-window','-no-audio','-gpu','swiftshader','-feature','-Vulkan','-no-boot-anim','-no-snapshot','-memory','2048','-cores','2','-timezone','Asia/Shanghai','-port','5554' -WindowStyle Hidden -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog -PassThru
@{ drive=$drive; pid=$emulatorProcess.Id; avd='ShiXu_API_35' } | ConvertTo-Json | Set-Content (Join-Path $projectRoot '.tools\emulator-state.json') -Encoding UTF8
$emulatorProcess | Select-Object Id,StartTime
