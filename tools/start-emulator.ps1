param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'

$projectPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolchainPath = Join-Path (Split-Path $projectPath) '.android-toolchain'
$sdkPath = Join-Path $toolchainPath 'sdk'
$emulatorPath = Join-Path $sdkPath 'emulator\emulator.exe'
$adbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
$avdPath = Join-Path $toolchainPath 'avd'
$avdName = 'FoodLogTest'
$appId = 'com.foodlog.app'
$transcribing = $false
$ownsMutex = $false
$mutex = $null
$logPath = $null
$exitCode = 0

function Invoke-Adb {
    param([string[]]$AdbArguments, [switch]$AllowFailure)
    # WinPS 5.1 otherwise treats normal adb daemon/boot stderr as exceptions.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $adbPath @AdbArguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $result = [pscustomobject]@{ Code = $code; Output = ($lines -join "`r`n").Trim() }
    if ($code -ne 0 -and -not $AllowFailure) { throw "adb $($AdbArguments -join ' ')`r`n$($result.Output)" }
    return $result
}

function Find-ProjectDevice {
    $devices = Invoke-Adb -AdbArguments @('devices')
    foreach ($line in ($devices.Output -split '\r?\n')) {
        if ($line -match '^(emulator-\d+)\s+device$') {
            $candidate = $Matches[1]
            $name = Invoke-Adb -AdbArguments @('-s', $candidate, 'emu', 'avd', 'name') -AllowFailure
            if ($name.Code -eq 0 -and (($name.Output -split '\r?\n' | ForEach-Object { $_.Trim() }) -contains $avdName)) { return $candidate }
        }
    }
    return $null
}

function Get-ProjectProcesses {
    # Match both the SDK location and exact AVD argument, not all emulators.
    $avdPattern = '(?:^|\s)(?:-avd\s+"?' + [regex]::Escape($avdName) + '"?(?=\s|$)|@' + [regex]::Escape($avdName) + '(?=\s|$))'
    @(Get-CimInstance Win32_Process -Filter "Name = 'emulator.exe' OR Name LIKE 'qemu-system%exe'" |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($sdkPath + '\', [StringComparison]::OrdinalIgnoreCase) -and $_.CommandLine -match $avdPattern })
}

function Get-FreeEmulatorPort {
    for ($port = 5554; $port -le 5680; $port += 2) {
        $listeners = @()
        try {
            foreach ($number in @($port, ($port + 1))) {
                $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $number)
                $listener.Start(); $listeners += $listener
            }
            return $port
        } catch { } finally { foreach ($listener in $listeners) { $listener.Stop() } }
    }
    throw 'No free Android emulator port found.'
}

try {
    foreach ($required in @($emulatorPath, $adbPath, (Join-Path $avdPath "$avdName.ini"))) {
        if (-not (Test-Path -LiteralPath $required)) { throw "Required file missing: $required`r`nKeep this launcher with the food project and its .android-toolchain folder." }
    }
    $apk = Get-ChildItem -LiteralPath (Join-Path $projectPath 'deliverables') -Filter '*-debug.apk' |
        Sort-Object @{ Expression = { if ($_.Name -match '-(\d+\.\d+\.\d+)-debug\.apk$') { [version]$Matches[1] } else { [version]'0.0.0' } }; Descending = $true }, @{ Expression = { $_.LastWriteTime }; Descending = $true } |
        Select-Object -First 1
    if (-not $apk) { throw 'No debug APK found in android-app\deliverables.' }
    if ($CheckOnly) {
        Write-Host "Emulator ready: $emulatorPath"
        Write-Host "Android device: $avdName (Android 15)"
        Write-Host "APK ready: $($apk.FullName)"
        Write-Host 'Saved app records will be preserved. Headless instances will be reopened visibly.'
    } else {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $workspaceKey = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectPath.ToLowerInvariant()))).Replace('-', '').Substring(0, 20) } finally { $sha.Dispose() }
        $mutex = [Threading.Mutex]::new($false, "Local\FoodLogPcTest-$workspaceKey")
        try { $ownsMutex = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
        if (-not $ownsMutex) { throw 'FoodLog is already starting. Please wait for the first launcher window.' }

        $logDirectory = Join-Path $projectPath 'logs'
        New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
        $logPath = Join-Path $logDirectory ("pc-test-{0}-{1}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $PID)
        Start-Transcript -Path $logPath | Out-Null
        $transcribing = $true
        Write-Host "FoodLog PC Test Launcher`r`nLog: $logPath`r`n"
        Write-Host '[1/5] Checking Android environment...'
        Write-Host "APK: $($apk.FullName)"
        $env:ANDROID_HOME = $sdkPath
        $env:ANDROID_SDK_ROOT = $sdkPath
        $env:ANDROID_AVD_HOME = $avdPath
        Invoke-Adb -AdbArguments @('start-server') | Out-Null

        Write-Host '[2/5] Opening the Android emulator window...'
        $serial = Find-ProjectDevice
        $processes = @(Get-ProjectProcesses)
        if (-not $serial -and $processes.Count -gt 0) {
            Write-Host 'An existing emulator is starting. Waiting for its connection...'
            $connectionDeadline = (Get-Date).AddSeconds(90)
            do {
                Start-Sleep -Seconds 2
                $serial = Find-ProjectDevice
                $processes = @(Get-ProjectProcesses)
            } while (-not $serial -and $processes.Count -gt 0 -and (Get-Date) -lt $connectionDeadline)
            if (-not $serial -and $processes.Count -gt 0) { throw 'The existing FoodLogTest emulator is not responding. Close its window, then run this launcher again.' }
        }
        if ($serial -and ($processes | Where-Object { $_.Name -like '*headless*' -or $_.CommandLine -match '(?:^|\s)-(?:no-window|qt-hide-window)(?:\s|$)' })) {
            Write-Host 'Reopening the background test emulator with a visible window. Saved records are kept.'
            Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'am', 'force-stop', $appId) | Out-Null
            Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'sync') | Out-Null
            Invoke-Adb -AdbArguments @('-s', $serial, 'emu', 'kill') | Out-Null
            $stopDeadline = (Get-Date).AddSeconds(45)
            do { Start-Sleep -Seconds 1; $processes = @(Get-ProjectProcesses) } while ($processes.Count -gt 0 -and (Get-Date) -lt $stopDeadline)
            if ($processes.Count -gt 0) { throw 'The old emulator is still shutting down. Wait a moment and run this launcher again.' }
            $serial = $null
        }
        if (-not $serial) {
            $port = Get-FreeEmulatorPort
            $serial = "emulator-$port"
            # Visible interaction is the purpose of this user-invoked launcher.
            Start-Process -FilePath $emulatorPath -WindowStyle Normal -ArgumentList @(
                '-avd', $avdName, '-port', "$port", '-no-audio', '-no-boot-anim',
                '-gpu', 'swiftshader', '-memory', '2048', '-cores', '2', '-no-snapshot'
            ) -RedirectStandardOutput (Join-Path $logDirectory 'emulator-output.log') -RedirectStandardError (Join-Path $logDirectory 'emulator-error.log') | Out-Null
        }

        Write-Host '[3/5] Waiting for Android to finish booting (first launch can take a few minutes)...'
        $deadline = (Get-Date).AddMinutes(6)
        $booted = $false
        $nextProgress = Get-Date
        do {
            $state = Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'getprop', 'sys.boot_completed') -AllowFailure
            if ($state.Code -eq 0 -and $state.Output -eq '1') { $booted = $true; break }
            if ((Get-Date) -ge $nextProgress) {
                Write-Host 'Android is still starting...'
                $nextProgress = (Get-Date).AddSeconds(15)
            }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)
        if (-not $booted) { throw "Android boot timed out. See $logDirectory\emulator-error.log and run the launcher again." }
        $verifiedName = Invoke-Adb -AdbArguments @('-s', $serial, 'emu', 'avd', 'name')
        if (($verifiedName.Output -split '\r?\n' | ForEach-Object { $_.Trim() }) -notcontains $avdName) { throw 'The connected emulator is not FoodLogTest; installation was cancelled.' }

        Write-Host '[4/5] Installing the latest FoodLog APK (keeping saved app records)...'
        $install = Invoke-Adb -AdbArguments @('-s', $serial, 'install', '-r', $apk.FullName)
        if ($install.Output -notmatch '(?m)^Success\s*$') { throw "APK installation did not report success: $($install.Output)" }
        Write-Host $install.Output
        Write-Host '[5/5] Opening FoodLog...'
        Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
        Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'wm', 'dismiss-keyguard') -AllowFailure | Out-Null
        $launch = Invoke-Adb -AdbArguments @('-s', $serial, 'shell', 'am', 'start', '-W', '-n', "$appId/.MainActivity")
        if ($launch.Output -match '(?im)^Error(?:\s|:)|^Exception') { throw "Could not open FoodLog: $($launch.Output)" }
        Write-Host $launch.Output

        # Restore/focus only this AVD if its window was previously minimized.
        Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class FoodLogWindow { [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd); }'
        foreach ($item in @(Get-ProjectProcesses)) {
            $window = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
            if ($window -and $window.MainWindowHandle -ne [IntPtr]::Zero) {
                [FoodLogWindow]::ShowWindowAsync($window.MainWindowHandle, 9) | Out-Null
                [FoodLogWindow]::SetForegroundWindow($window.MainWindowHandle) | Out-Null
            }
        }
        Write-Host "`r`nReady. Use the mouse and keyboard in the Android window to test FoodLog."
        Write-Host 'You can close this console. Closing the emulator keeps saved app records.'
    }
} catch {
    Write-Host "`r`nFoodLog could not start: $($_.Exception.Message)" -ForegroundColor Red
    if ($logPath) { Write-Host "Log: $logPath" }
    $exitCode = 1
} finally {
    if ($transcribing) { Stop-Transcript | Out-Null }
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
exit $exitCode
