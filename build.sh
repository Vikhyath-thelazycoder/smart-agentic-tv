#!/usr/bin/env bash
# Builds the phone app, then the TV app that carries it.
set -euo pipefail
cd "$(dirname "$0")"
(cd apps/phone && ./build.sh)
(cd apps/tv && ./build.sh)
