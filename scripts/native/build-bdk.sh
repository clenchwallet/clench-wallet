#!/usr/bin/env bash
# Build checksum-pinned upstream BDK with Clench's reviewed Cargo lock.
set -euo pipefail
exec python3 -B "$(dirname "$0")/build-bdk.py" "$@"
