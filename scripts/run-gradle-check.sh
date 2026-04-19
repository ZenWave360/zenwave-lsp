#!/usr/bin/env bash

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.gradle-logs"
STDOUT_LOG="$LOG_DIR/gradle-stdout.log"
STDERR_LOG="$LOG_DIR/gradle-stderr.log"

mkdir -p "$LOG_DIR"
: > "$STDOUT_LOG"
: > "$STDERR_LOG"

if [[ -f "$ROOT_DIR/.sdkmanrc" ]] && command -v sdk >/dev/null 2>&1; then
  sdk env install=false >/dev/null 2>&1 || true
fi

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"

cd "$ROOT_DIR" || exit 1

./gradlew --no-daemon "$@" --console=plain --stacktrace \
  > >(tee "$STDOUT_LOG") \
  2> >(tee "$STDERR_LOG" >&2)

exit ${PIPESTATUS[0]}
