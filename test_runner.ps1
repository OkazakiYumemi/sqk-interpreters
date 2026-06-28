# Lab 4 Test Runner - Automated test script for MiniJava interpreter
param(
    [string]$TestDir = "testcase\lab4\ExtraCases",
    [string]$ClassPath = "target\classes",
    [switch]$Verbose = $false
)

$ErrorActionPreference = "Continue"
$script:RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Resolve ANTLR JAR
$antlrJar = Resolve-Path "$env:USERPROFILE\.m2\repository\org\antlr\antlr4-runtime\4.13.2\antlr4-runtime-4.13.2.jar" -ErrorAction SilentlyContinue
if (-not $antlrJar) {
    Write-Host "ERROR: Cannot find ANTLR JAR" -ForegroundColor Red
    exit 1
}

$fullCp = "$ClassPath;$($antlrJar.Path)"

# Compile first
Write-Host "=== Compiling ===" -ForegroundColor Cyan
Push-Location $RootDir
$compileResult = mvn compile -q 2>&1 | Select-Object -Last 5
if ($LASTEXITCODE -ne 0) {
    Write-Host "COMPILE FAILED:" -ForegroundColor Red
    $compileResult | ForEach-Object { Write-Host $_ }
    Pop-Location
    exit 1
}
Write-Host "Compile OK" -ForegroundColor Green

# Scan test directories
$testRoot = Join-Path $RootDir $TestDir
if (-not (Test-Path $testRoot)) {
    Write-Host "ERROR: Test directory not found: $testRoot" -ForegroundColor Red
    Pop-Location
    exit 1
}

$categories = Get-ChildItem $testRoot -Directory | Sort-Object Name

$totalPass = 0
$totalFail = 0
$categoryResults = @{}

foreach ($cat in $categories) {
    $mjFiles = Get-ChildItem $cat.FullName -Filter *.mj | Sort-Object Name
    if ($mjFiles.Count -eq 0) { continue }
    
    $pass = 0
    $fail = 0
    $failList = @()
    
    foreach ($mj in $mjFiles) {
        $name = $mj.BaseName
        $outFile = Join-Path $cat.FullName "$name.output"
        
        if (-not (Test-Path $outFile)) {
            if ($Verbose) { Write-Host "  SKIP $name (no .output)" -ForegroundColor DarkGray }
            continue
        }
        
        # Read expected output
        $expectedLines = @(Get-Content $outFile)
        
        # Skip last 2 lines of expected (blank line + exit code)
        $expOut = if ($expectedLines.Count -ge 3) {
            ($expectedLines[0..($expectedLines.Count - 3)] -join "`n").Trim()
        } else {
            ($expectedLines -join "`n").Trim()
        }
        
        # Run interpreter (ignore stderr, catch StackOverflowError)
        $prevErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $actRaw = try {
            & java -cp $fullCp cn.edu.nju.cs.Main $mj.FullName 2>&1 | Where-Object { $_ -notmatch "^Exception in thread|^\s+at |java\.lang\.StackOverflowError|NativeCommandError|CategoryInfo|FullyQualifiedErrorId" }
        } catch {
            @("Process exits with 34.")
        }
        $ErrorActionPreference = $prevErrorAction
        $actOut = ($actRaw -join "`n").Trim()
        
        if ($expOut -eq $actOut) {
            $pass++
        } else {
            $fail++
            if ($failList.Count -lt 5) { $failList += $name }
        }
    }
    
    $total = $pass + $fail
    if ($total -eq 0) { continue }
    
    $pct = if ($total -gt 0) { [math]::Round(100 * $pass / $total, 1) } else { 0 }
    $color = if ($fail -eq 0) { "Green" } elseif ($pct -ge 90) { "Yellow" } else { "Red" }
    
    Write-Host "$($cat.Name): Pass=$pass Fail=$fail Total=$total ($pct%)" -ForegroundColor $color
    if ($failList.Count -gt 0) {
        Write-Host "  Failures: $($failList -join ', ')" -ForegroundColor DarkYellow
    }
    
    $categoryResults[$cat.Name] = @{
        Pass = $pass
        Fail = $fail
        Total = $total
        FailList = $failList
    }
    
    $totalPass += $pass
    $totalFail += $fail
}

$totalAll = $totalPass + $totalFail
$overallPct = if ($totalAll -gt 0) { [math]::Round(100 * $totalPass / $totalAll, 1) } else { 0 }
$overallColor = if ($totalFail -eq 0) { "Green" } elseif ($overallPct -ge 90) { "Yellow" } else { "Red" }

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  TOTAL: Pass=$totalPass Fail=$totalFail Total=$totalAll ($overallPct%)" -ForegroundColor $overallColor
Write-Host "========================================" -ForegroundColor Cyan

Pop-Location
exit 0
