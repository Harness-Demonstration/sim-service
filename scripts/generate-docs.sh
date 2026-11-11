#!/usr/bin/env bash
# Generates docs/generated/ from the message contract. Never edit those files by hand; change the contract and run this.
set -eu
cd "$(dirname "$0")/.."
out=${1:-docs/generated}
npx --yes @asyncapi/cli@6.3.0 generate fromTemplate docs/contracts/sim-events.asyncapi.yaml \
  @asyncapi/markdown-template@2.0.0 --output "$out" --force-write -p outFilename=kafka-topics.md
