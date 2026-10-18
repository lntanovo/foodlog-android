param([switch]$Connected, [string]$Proxy = '127.0.0.1:7897')
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolchainPath = Join-Path (Split-Path $projectPath) '.android-toolchain'
$jdkPath = Join-Path $toolchainPath 'jdk\jdk-17.0.20.1+1'
if (Test-Path -LiteralPath $jdkPath) { $env:JAVA_HOME = $jdkPath }
if (-not $env:JAVA_HOME) { throw '请安装JDK17并设置JAVA_HOME，或运行tools/bootstrap.py。' }
$sdkPath = Join-Path $toolchainPath 'sdk'
if (Test-Path -LiteralPath $sdkPath) {
    $env:ANDROID_HOME = $sdkPath
    Set-Content -LiteralPath (Join-Path $projectPath 'local.properties') -Value ('sdk.dir=' + $sdkPath.Replace('\','/')) -Encoding utf8
}
$gradleArgs = @('--console=plain','assembleDebug','testDebugUnitTest','lintDebug')
if ($Connected) { $gradleArgs += 'connectedDebugAndroidTest' }
if ($Proxy) {
    $proxyHostName,$proxyPortNumber = $Proxy.Split(':')
    $gradleArgs += @("-Dhttp.proxyHost=$proxyHostName","-Dhttp.proxyPort=$proxyPortNumber","-Dhttps.proxyHost=$proxyHostName","-Dhttps.proxyPort=$proxyPortNumber")
}
Push-Location $projectPath
try {
    & '.\gradlew.bat' @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw '构建或测试失败，请查看上方日志。' }
} finally { Pop-Location }
