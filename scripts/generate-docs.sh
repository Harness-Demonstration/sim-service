#!/usr/bin/env bash
# Generates docs/generated/kafka-topics.md from the message contract. Never edit that file by hand.
set -eu
cd "$(dirname "$0")/.."
out=${1:-docs/generated}
npx --yes -p @asyncapi/cli@6.3.0 -p @asyncapi/markdown-template@2.0.0 \
  asyncapi generate fromTemplate docs/contracts/sim-events.asyncapi.yaml @asyncapi/markdown-template@2.0.0 \
  --output "$out" --force-write --param outFilename=kafka-topics.md
