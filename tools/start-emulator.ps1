param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'

$projectPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolchainPath = Join-Path (Split-Path $projectPath) '.android-toolchain'
$sdkPath = Join-Path $toolchainPath 'sdk'
$emulatorPath = Join-Path $sdkPath 'emulator\emulator.exe'
$adbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
$avdPath = Join-Path $toolchainPath 'avd'
$avdName = 'FoodLogTest'
$apk = Get-ChildItem -LiteralPath (Join-Path $projectPath 'deliverables') -Filter '*-debug.apk' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
foreach ($required in @($emulatorPath, $adbPath, (Join-Path $avdPath "$avdName.ini"))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required file missing: $required" }
}
if (-not $apk) { throw 'No debug APK found in deliverables.' }
if ($CheckOnly) {
    Write-Host "Emulator ready: $emulatorPath"
    Write-Host "Android device: $avdName (Android 15)"
    Write-Host "APK ready: $($apk.FullName)"
    Write-Host 'The launcher preserves existing emulator records.'
    exit 0
}

$env:ANDROID_HOME = $sdkPath
$env:ANDROID_SDK_ROOT = $sdkPath
$env:ANDROID_AVD_HOME = $avdPath
& $adbPath start-server | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not start Android Debug Bridge.' }

# Reuse only this project's AVD; never install into an attached physical phone.
$serial = $null
$devices = & $adbPath devices
foreach ($line in $devices) {
    if ($line -match '^(emulator-\d+)\s+device$') {
        $candidate = $Matches[1]
        $deviceName = & $adbPath -s $candidate emu avd name 2>$null
        if ($deviceName -contains $avdName) { $serial = $candidate; break }
    }
}
if (-not $serial) {
    $selectedPort = $null
    for ($candidatePort = 5554; $candidatePort -le 5680; $candidatePort += 2) {
        $listeners = @()
        try {
            foreach ($number in @($candidatePort, ($candidatePort + 1))) {
                $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $number)
                $listener.Start()
                $listeners += $listener
            }
            $selectedPort = $candidatePort
        } catch { $selectedPort = $null }
        finally { foreach ($listener in $listeners) { $listener.Stop() } }
        if ($null -ne $selectedPort) { break }
    }
    if ($null -eq $selectedPort) { throw 'No free Android emulator port found.' }
    $serial = "emulator-$selectedPort"
    Write-Host 'Opening the Android emulator window. Please wait for Android to finish booting...'
    # Visible window is intentional: the user starts this interactive test launcher.
    Start-Process -FilePath $emulatorPath -WindowStyle Normal -ArgumentList @(
        '-avd', $avdName, '-port', "$selectedPort", '-no-audio', '-no-boot-anim',
        '-gpu', 'swiftshader', '-memory', '2048', '-cores', '2', '-no-snapshot'
    ) | Out-Null
}

$deadline = (Get-Date).AddMinutes(6)
$booted = $false
do {
    $savedErrorPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 can treat adb's expected boot-time stderr as a
        # terminating NativeCommandError when ErrorActionPreference is Stop.
        $ErrorActionPreference = 'SilentlyContinue'
        $bootState = & $adbPath -s $serial shell getprop sys.boot_completed 2>$null
    } finally { $ErrorActionPreference = $savedErrorPreference }
    if (($bootState -join '').Trim() -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if (-not $booted) { throw 'Android boot timed out. Check the emulator window, then run this launcher again.' }

Write-Host 'Installing FoodLog (existing app records are preserved)...'
& $adbPath -s $serial install -r $apk.FullName | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Existing records were not cleared.' }
& $adbPath -s $serial shell am start -W -n 'com.foodlog.app/.MainActivity' | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not open FoodLog.' }
Write-Host 'FoodLog is ready in the emulator. You can close this console window.'
