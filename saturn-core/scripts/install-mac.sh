#!/usr/bin/env bash
# ==============================================================================
# Saturn Client - One-Click Installer for macOS (Intel & Apple Silicon)
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
echo "         Saturn Client macOS One-Click Installer          "
echo "=========================================================="
echo -e "${NC}"

ARCH=$(uname -m)
case "$ARCH" in
    arm64)
        SATURN_ARCH="arm64"
        ;;
    x86_64)
        SATURN_ARCH="amd64"
        ;;
    *)
        echo -e "${RED}Unsupported macOS CPU architecture: $ARCH${NC}"
        exit 1
        ;;
esac

TARGET_DIR="/usr/local/bin"
sudo mkdir -p "$TARGET_DIR"

echo -e "${YELLOW}[*] Downloading saturn-client for macOS ($SATURN_ARCH)...${NC}"

DOWNLOAD_URLS=(
    "https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-darwin-${SATURN_ARCH}.tar.gz"
    "https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-darwin-${SATURN_ARCH}.tar.gz"
    "https://ghproxy.net/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-darwin-${SATURN_ARCH}.tar.gz"
    "https://github.moeyy.xyz/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-darwin-${SATURN_ARCH}.tar.gz"
    "https://mirror.ghproxy.com/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-darwin-${SATURN_ARCH}.tar.gz"
)

TMP_DIR=$(mktemp -d)
DOWNLOADED=false

for URL in "${DOWNLOAD_URLS[@]}"; do
    if curl -fsSL -m 35 "$URL" -o "$TMP_DIR/saturn.tar.gz" 2>/dev/null; then
        if [ -s "$TMP_DIR/saturn.tar.gz" ] && [ $(wc -c < "$TMP_DIR/saturn.tar.gz") -gt 100000 ]; then
            tar -xzf "$TMP_DIR/saturn.tar.gz" -C "$TMP_DIR" 2>/dev/null || true
            FOUND_CLIENT=$(find "$TMP_DIR" -type f -name "saturn-client" | head -n 1)
            if [ -n "$FOUND_CLIENT" ]; then
                sudo cp "$FOUND_CLIENT" "$TARGET_DIR/saturn-client"
                sudo chmod +x "$TARGET_DIR/saturn-client"
                DOWNLOADED=true
                break
            fi
        fi
    fi
done
rm -rf "$TMP_DIR"

if [ "$DOWNLOADED" != true ]; then
    echo -e "${YELLOW}[*] Release download URL not found. Checking local binaries...${NC}"
    if [ -f "./saturn-client" ]; then
        sudo cp "./saturn-client" "$TARGET_DIR/saturn-client"
        sudo chmod +x "$TARGET_DIR/saturn-client"
    elif [ -f "../build/saturn-client-darwin-$SATURN_ARCH" ]; then
        sudo cp "../build/saturn-client-darwin-$SATURN_ARCH" "$TARGET_DIR/saturn-client"
        sudo chmod +x "$TARGET_DIR/saturn-client"
    else
        echo -e "${YELLOW}[*] Building from source using Go...${NC}"
        go build -trimpath -ldflags="-s -w" -o "$TARGET_DIR/saturn-client" ./cmd/saturn-client
        sudo chmod +x "$TARGET_DIR/saturn-client"
    fi
fi

# Create helper proxy toggle script for macOS
sudo tee /usr/local/bin/saturn-mac-on > /dev/null << 'EOF'
#!/bin/bash
INTERFACE=$(networksetup -listallnetworkservices | grep -E "Wi-Fi|Ethernet" | head -n 1)
networksetup -setsocksfirewallproxy "$INTERFACE" 127.0.0.1 10809
networksetup -setsocksfirewallproxystate "$INTERFACE" on
echo "macOS SOCKS5 Proxy enabled for $INTERFACE (127.0.0.1:10809)"
EOF
sudo chmod +x /usr/local/bin/saturn-mac-on

sudo tee /usr/local/bin/saturn-mac-off > /dev/null << 'EOF'
#!/bin/bash
INTERFACE=$(networksetup -listallnetworkservices | grep -E "Wi-Fi|Ethernet" | head -n 1)
networksetup -setsocksfirewallproxystate "$INTERFACE" off
echo "macOS SOCKS5 Proxy disabled for $INTERFACE"
EOF
sudo chmod +x /usr/local/bin/saturn-mac-off

echo -e "\n${GREEN}=========================================================="
echo "       Saturn Client successfully installed on macOS!     "
echo "=========================================================="
echo -e "${CYAN}1. Start client:"
echo -e "   saturn-client -u \"saturn://...\" &"
echo -e "2. Enable macOS System Proxy with one command:"
echo -e "   saturn-mac-on"
echo -e "3. Disable macOS System Proxy:"
echo -e "   saturn-mac-off"
echo -e "==========================================================${NC}\n"
