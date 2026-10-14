$ErrorActionPreference = "Stop"
$ncnnVersion = "20240820"
$defaultZip = "ncnn-$ncnnVersion-android-vulkan-shared.zip"
$ncnnUrl = "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/$defaultZip"
$targetDir = "app/src/main/cpp/ncnn"
$tempDir = "temp_ncnn_extract"

Write-Host "=== Setting up NCNN Android Vulkan SDK ==="

New-Item -ItemType Directory -Path "app/src/main/cpp" -Force | Out-Null

$localZip = Get-ChildItem -Filter "ncnn-*-android-vulkan-shared.zip" | Select-Object -First 1

if ($localZip) {
    Write-Host "Using existing archive: $($localZip.Name)"
    $zipToExtract = $localZip.FullName
} else {
    Write-Host "Downloading $defaultZip from $ncnnUrl..."
    curl.exe -L $ncnnUrl -o $defaultZip
    $zipToExtract = (Resolve-Path $defaultZip).Path
}

Write-Host "Extracting $zipToExtract..."
if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
if (Test-Path $targetDir) { Remove-Item $targetDir -Recurse -Force }

Expand-Archive -Path $zipToExtract -DestinationPath $tempDir -Force

$abiRoot = Get-ChildItem -Path $tempDir -Recurse -Directory -Filter "arm64-v8a" | Select-Object -First 1

if ($abiRoot) {
    $parentDir = $abiRoot.Parent.FullName
    Move-Item -Path $parentDir -Destination $targetDir -Force
} else {
    Move-Item -Path $tempDir -Destination $targetDir -Force
}

if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
Write-Host "=== NCNN SDK successfully installed at $targetDir ==="
Get-ChildItem -Path $targetDir
