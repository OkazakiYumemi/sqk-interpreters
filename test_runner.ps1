$antlrJar = (Resolve-Path "$env:USERPROFILE\.m2\repository\org\antlr\antlr4-runtime\4.13.2\antlr4-runtime-4.13.2.jar").Path
$cp = "target/classes;$antlrJar"
$inputDir = "testcase/Lab4-OpenCase/Lab4-OpenCase/input"
$outputDir = "testcase/Lab4-OpenCase/Lab4-OpenCase/output"

$pass = 0
$fail = 0
$errors = @()

$files = Get-ChildItem "$inputDir/*.mj" | Sort-Object Name
$total = $files.Count

foreach ($f in $files) {
    $name = $f.BaseName
    $outFile = Join-Path $outputDir "$name.output"
    
    # Run interpreter
    $raw = & java -cp $cp cn.edu.nju.cs.Main $f.FullName 2>&1
    $actual = ($raw | Where-Object { $_ -ne '' }) -join "`n"
    
    # Read expected
    $expectedRaw = if (Test-Path $outFile) { (Get-Content $outFile -Raw).Trim() } else { "" }
    
    # Expected has trailing blank line + exit code; strip last 2 lines
    $expLines = $expectedRaw -split "`n"
    $compareCount = [Math]::Max(0, $expLines.Count - 2)
    $expected = ($expLines[0..($compareCount - 1)] -join "`n").Trim()
    
    if ($actual.Trim() -eq $expected) {
        $pass++
    } else {
        $fail++
        if ($fail -le 10) {
            $errors += "=== FAIL: $name ===`nEXPECTED:`n$expected`nACTUAL:`n$($actual.Trim())`n"
        }
    }
    
    # Progress
    $done = $pass + $fail
    if ($done % 50 -eq 0) {
        Write-Host "Progress: $done / $total  (pass=$pass fail=$fail)"
    }
}

Write-Host "========================================="
Write-Host "FINAL: $pass passed, $fail failed out of $total"
Write-Host "========================================="
foreach ($e in $errors) {
    Write-Host $e
}
