param(
    [string]$Serial = "1901092534053723",
    [ValidateSet("start", "stop", "restart", "status", "trigger")]
    [string]$Action = "start"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$localScript = Join-Path $repoRoot "tools\r08-shortcut-bridge.sh"
$remoteScript = "/data/local/tmp/r08-shortcut-bridge.sh"
$packageName = "com.anezium.r08accessbridge"
$bridgeDir = "/sdcard/Android/data/$packageName/files/shortcut_bridge"

if (!(Test-Path -LiteralPath $localScript)) {
    throw "Missing bridge script: $localScript"
}

adb -s $Serial push $localScript $remoteScript | Out-Host
adb -s $Serial shell "chmod 755 $remoteScript" | Out-Host

if ($Action -eq "start" -or $Action -eq "restart") {
    adb -s $Serial shell "rm -rf $bridgeDir" | Out-Host
    adb -s $Serial shell "am start -n $packageName/.BridgeCommandActivity --ez init_shortcut_bridge true --ez exit_after_command true" | Out-Host
    Start-Sleep -Seconds 1
}

adb -s $Serial shell "sh $remoteScript $Action" | Out-Host
