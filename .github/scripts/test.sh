#!/usr/bin/bash

set -eu
set -o pipefail

html_dir="$1"
for file in scripting-host/src/test/resources/*.server.kts; do
  cp "$file" "$html_dir"
  test_file_name=$(basename "$file")
  test_name="${test_file_name%%.server.kts}"
  expected_body="${file%%.server.kts}.body"
  header_file="${file%%.server.kts}.header"
  expected_header=''
  if [ -f "$header_file" ]; then
      sed 's%Status:%HTTP/1.1 %g' "$header_file" > 'expected_header.txt'
      expected_header='expected_header.txt'
  fi
  echo "test $test_name"

  function test_request() {
    expected="$1"
    curl --request GET -s \
        --url "http://localhost:8080/$test_name.kts" \
        --output 'test_result.txt' \
        --dump-header 'test_headers.txt'
    diff "$expected" 'test_result.txt'
    if [[ -n "$expected_header" ]]; then
        missing_lines=$(diff --ignore-all-space -U 0 expected_header.txt test_headers.txt | tail -n +3 | grep -c '^-' || true)
        if [ "$missing_lines" -gt 0 ]; then
            echo "expected headers [$(cat expected_header.txt)] but found [$(cat test_headers.txt)]"
            exit 1
        fi
    fi
  }
  if [ "$test_name" == 'cache_data' ]; then
    for (( i = 1; i <= 5; i++ )); do
      sed "s/{counter}/$i/g" "$expected_body" > 'expected_result.txt'
      test_request 'expected_result.txt'
    done
  else
    test_request "$expected_body"
  fi
done
