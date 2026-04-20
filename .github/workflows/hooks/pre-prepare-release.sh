#!/usr/bin/env bash
set -euo pipefail

PLUGIN_JSON=".claude-plugin/plugin.json"
README="README.md"

jq --arg v "${CURRENT_VERSION}" '.version = $v' "${PLUGIN_JSON}" > "${PLUGIN_JSON}.tmp"
mv "${PLUGIN_JSON}.tmp" "${PLUGIN_JSON}"

sed -i "s/quarkus-agent-mcp-[0-9][0-9.]*-runner/quarkus-agent-mcp-${CURRENT_VERSION}-runner/g" "${README}"

git add "${PLUGIN_JSON}" "${README}"
git commit -m "Update versions to ${CURRENT_VERSION}"
