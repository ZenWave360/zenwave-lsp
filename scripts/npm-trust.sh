#!/usr/bin/env bash
set -euo pipefail

# Run in your interactive Git Bash terminal after npm login.
# No output capture: npm needs a terminal to complete browser 2FA.
configure_publisher() {
  npm trust github @zenwave360/lsp-js \
    --repo=ZenWave360/zenwave-lsp \
    --file="$1" \
    --environment="$2" \
    --allow-publish \
    --yes \
    --registry=https://registry.npmjs.org/
}

case "${1:-snapshots}" in
  snapshots) configure_publisher publish-npm-snapshots.yml npm-snapshots ;;
  release) configure_publisher release.yml npm-publish ;;
  all)
    configure_publisher publish-npm-snapshots.yml npm-snapshots
    configure_publisher release.yml npm-publish
    ;;
  *) echo "Usage: bash scripts/npm-trust.sh [snapshots|release|all]" >&2; exit 2 ;;
esac

npm trust list @zenwave360/lsp-js --registry=https://registry.npmjs.org/
