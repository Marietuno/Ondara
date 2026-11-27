param(
    [Parameter(Mandatory = $true)][string]$Zip,
    [int]$TrainingTimeoutSeconds = 300
)
# Adds a JVM AOT cache to the portable Windows package: runs the app once in its training mode (it walks through
# the main screens and exits), builds the cache from that recording, and points the launcher at it. Any failure
# leaves the package unchanged, since the app runs fine without the cache.
$ErrorActionPreference = "Stop"
$work = Join-Path ([IO.Path]::GetTempPath()) "ondara-aot"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
$app = Join-Path $work "app"
$data = Join-Path $work "data"
New-Item -ItemType Directory -Force $app, $data | Out-Null
Expand-Archive -Path $Zip -DestinationPath $app

$exe = Join-Path $app "Ondara.exe"
$cfg = Join-Path $app "app\Ondara.cfg"
$config = Join-Path $work "ondara.aotconf"
$cache = Join-Path $app "app\ondara.aot"
# AOT options refuse an explicit -Xshare flag; -Xshare:auto is the JVM default anyway.
(Get-Content $cfg) | Where-Object { $_ -ne "java-options=-Xshare:auto" } | Set-Content -Path $cfg -Encoding ascii
foreach ($name in "share", "config", "cache", "state") { New-Item -ItemType Directory -Force (Join-Path $data $name) | Out-Null }
$env:XDG_DATA_HOME = Join-Path $data "share"
$env:XDG_CONFIG_HOME = Join-Path $data "config"
$env:XDG_CACHE_HOME = Join-Path $data "cache"
$env:XDG_STATE_HOME = Join-Path $data "state"

function Invoke-Ondara([string]$Options, [int]$TimeoutSeconds) {
    $env:JAVA_TOOL_OPTIONS = $Options
    try {
        Start-Process -FilePath $exe | Out-Null
        # With non-default data folders the app relaunches itself once (JVM crash-log location), so wait for every
        # process started from this package, not just the first one.
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        do {
            Start-Sleep -Seconds 1
            $running = Get-Process -Name Ondara -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$app*" }
        } while ($running -and (Get-Date) -lt $deadline)
        if ($running) {
            $running | Stop-Process -Force -ErrorAction SilentlyContinue
            return $false
        }
        return $true
    } finally {
        $env:JAVA_TOOL_OPTIONS = $null
    }
}

if (-not (Invoke-Ondara "-XX:AOTMode=record -XX:AOTConfiguration=`"$config`" -Dondara.aotTraining=true" $TrainingTimeoutSeconds) -or
    -not (Test-Path $config)) {
    Write-Warning "AOT training did not finish; publishing without an AOT cache"
    exit 0
}
if (-not (Invoke-Ondara "-XX:AOTMode=create -XX:AOTConfiguration=`"$config`" -XX:AOTCache=`"$cache`"" 300) -or
    -not (Test-Path $cache)) {
    Write-Warning "AOT cache creation failed; publishing without an AOT cache"
    exit 0
}

# Without AOTMode=on a cache that does not match the runtime is skipped with a warning instead of failing to start.
Add-Content -Path $cfg -Value 'java-options=-XX:AOTCache=$APPDIR\ondara.aot' -Encoding ascii
"AOT cache: {0:N0} MB" -f ((Get-Item $cache).Length / 1MB)

Remove-Item $Zip -Force
Compress-Archive -Path (Join-Path $app "*") -DestinationPath $Zip -CompressionLevel Optimal
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
