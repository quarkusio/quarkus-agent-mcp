#!/bin/bash
set -e

REPO="quarkusio/quarkus-agent-mcp"
INSTALL_DIR="${HOME}/.local/bin"

OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

case "${OS}" in
  linux)  PLATFORM="linux-x86_64" ;;
  darwin)
    case "${ARCH}" in
      arm64|aarch64) PLATFORM="macos-aarch64" ;;
      x86_64)        PLATFORM="macos-x86_64" ;;
      *) echo "Unsupported architecture: ${ARCH}"; exit 1 ;;
    esac
    ;;
  *) echo "Unsupported OS: ${OS}. Use JBang instead: claude mcp add -s user quarkus-agent -- jbang quarkus-agent-mcp@quarkusio"; exit 1 ;;
esac

VERSION=$(curl -sI "https://github.com/${REPO}/releases/latest" | grep -i ^location: | sed 's|.*/||' | tr -d '\r')
if [ -z "${VERSION}" ]; then
  echo "Failed to determine latest version"
  exit 1
fi

DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${VERSION}/quarkus-agent-mcp-${VERSION}-${PLATFORM}"

BINARY="quarkus-agent-mcp-${VERSION}"

echo "Installing quarkus-agent-mcp ${VERSION} (${PLATFORM})..."
mkdir -p "${INSTALL_DIR}"
# Download to a temporary name first so an interrupted download never leaves a broken binary behind
curl -fL -o "${INSTALL_DIR}/.${BINARY}.download" "${DOWNLOAD_URL}"
chmod +x "${INSTALL_DIR}/.${BINARY}.download"
mv -f "${INSTALL_DIR}/.${BINARY}.download" "${INSTALL_DIR}/${BINARY}"

# Point the stable name at the versioned binary, so `ls -l` shows the installed version.
# Replacing the link (or an older plain-file install) does not disturb running agent
# sessions: they keep using the binary they started with.
ln -sfn "${BINARY}" "${INSTALL_DIR}/quarkus-agent-mcp"

# Remove binaries from previous versions
for old in "${INSTALL_DIR}"/quarkus-agent-mcp-[0-9]*; do
  if [ -f "${old}" ] && [ "$(basename "${old}")" != "${BINARY}" ]; then
    rm -f "${old}"
    echo "Removed previous version $(basename "${old}")"
  fi
done

if command -v claude &>/dev/null; then
  claude mcp remove quarkus-agent -s user 2>/dev/null || true
  claude mcp add -s user quarkus-agent -- "${INSTALL_DIR}/quarkus-agent-mcp"
  echo "Registered as MCP server with Claude Code"
else
  echo "Claude Code not found — register manually:"
  echo "  claude mcp add -s user quarkus-agent -- ${INSTALL_DIR}/quarkus-agent-mcp"
fi

echo "Done! Installed to ${INSTALL_DIR}/quarkus-agent-mcp -> ${BINARY}"
