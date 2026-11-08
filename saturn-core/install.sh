#!/usr/bin/env bash
# ==============================================================================
# Saturn Core - Universal One-Click Auto-Installer
# Repository: https://github.com/dashbehrad/Saturn-core
# ==============================================================================

set -euo pipefail

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}"
echo "=========================================================="
echo "          🪐 Saturn Core Universal One-Click Installer    "
echo "=========================================================="
echo -e "${NC}"

OS="$(uname -s)"
BASE_URL="https://raw.githubusercontent.com/dashbehrad/Saturn-core/main"

# 1. Detect Termux
if [ -d "/data/data/com.termux" ] || [ -n "${PREFIX:-}" ]; then
    echo -e "${GREEN}[+] Android Termux environment detected!${NC}"
    curl -fsSL "$BASE_URL/scripts/install-termux.sh" | bash
    exit 0
fi

# 2. Detect macOS
if [ "$OS" = "Darwin" ]; then
    echo -e "${GREEN}[+] macOS environment detected!${NC}"
    curl -fsSL "$BASE_URL/scripts/install-mac.sh" | bash
    exit 0
fi

# 3. Detect Windows (MSYS / MINGW / Cygwin)
if [[ "$OS" == *"MINGW"* ]] || [[ "$OS" == *"CYGWIN"* ]] || [[ "$OS" == *"MSYS"* ]]; then
    echo -e "${YELLOW}[!] Windows detected via Bash shell.${NC}"
    echo -e "Please run the following one-line command in Windows PowerShell:"
    echo -e "${CYAN}irm https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@main/scripts/install-windows.ps1 | iex${NC}"
    exit 0
fi

# 4. Linux Environment
if [ "$OS" = "Linux" ]; then
    if [ "$(id -u)" -eq 0 ]; then
        echo -e "${GREEN}[+] Linux Root detected. Installing Saturn Server...${NC}"
        curl -fsSL "$BASE_URL/scripts/install-server.sh" | bash
    else
        echo -e "${YELLOW}[*] Non-root Linux user detected.${NC}"
        echo -e "To install Saturn Server (with systemd & BBR tuning), run with sudo:"
        echo -e "  ${CYAN}curl -fsSL $BASE_URL/scripts/install-server.sh | sudo bash${NC}\n"
        echo -e "Installing Saturn Client locally for current user..."

        ARCH=$(uname -m)
        case "$ARCH" in
            x86_64|amd64) SATURN_ARCH="amd64" ;;
            aarch64|arm64) SATURN_ARCH="arm64" ;;
            *) SATURN_ARCH="amd64" ;;
        esac

        BIN_DIR="$HOME/.local/bin"
        mkdir -p "$BIN_DIR"
        DOWNLOAD_URLS=(
            "https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-linux-${SATURN_ARCH}.tar.gz"
            "https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-linux-${SATURN_ARCH}.tar.gz"
            "https://ghproxy.net/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-linux-${SATURN_ARCH}.tar.gz"
            "https://github.moeyy.xyz/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-linux-${SATURN_ARCH}.tar.gz"
            "https://mirror.ghproxy.com/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-linux-${SATURN_ARCH}.tar.gz"
        )
        TMP_DIR=$(mktemp -d)
        for URL in "${DOWNLOAD_URLS[@]}"; do
            if curl -fsSL -m 35 "$URL" -o "$TMP_DIR/saturn.tar.gz" 2>/dev/null; then
                if [ -s "$TMP_DIR/saturn.tar.gz" ] && [ $(wc -c < "$TMP_DIR/saturn.tar.gz") -gt 100000 ]; then
                    tar -xzf "$TMP_DIR/saturn.tar.gz" -C "$TMP_DIR" 2>/dev/null || true
                    FOUND_CLIENT=$(find "$TMP_DIR" -type f -name "saturn-client" | head -n 1)
                    if [ -n "$FOUND_CLIENT" ]; then
                        cp "$FOUND_CLIENT" "$BIN_DIR/saturn-client"
                        chmod +x "$BIN_DIR/saturn-client"
                        break
                    fi
                fi
            fi
        done
        rm -rf "$TMP_DIR"
        if [ -f "$BIN_DIR/saturn-client" ]; then
            echo -e "${GREEN}[+] Saturn Client installed to $BIN_DIR/saturn-client${NC}"
        else
            echo -e "${RED}[-] Failed to download saturn-client binary.${NC}"
        fi
    fi
    exit 0
fi

echo -e "${RED}Unrecognized operating system: $OS${NC}"
exit 1
