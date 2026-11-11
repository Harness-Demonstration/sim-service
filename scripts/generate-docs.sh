#!/usr/bin/env bash
# Generates docs/generated/kafka-topics.md from the message contract.
# Usage: scripts/generate-docs.sh [output-dir]   (default: docs/generated)
set -euo pipefail
cd "$(dirname "$0")/.."
out=${1:-docs/generated}
npx --yes @asyncapi/cli@6.3.0 generate fromTemplate docs/contracts/sim-events.asyncapi.yaml \
  @asyncapi/markdown-template@2.0.0 -o "$out" --force-write --param outFilename=kafka-topics.md
