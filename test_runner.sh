#!/bin/bash
INPUT="testcase/Lab4-OpenCase/Lab4-OpenCase/input"
OUTPUT="testcase/Lab4-OpenCase/Lab4-OpenCase/output"
PASS=0
FAIL=0
ERRORS=""
COUNT=0

for f in $(ls "$INPUT"/*.mj | sort); do
    COUNT=$((COUNT + 1))
    name=$(basename "$f" .mj)
    expected_file="$OUTPUT/$name.output"
    
    # Run interpreter with Maven (quiet mode)
    raw=$(mvn -q exec:java -Dexec.mainClass=cn.edu.nju.cs.Main -Dexec.args="$f" 2>&1)
    
    # Filter out Maven noise
    actual=$(echo "$raw" | grep -v '^\[INFO\]' | grep -v '^\[WARNING\]' | grep -v '^\[ERROR\]' | grep -v 'BUILD' | grep -v 'Total time' | grep -v 'Finished' | grep -v 'Scanning' | grep -v 'Building' | grep -v '^---' | sed '/^$/d')
    
    # Read expected, strip last 2 lines (blank + exit code)
    expected=$(cat "$expected_file" 2>/dev/null)
    total_lines=$(echo "$expected" | wc -l)
    compare_lines=$((total_lines - 2))
    if [ $compare_lines -lt 0 ]; then compare_lines=0; fi
    exp_compare=$(echo "$expected" | head -n $compare_lines)
    
    if [ "$actual" = "$exp_compare" ]; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
        if [ $FAIL -le 5 ]; then
            ERRORS="${ERRORS}\n=== FAIL: $name ===\nEXPECTED:\n${exp_compare}\nACTUAL:\n${actual}\n"
        fi
    fi
    
    # Progress every 50
    if [ $((COUNT % 50)) -eq 0 ]; then
        echo "Progress: $COUNT tests, $PASS passed, $FAIL failed"
    fi
done

echo "========================================="
echo "FINAL: $PASS passed, $FAIL failed out of $COUNT"
echo "========================================="
echo -e "$ERRORS"
