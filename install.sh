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

echo "Installing quarkus-agent-mcp ${VERSION} (${PLATFORM})..."
mkdir -p "${INSTALL_DIR}"
curl -fL -o "${INSTALL_DIR}/quarkus-agent-mcp" "${DOWNLOAD_URL}"
chmod +x "${INSTALL_DIR}/quarkus-agent-mcp"

if command -v claude &>/dev/null; then
  claude mcp remove quarkus-agent -s user 2>/dev/null || true
  claude mcp add -s user quarkus-agent -- "${INSTALL_DIR}/quarkus-agent-mcp"
  echo "Registered as MCP server with Claude Code"
else
  echo "Claude Code not found — register manually:"
  echo "  claude mcp add -s user quarkus-agent -- ${INSTALL_DIR}/quarkus-agent-mcp"
fi

echo "Done! Installed to ${INSTALL_DIR}/quarkus-agent-mcp"
