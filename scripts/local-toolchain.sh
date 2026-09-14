#!/usr/bin/env sh

# Run project build commands with the portable toolchain stored on the Data volume.
# This keeps Java, Node, Maven artifacts and npm cache out of the macOS system paths.
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME="$PROJECT_ROOT/.tools/jdk21/Contents/Home"
NODE_HOME="$PROJECT_ROOT/.tools/node22"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Missing portable JDK 21 at $JAVA_HOME" >&2
  exit 1
fi

if [ ! -x "$NODE_HOME/bin/node" ]; then
  echo "Missing portable Node 22 at $NODE_HOME" >&2
  exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$NODE_HOME/bin:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=$PROJECT_ROOT/.tools/m2/repository"
export npm_config_cache="$PROJECT_ROOT/.tools/npm-cache"

if [ "$#" -eq 0 ]; then
  echo "Java: $(java -version 2>&1 | head -n 1)"
  echo "Node: $(node -v)"
  echo "npm:  $(npm -v)"
  echo "Usage: scripts/local-toolchain.sh <command> [args...]"
  exit 0
fi

exec "$@"
