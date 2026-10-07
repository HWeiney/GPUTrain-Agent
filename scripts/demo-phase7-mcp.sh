#!/usr/bin/env bash
set -euo pipefail
set +x

base_url="${GPU_TRAIN_API_BASE_URL:?Set GPU_TRAIN_API_BASE_URL to the HTTPS control-plane URL}"
token="${GPU_TRAIN_JWT:?Set GPU_TRAIN_JWT to a JWT with train:overview:view and train:job:view}"
base_url="${base_url%/}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
auth_config="$tmp_dir/auth.conf"
(umask 077; printf 'header = "Authorization: Bearer %s"\n' "$token" > "$auth_config")
unset token
auth_args=(--config "$auth_config")

common_headers=(
  -H 'Accept: application/json, text/event-stream'
  -H 'Content-Type: application/json'
)

echo '== GPUTrain overview (read only) =='
curl --silent --show-error --fail-with-body "${auth_args[@]}" "${common_headers[@]}" \
  "${base_url}/train/overview"
printf '\n'

echo '== MCP initialize =='
curl --silent --show-error --fail-with-body "${auth_args[@]}" -D "$tmp_dir/initialize.headers" \
  -o "$tmp_dir/initialize.body" "${common_headers[@]}" \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"gputrain-demo","version":"1.0.0"}}}' \
  "${base_url}/mcp"
cat "$tmp_dir/initialize.body"
printf '\n'

session_id="$(awk 'tolower($1)=="mcp-session-id:" {gsub("\r", "", $2); print $2}' "$tmp_dir/initialize.headers" | tail -n 1)"
session_header=()
if [[ -n "$session_id" ]]; then
  session_header=(-H "Mcp-Session-Id: ${session_id}")
fi
protocol_header=(-H 'MCP-Protocol-Version: 2025-06-18')

curl --silent --show-error --fail-with-body "${auth_args[@]}" -o /dev/null "${common_headers[@]}" \
  "${session_header[@]}" "${protocol_header[@]}" \
  --data '{"jsonrpc":"2.0","method":"notifications/initialized"}' "${base_url}/mcp"

echo '== MCP read-only tool allowlist =='
curl --silent --show-error --fail-with-body "${auth_args[@]}" "${common_headers[@]}" \
  "${session_header[@]}" "${protocol_header[@]}" \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' "${base_url}/mcp"
printf '\n'

echo '== My experiments (read only) =='
curl --silent --show-error --fail-with-body "${auth_args[@]}" "${common_headers[@]}" \
  "${session_header[@]}" "${protocol_header[@]}" \
  --data '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_my_experiments","arguments":{}}}' \
  "${base_url}/mcp"
printf '\n'
