# Builds KoHs Anchor's for every Minecraft version in gradle/versions.properties, checks every
# Mixin target against that version's bytecode, and copies the playable jars to dist/<mod_version>/.
#
#   powershell -ExecutionPolicy Bypass -File tools\build-all.ps1
#   powershell -ExecutionPolicy Bypass -File tools\build-all.ps1 -Versions 26.2,26.3

param([string[]]$Versions)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
}

if (-not $Versions) {
    $Versions = Get-Content 'gradle\versions.properties' |
        Where-Object { $_ -match '^\s*[0-9][^#=]*=' } |
        ForEach-Object { ($_ -split '=')[0].Trim() }
}

$modVersion = ((Get-Content 'gradle.properties' | Where-Object { $_ -match '^mod_version=' }) -split '=')[1].Trim()
$dist = Join-Path $root "dist\$modVersion"
New-Item -ItemType Directory -Force $dist | Out-Null

foreach ($version in $Versions) {
    Write-Host "== Minecraft $version" -ForegroundColor Magenta
    # The -P argument has to stay quoted: PowerShell would otherwise split it at the dot.
    & .\gradlew.bat build "-Pmc=$version" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed for Minecraft $version" }
    $jar = "build\libs\kohs-anchors-$version-$modVersion.jar"
    Copy-Item $jar $dist -Force
}

python tools\verify_mixin_targets.py @Versions
if ($LASTEXITCODE -ne 0) { throw 'A Mixin target does not match the Minecraft bytecode' }

Get-ChildItem $dist -Filter *.jar | ForEach-Object {
    $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
    "{0}  {1}" -f $hash, $_.Name
} | Set-Content (Join-Path $dist 'CHECKSUMS.sha256') -Encoding ascii

Write-Host "Jars in $dist" -ForegroundColor Magenta
