#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Saturn Client Installer for Android Termux
# Repository: https://github.com/dashbehrad/Saturn-core
# ==============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}Installing Saturn Client for Termux...${NC}"

ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64)
        SATURN_ARCH="arm64"
        ;;
    armv7l|arm)
        SATURN_ARCH="arm"
        ;;
    x86_64|amd64)
        SATURN_ARCH="amd64"
        ;;
    *)
        echo -e "${RED}Unsupported Termux architecture: $ARCH${NC}"
        exit 1
        ;;
esac

TARGET_BIN="$PREFIX/bin/saturn-client"

echo -e "${YELLOW}[*] Fetching saturn-client for linux-$SATURN_ARCH...${NC}"
RELEASE_URL=$(curl -s https://api.github.com/repos/dashbehrad/Saturn-core/releases/latest | grep "browser_download_url.*client-linux-${SATURN_ARCH}.tar.gz" | cut -d '"' -f 4 || true)

if [ -n "$RELEASE_URL" ]; then
    curl -sSL "$RELEASE_URL" | tar -xz -C "$PREFIX/bin"
    chmod +x "$TARGET_BIN"
else
    echo -e "${YELLOW}[*] Release download URL not found. Compiling via go...${NC}"
    pkg install -y golang git
    CGO_ENABLED=0 go install github.com/dashbehrad/Saturn-core/cmd/saturn-client@latest
fi

# Add helper scripts for Termux shell proxy
cat > "$PREFIX/bin/saturn-on" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
export http_proxy="http://127.0.0.1:10808"
export https_proxy="http://127.0.0.1:10808"
export all_proxy="socks5://127.0.0.1:10808"
echo "Proxy environment enabled for Termux (127.0.0.1:10808)"
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
echo -e "  2. Enable Termux Proxy:"
echo -e "     ${CYAN}source saturn-on${NC}"
echo -e "  3. Test connection:"
echo -e "     ${CYAN}curl -I https://www.google.com${NC}"
echo -e "  4. Disable Termux Proxy:"
echo -e "     ${CYAN}source saturn-off${NC}\n"
