#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Saturn Client - Termux Auto-Installer (Rootless Android)
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
echo "          🪐 Saturn Client Termux Installer               "
echo "=========================================================="
echo -e "${NC}"

ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64)
        SATURN_ARCH="arm64"
        ;;
    armv7l|armhf|arm)
        SATURN_ARCH="arm"
        ;;
    x86_64|amd64)
        SATURN_ARCH="amd64"
        ;;
    *)
        echo -e "${RED}Unsupported Termux CPU architecture: $ARCH${NC}"
        exit 1
        ;;
esac

TARGET_BIN="$PREFIX/bin/saturn-client"

echo -e "${YELLOW}[*] Fetching saturn-client for linux-$SATURN_ARCH...${NC}"

DOWNLOAD_URLS=(
    "https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-linux-${SATURN_ARCH}.tar.gz"
    "https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-linux-${SATURN_ARCH}.tar.gz"
    "https://ghproxy.net/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-linux-${SATURN_ARCH}.tar.gz"
    "https://github.moeyy.xyz/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-linux-${SATURN_ARCH}.tar.gz"
    "https://mirror.ghproxy.com/https://github.com/dashbehrad/Saturn-core/releases/download/v1.0/saturn-linux-${SATURN_ARCH}.tar.gz"
)

TMP_DIR=$(mktemp -d)
DOWNLOADED=false

for URL in "${DOWNLOAD_URLS[@]}"; do
    if curl -fsSL -m 35 "$URL" -o "$TMP_DIR/saturn.tar.gz" 2>/dev/null; then
        if [ -s "$TMP_DIR/saturn.tar.gz" ] && [ $(wc -c < "$TMP_DIR/saturn.tar.gz") -gt 100000 ]; then
            tar -xzf "$TMP_DIR/saturn.tar.gz" -C "$TMP_DIR" 2>/dev/null || true
            FOUND_CLIENT=$(find "$TMP_DIR" -type f -name "saturn-client" | head -n 1)
            if [ -n "$FOUND_CLIENT" ]; then
                cp "$FOUND_CLIENT" "$TARGET_BIN"
                chmod +x "$TARGET_BIN"
                DOWNLOADED=true
                break
            fi
        fi
    fi
done
rm -rf "$TMP_DIR"

if [ "$DOWNLOADED" != true ]; then
    echo -e "${YELLOW}[*] Binary mirrors unavailable. Compiling via go...${NC}"
    pkg install -y golang git
    CGO_ENABLED=0 go install github.com/dashbehrad/Saturn-core/cmd/saturn-client@latest
fi

# Add helper scripts for Termux shell proxy
cat > "$PREFIX/bin/saturn-on" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
export http_proxy="http://127.0.0.1:10809"
export https_proxy="http://127.0.0.1:10809"
export all_proxy="socks5://127.0.0.1:10809"
echo "Proxy environment enabled for Termux (127.0.0.1:10809)"
EOF
chmod +x "$PREFIX/bin/saturn-on"

cat > "$PREFIX/bin/saturn-off" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
unset http_proxy
unset https_proxy
unset all_proxy
echo "Proxy environment disabled"
EOF
chmod +x "$PREFIX/bin/saturn-off"

echo -e "\n${GREEN}=== Saturn Client installed successfully in Termux! ===${NC}"
echo -e "Usage:"
echo -e "  1. Start Saturn Client:"
echo -e "     ${CYAN}saturn-client -u \"saturn://...\" &${NC}"
echo -e "  2. Turn on Termux environment proxy:"
echo -e "     ${CYAN}source saturn-on${NC}"
echo -e "  3. Turn off proxy:"
echo -e "     ${CYAN}source saturn-off${NC}\n"
