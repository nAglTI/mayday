param(
    [string]$OutputRoot = (Join-Path (Get-Location) "docs\internalDocs\device_apks"),
    [string[]]$Devices = @(),
    [switch]$IncludeSystem
)

$ErrorActionPreference = "Continue"

function ConvertTo-SafePathSegment {
    param([string]$Value)
    return (($Value -replace '[^A-Za-z0-9._-]', '_').Trim('_'))
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = & adb @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($output)
    }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not available in PATH"
}

if ($Devices.Count -eq 0) {
    $Devices = @(
        (& adb devices) |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -match "`tdevice$" } |
            ForEach-Object { ($_ -split "`t")[0] }
    )
}

if ($Devices.Count -eq 0) {
    throw "No adb devices in 'device' state"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

foreach ($device in $Devices) {
    $safeSerial = ConvertTo-SafePathSegment $device
    $brand = ((Invoke-Adb @("-s", $device, "shell", "getprop", "ro.product.brand")).Output -join "").Trim()
    $model = ((Invoke-Adb @("-s", $device, "shell", "getprop", "ro.product.model")).Output -join "").Trim()
    $safeName = ConvertTo-SafePathSegment "$safeSerial-$brand-$model"
    $deviceDir = Join-Path $OutputRoot "$timestamp-$safeName"
    $apkRoot = Join-Path $deviceDir "apks"
    New-Item -ItemType Directory -Force -Path $apkRoot | Out-Null

    $summary = [ordered]@{
        exported_at = (Get-Date).ToString("o")
        serial = $device
        brand = $brand
        model = $model
        include_system = [bool]$IncludeSystem
        output_dir = $deviceDir
        package_count = 0
        apk_count = 0
        failed_packages = @()
        packages = @()
    }

    $packageFilterArgs = if ($IncludeSystem) {
        @("pm", "list", "packages")
    } else {
        @("pm", "list", "packages", "-3")
    }
    Write-Host "[$device] Reading package list..."
    $packageResult = Invoke-Adb @("-s", $device, "shell", $packageFilterArgs)
    if ($packageResult.ExitCode -ne 0) {
        throw "Failed to list packages for ${device}: $($packageResult.Output -join "`n")"
    }

    $packages = @(
        $packageResult.Output |
            ForEach-Object { "$_".Trim() } |
            Where-Object { $_ -match '^package:(.+)$' } |
            ForEach-Object { $Matches[1] } |
            Sort-Object -Unique
    )
    $summary.package_count = $packages.Count
    Write-Host "[$device] Packages found: $($packages.Count)"

    $index = 0
    foreach ($packageName in $packages) {
        $index += 1
        $safePackage = ConvertTo-SafePathSegment $packageName
        $packageDir = Join-Path $apkRoot $safePackage
        New-Item -ItemType Directory -Force -Path $packageDir | Out-Null

        $pathResult = Invoke-Adb @("-s", $device, "shell", "pm", "path", $packageName)
        if ($pathResult.ExitCode -ne 0) {
            $summary.failed_packages += [ordered]@{
                package_name = $packageName
                stage = "pm path"
                error = ($pathResult.Output -join "`n")
            }
            Write-Host "[$device] [$index/$($packages.Count)] $packageName -> pm path failed"
            continue
        }

        $remotePaths = @(
            $pathResult.Output |
                ForEach-Object { "$_".Trim() } |
                Where-Object { $_ -match '^package:(.+\.apk)$' } |
                ForEach-Object { $Matches[1].Trim() } |
                Sort-Object -Unique
        )

        $packageRecord = [ordered]@{
            package_name = $packageName
            apk_count = 0
            apks = @()
        }

        $apkIndex = 0
        foreach ($remotePath in $remotePaths) {
            $apkIndex += 1
            $remoteFileName = ($remotePath -split '/')[-1]
            if ([string]::IsNullOrWhiteSpace($remoteFileName)) {
                $remoteFileName = "artifact.apk"
            }
            $localFileName = "{0:D2}-{1}" -f $apkIndex, (ConvertTo-SafePathSegment $remoteFileName)
            if (-not $localFileName.EndsWith(".apk")) {
                $localFileName = "$localFileName.apk"
            }
            $localPath = Join-Path $packageDir $localFileName

            Write-Host "[$device] [$index/$($packages.Count)] Pulling $packageName :: $remoteFileName"
            $pullResult = Invoke-Adb @("-s", $device, "pull", $remotePath, $localPath)
            if ($pullResult.ExitCode -ne 0 -or -not (Test-Path $localPath)) {
                $summary.failed_packages += [ordered]@{
                    package_name = $packageName
                    stage = "adb pull"
                    remote_path = $remotePath
                    error = ($pullResult.Output -join "`n")
                }
                continue
            }

            $fileInfo = Get-Item $localPath
            $summary.apk_count += 1
            $packageRecord.apk_count += 1
            $packageRecord.apks += [ordered]@{
                remote_path = $remotePath
                local_path = $fileInfo.FullName
                size_bytes = $fileInfo.Length
            }
        }

        $summary.packages += $packageRecord
    }

    $summaryPath = Join-Path $deviceDir "apk_export_manifest.json"
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8
    Write-Host "[$device] Done. APKs: $($summary.apk_count). Manifest: $summaryPath"
}
