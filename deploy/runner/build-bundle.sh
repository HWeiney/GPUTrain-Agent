#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
runner_jar="$repo_root/backend/ruoyi-train-runner/target/ruoyi-train-runner-3.9.2.jar"
output_dir="$repo_root/deploy/runner/build"
output="$output_dir/gputrain-runner-bundle.tar.gz"
stage=$(mktemp -d "${TMPDIR:-/tmp}/gputrain-runner-bundle.XXXXXX")

cleanup() { rm -rf -- "$stage"; }
trap cleanup EXIT

test -f "$runner_jar"
install -d -m 755 "$output_dir" "$stage/runner"
install -m 644 "$repo_root/deploy/runner/gputrain-runner.service" "$stage/runner/gputrain-runner.service"
install -m 644 "$runner_jar" "$stage/runner/ruoyi-train-runner.jar"
install -m 644 "$repo_root/deploy/runner/runner.properties.example" "$stage/runner/runner.properties.example"

COPYFILE_DISABLE=1 tar --no-xattrs -czf "$output" -C "$stage" \
  runner/gputrain-runner.service \
  runner/ruoyi-train-runner.jar \
  runner/runner.properties.example

expected=$(printf '%s\n' \
  runner/gputrain-runner.service \
  runner/ruoyi-train-runner.jar \
  runner/runner.properties.example)
actual=$(tar -tzf "$output")
test "$actual" = "$expected"

printf 'Created %s\n' "$output"
shasum -a 256 "$output"
