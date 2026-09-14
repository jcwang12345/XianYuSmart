#!/usr/bin/env sh

# Fast native verification. The QA application also runs natively; only MySQL stays in Docker.
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TOOLCHAIN="$PROJECT_ROOT/scripts/local-toolchain.sh"

"$TOOLCHAIN" "$PROJECT_ROOT/mvnw" test
"$TOOLCHAIN" npm --prefix "$PROJECT_ROOT/vue-code" run type-check
"$TOOLCHAIN" npm --prefix "$PROJECT_ROOT/vue-code" run build-only
