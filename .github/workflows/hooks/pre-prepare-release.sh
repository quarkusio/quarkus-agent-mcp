#!/usr/bin/env bash
set -euo pipefail

PLUGIN_JSON=".claude-plugin/plugin.json"

jq --arg v "${CURRENT_VERSION}" '.version = $v' "${PLUGIN_JSON}" > "${PLUGIN_JSON}.tmp"
mv "${PLUGIN_JSON}.tmp" "${PLUGIN_JSON}"

git add "${PLUGIN_JSON}"
git commit -m "Update plugin.json version to ${CURRENT_VERSION}"
