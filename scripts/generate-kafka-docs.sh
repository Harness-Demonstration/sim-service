#!/usr/bin/env bash
# Generates docs/generated/kafka-topics.md from docs/contracts/sim-events.asyncapi.yaml.
# Never edit the generated file by hand; change the contract and run this script.
# Needs Node.js; the tool versions are pinned here and in CI.
set -eu
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
npx --yes @asyncapi/cli@6.3.0 generate fromTemplate docs/contracts/sim-events.asyncapi.yaml \
  @asyncapi/markdown-template@2.0.0 --output "$tmp" --force-write --no-interactive
mkdir -p docs/generated
cp "$tmp/asyncapi.md" docs/generated/kafka-topics.md
