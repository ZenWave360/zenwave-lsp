#!/usr/bin/env bash
set -euo pipefail
: "${NPM_VERSION:?NPM_VERSION is required}"
SMOKE_DIR="${RUNNER_TEMP:-build}/npm-smoke"
mkdir -p "$SMOKE_DIR"
npm install --prefix "$SMOKE_DIR" --ignore-scripts --no-package-lock --no-audit --no-fund \
  "$PWD/build/npm/zenwave360-lsp-js-$NPM_VERSION.tgz"
LSP_JS_PACKAGE_DIR="$SMOKE_DIR/node_modules/@zenwave360/lsp-js" \
  node --test lsp-js/npm/test/node-ipc.test.mjs
