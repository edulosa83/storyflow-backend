#!/usr/bin/env bash
set -euo pipefail

DEVICE_ID="${1:-}"
if [[ -z "$DEVICE_ID" ]]; then
  echo "Uso: $0 <DEVICE_ID>"
  echo "Ejemplo: $0 R5CWC3Y2VSH"
  exit 1
fi

adb -s "$DEVICE_ID" reverse --remove-all
adb -s "$DEVICE_ID" reverse tcp:8080 tcp:8080
adb -s "$DEVICE_ID" reverse --list
