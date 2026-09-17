#!/usr/bin/env bash
set -euo pipefail

# Run in your interactive Git Bash terminal after npm login.
# No output capture: npm needs a terminal to complete browser 2FA.
npm trust github @zenwave360/lsp-js \
  --repo=ZenWave360/zenwave-lsp \
  --file=publish-npm-snapshots.yml \
  --environment=npm-snapshots \
  --allow-publish \
  --yes \
  --registry=https://registry.npmjs.org/

npm trust list @zenwave360/lsp-js --registry=https://registry.npmjs.org/
