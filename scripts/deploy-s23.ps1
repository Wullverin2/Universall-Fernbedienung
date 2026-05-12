param(
    [string]$DeviceId = "R5CW82ZH8SB",
    [switch]$Fresh
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$gradle = Join-Path $projectRoot "gradlew.bat"
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$packageName = "de.craftplay.universalremote"
$deviceDownloadPath = "/sdcard/Download/Universal-Fernbedienung-debug.apk"

function Resolve-Adb {
    $candidates = @()

    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }

    $candidates += "C:\Users\speed_pctca6b\AppData\Local\Android\Sdk\platform-tools\adb.exe"

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "adb.exe wurde nicht gefunden."
}

$adb = Resolve-Adb

Push-Location $projectRoot
try {
    & $gradle assembleDebug

    if (-not (Test-Path $apk)) {
        throw "APK wurde nicht gefunden: $apk"
    }

    if ($Fresh) {
        & $adb -s $DeviceId uninstall $packageName | Out-Host
    }

    & $adb -s $DeviceId install -r $apk | Out-Host
    & $adb -s $DeviceId push $apk $deviceDownloadPath | Out-Host

    Write-Host "APK installiert und nach $deviceDownloadPath kopiert."
}
finally {
    Pop-Location
}
