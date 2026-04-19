$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $RootDir ".gradle-logs"
$StdoutLog = Join-Path $LogDir "gradle-stdout.log"
$StderrLog = Join-Path $LogDir "gradle-stderr.log"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Set-Content -Path $StdoutLog -Value ""
Set-Content -Path $StderrLog -Value ""

if (-not $env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path $RootDir ".gradle-user-home"
}

$SdkmanRc = Join-Path $RootDir ".sdkmanrc"
$SdkmanInit = Join-Path $HOME ".sdkman\\bin\\sdkman-init.ps1"
if ((Test-Path $SdkmanRc) -and (Test-Path $SdkmanInit)) {
    . $SdkmanInit
    sdk env install false | Out-Null
}

$Arguments = @("--no-daemon") + $args + @("--console=plain", "--stacktrace")
$GradlePath = Join-Path $RootDir "gradlew.bat"
$DaemonRoot = Join-Path $env:GRADLE_USER_HOME "daemon"

function Get-LatestDaemonLog {
    if (-not (Test-Path $DaemonRoot)) {
        return $null
    }
    return Get-ChildItem -Path $DaemonRoot -Recurse -Filter "*.log" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Read-TextFileOrEmpty {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        return ""
    }

    $content = Get-Content $Path -Raw
    if ($null -eq $content) {
        return ""
    }

    return [string]$content
}

function Test-WrappedGradleSuccess {
    param(
        [int]$ExitCode,
        [string]$StdoutPath,
        [string]$StderrPath
    )

    if ($ExitCode -eq 0) {
        return $true
    }

    [string]$stdoutText = Read-TextFileOrEmpty $StdoutPath
    [string]$stderrText = Read-TextFileOrEmpty $StderrPath

    if ($stdoutText -match "FAILURE: Build failed" -or $stderrText.Trim().Length -gt 0) {
        return $false
    }

    $daemonLog = Get-LatestDaemonLog
    if ($null -eq $daemonLog) {
        return $false
    }

    [string]$daemonTail = Read-TextFileOrEmpty $daemonLog.FullName
    return $daemonTail -match "dispatching the build result: Success"
}

Push-Location $RootDir
try {
    $process = Start-Process -FilePath $GradlePath `
        -ArgumentList $Arguments `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $StdoutLog `
        -RedirectStandardError $StderrLog

    if (Test-Path $StdoutLog) {
        Get-Content $StdoutLog
    }
    if (Test-Path $StderrLog) {
        $stderrLines = Get-Content $StderrLog
        if ($stderrLines.Count -gt 0) {
            $stderrLines | Write-Error
        }
    }

    if (Test-WrappedGradleSuccess -ExitCode $process.ExitCode -StdoutPath $StdoutLog -StderrPath $StderrLog) {
        exit 0
    }

    $daemonLog = Get-LatestDaemonLog
    if ($null -ne $daemonLog) {
        Write-Output "--- daemon tail: $($daemonLog.FullName) ---"
        Get-Content $daemonLog.FullName -Tail 120
    }

    exit $process.ExitCode
} finally {
    Pop-Location
}
