# Build script for Android PTY Library - Native .so compilation (Windows)
# Compiles libterm.c directly with NDK clang for all Android ABIs.

param(
    [string]$NdkPath = ""
)

Write-Host "=== Building Android PTY Native Library ===" -ForegroundColor Green

# Find Android NDK
$NDK_HOME = $NdkPath

if (-not $NDK_HOME) { $NDK_HOME = $env:NDK_HOME }
if (-not $NDK_HOME) { $NDK_HOME = $env:ANDROID_NDK_HOME }
if (-not $NDK_HOME -and $env:ANDROID_HOME) {
    $ndkDir = "$env:ANDROID_HOME\ndk"
    if (Test-Path $ndkDir) {
        $NDK_HOME = (Get-ChildItem $ndkDir -Directory | Sort-Object Name | Select-Object -Last 1).FullName
    }
}
# Fallback to common local path
if (-not $NDK_HOME) {
    $defaultPath = "d:\dev_tools\android\Sdk\ndk"
    if (Test-Path $defaultPath) {
        $NDK_HOME = (Get-ChildItem $defaultPath -Directory | Sort-Object Name | Select-Object -Last 1).FullName
    }
}

if (-not $NDK_HOME -or -not (Test-Path $NDK_HOME)) {
    Write-Host "Error: Android NDK not found." -ForegroundColor Red
    Write-Host "Set NDK_HOME, ANDROID_NDK_HOME, or pass -NdkPath" -ForegroundColor Yellow
    exit 1
}

Write-Host "Using NDK: $NDK_HOME" -ForegroundColor Green

# Find prebuilt toolchain directory
$prebuiltBase = "$NDK_HOME\toolchains\llvm\prebuilt"
$prebuiltDir = (Get-ChildItem $prebuiltBase -Directory | Select-Object -First 1).FullName

if (-not $prebuiltDir) {
    Write-Host "Error: Could not find prebuilt directory in $prebuiltBase" -ForegroundColor Red
    exit 1
}

Write-Host "Toolchain: $prebuiltDir" -ForegroundColor Gray

$SYSROOT = "$prebuiltDir\sysroot"

# Output directories
$LIB_OUTPUT = "android\terminal-lib\src\main\jniLibs"

# Clean previous builds
Write-Host "Cleaning previous builds..." -ForegroundColor Yellow
Remove-Item -Recurse -Force "libs\*" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$LIB_OUTPUT\*" -ErrorAction SilentlyContinue

New-Item -ItemType Directory -Force -Path "libs" | Out-Null

# Architecture configuration
$architectures = @(
    @{ abi = "armeabi-v7a"; cc = "armv7a-linux-androideabi21-clang" }
    @{ abi = "arm64-v8a";   cc = "aarch64-linux-android21-clang" }
    @{ abi = "x86";         cc = "i686-linux-android21-clang" }
    @{ abi = "x86_64";      cc = "x86_64-linux-android21-clang" }
)

foreach ($config in $architectures) {
    $arch = $config.abi
    Write-Host ""
    Write-Host "--- Building for $arch ---" -ForegroundColor Cyan

    # Find compiler (try .cmd, .exe, then bare name)
    $ccBase = "$prebuiltDir\bin\$($config.cc)"
    $CC = $null
    foreach ($ext in @(".cmd", ".exe", "")) {
        if (Test-Path "$ccBase$ext") {
            $CC = "$ccBase$ext"
            break
        }
    }

    if (-not $CC) {
        Write-Host "  Warning: Compiler not found for $arch, skipping" -ForegroundColor Yellow
        continue
    }

    Write-Host "  Compiler: $CC" -ForegroundColor Gray

    # Create output directories
    $archDir = "libs\$arch"
    $jniArchDir = "$LIB_OUTPUT\$arch"
    New-Item -ItemType Directory -Force -Path $archDir | Out-Null
    New-Item -ItemType Directory -Force -Path $jniArchDir | Out-Null

    $outSo = "$archDir\libterm.so"

    # Compile
    & $CC -shared -o $outSo -fPIC -O2 -Wall `
        "-I$SYSROOT\usr\include" `
        "-I$SYSROOT\usr\include\android" `
        native\libterm.c `
        -llog

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Build FAILED for $arch!" -ForegroundColor Red
        exit 1
    }

    # Strip the .so
    $stripExe = $null
    foreach ($ext in @("", ".exe")) {
        $candidate = "$prebuiltDir\bin\llvm-strip$ext"
        if (Test-Path $candidate) {
            $stripExe = $candidate
            break
        }
    }

    if ($stripExe) {
        Write-Host "  Stripping: $outSo" -ForegroundColor Gray
        & $stripExe $outSo
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  Warning: strip failed for $arch" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  Warning: llvm-strip not found, skipping strip" -ForegroundColor Yellow
    }

    # Copy to jniLibs
    Copy-Item $outSo "$jniArchDir\" -Force

    # Compile ftyd daemon executable
    $outDaemon = "$archDir\ftyd"
    & $CC -o $outDaemon -O2 -Wall `
        "-I$SYSROOT\usr\include" `
        "-I$SYSROOT\usr\include\android" `
        native\ftyd.c `
        -llog

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ftyd build FAILED for $arch!" -ForegroundColor Red
        exit 1
    }

    if ($stripExe) {
        Write-Host "  Stripping: $outDaemon" -ForegroundColor Gray
        & $stripExe $outDaemon
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  Warning: strip failed for ftyd on $arch" -ForegroundColor Yellow
        }
    }

    # Report sizes
    $soSize   = [math]::Round((Get-Item $outSo).Length     / 1KB, 1)
    $daemonSize = [math]::Round((Get-Item $outDaemon).Length / 1KB, 1)
    Write-Host "  Done: $arch  (libterm.so: $soSize KB, ftyd: $daemonSize KB)" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Build Complete ===" -ForegroundColor Green
Write-Host "Libraries in: libs/  (libterm.so + ftyd per ABI)" -ForegroundColor Green
Write-Host "JNI libs in:  $LIB_OUTPUT/  (libterm.so only)" -ForegroundColor Green
Write-Host ""
Write-Host "To build the Android project:" -ForegroundColor Yellow
Write-Host "  cd android; .\gradlew.bat :terminal-lib:build" -ForegroundColor Cyan
Write-Host ""
Write-Host "To publish locally:" -ForegroundColor Yellow
Write-Host "  cd android; .\gradlew.bat :terminal-lib:publishToMavenLocal" -ForegroundColor Cyan
