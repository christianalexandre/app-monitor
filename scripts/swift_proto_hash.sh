#!/usr/bin/env bash
# Prints the hash of protocol/proto that sdk/ios's generated code was built from.
set -euo pipefail
cd "$(dirname "$0")/.."
find protocol/proto -name '*.proto' | LC_ALL=C sort | xargs shasum -a 256
