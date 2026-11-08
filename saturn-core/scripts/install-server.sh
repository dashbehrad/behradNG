#!/usr/bin/env bash
# ==============================================================================
# Saturn Stealth Tunnel Server - Quick Installer
# Repository: https://github.com/dashbehrad/Saturn-core
# ==============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}"
echo "=========================================================="
echo "          Saturn Stealth Tunnel Server Installer          "
echo "=========================================================="
echo -e "${NC}"

if [ "$(id -u)" -ne 0 ]; then
    echo -e "${RED}Error: This script must be run as root (sudo).${NC}"
    exit 1
fi

# Ensure basic utilities exist
for pkg in curl tar gzip; do
    if ! command -v "$pkg" &>/dev/null; then
        (apt-get update -qq && apt-get install -y -qq "$pkg") 2>/dev/null || (yum install -y "$pkg") 2>/dev/null || true
    fi
done

# Detect Architecture
ARCH=$(uname -m)
case "$ARCH" in
    x86_64|amd64)
        SATURN_ARCH="amd64"
        ;;
    aarch64|arm64)
        SATURN_ARCH="arm64"
        ;;
    armv7l|armhf|arm)
        SATURN_ARCH="arm"
        ;;
    *)
        echo -e "${RED}Unsupported CPU architecture: $ARCH${NC}"
        exit 1
        ;;
esac

BIN_DIR="/usr/local/bin"
CONF_DIR="/etc/saturn"
CONF_FILE="$CONF_DIR/config.json"
SERVICE_FILE="/etc/systemd/system/saturn-server.service"

mkdir -p "$BIN_DIR" "$CONF_DIR"

# 1. Check local binaries or download prebuilt release
if [ -f "./saturn-server" ]; then
    echo -e "${GREEN}[+] Using local saturn-server binary.${NC}"
    cp "./saturn-server" "$BIN_DIR/saturn-server"
    chmod +x "$BIN_DIR/saturn-server"
elif [ -f "../build/saturn-server-linux-$SATURN_ARCH" ]; then
    echo -e "${GREEN}[+] Using built saturn-server binary.${NC}"
    cp "../build/saturn-server-linux-$SATURN_ARCH" "$BIN_DIR/saturn-server"
    chmod +x "$BIN_DIR/saturn-server"
else
    echo -e "${YELLOW}[*] Downloading latest Saturn Server release for linux-${SATURN_ARCH}...${NC}"

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
        echo -e "${YELLOW}[*] Trying download: $URL...${NC}"
        if curl -fsSL -m 35 "$URL" -o "$TMP_DIR/saturn.tar.gz" 2>/dev/null; then
            if [ -s "$TMP_DIR/saturn.tar.gz" ] && [ $(wc -c < "$TMP_DIR/saturn.tar.gz") -gt 100000 ]; then
                echo -e "${GREEN}[+] Download complete! Extracting...${NC}"
                tar -xzf "$TMP_DIR/saturn.tar.gz" -C "$TMP_DIR" 2>/dev/null || true

                FOUND_SERVER=$(find "$TMP_DIR" -type f -name "saturn-server" | head -n 1)
                FOUND_CLIENT=$(find "$TMP_DIR" -type f -name "saturn-client" | head -n 1)

                if [ -n "$FOUND_SERVER" ]; then
                    cp "$FOUND_SERVER" "$BIN_DIR/saturn-server"
                    chmod +x "$BIN_DIR/saturn-server"
                    DOWNLOADED=true
                fi
                if [ -n "$FOUND_CLIENT" ]; then
                    cp "$FOUND_CLIENT" "$BIN_DIR/saturn-client"
                    chmod +x "$BIN_DIR/saturn-client"
                fi

                if [ "$DOWNLOADED" = true ]; then
                    break
                fi
            fi
        fi
    done
    rm -rf "$TMP_DIR"

    # Fallback to Go build from source if download failed
    if [ ! -f "$BIN_DIR/saturn-server" ]; then
        echo -e "${YELLOW}[*] Binary mirrors failed. Building from source via Go...${NC}"
        if ! command -v go &> /dev/null; then
            (apt-get update -qq && apt-get install -y -qq golang git curl jq) 2>/dev/null || true
        fi
        BUILD_TMP=$(mktemp -d)
        if git clone --depth 1 https://github.com/dashbehrad/Saturn-core.git "$BUILD_TMP" 2>/dev/null; then
            (cd "$BUILD_TMP" && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$BIN_DIR/saturn-server" ./cmd/saturn-server)
            (cd "$BUILD_TMP" && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$BIN_DIR/saturn-client" ./cmd/saturn-client)
            chmod +x "$BIN_DIR/saturn-server" "$BIN_DIR/saturn-client"
        fi
        rm -rf "$BUILD_TMP"
    fi
fi

if [ ! -f "$BIN_DIR/saturn-server" ]; then
    echo -e "${RED}Error: Failed to install saturn-server binary.${NC}"
    exit 1
fi
chmod +x "$BIN_DIR/saturn-server"

# Kernel Tuning for High Speed & Low Latency (BBR + Window Scale + MTU)
echo -e "${GREEN}[+] Tuning Linux network stack...${NC}"
cat > /etc/sysctl.d/99-saturn.conf << 'EOF'
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 65536 16777216
net.ipv4.tcp_mtu_probing = 1
net.ipv4.tcp_slow_start_after_idle = 0
EOF
sysctl --system > /dev/null 2>&1 || true

# Generate Keys if not present
if [ ! -f "$CONF_FILE" ]; then
    echo -e "${GREEN}[+] Generating new cryptographic keys and configuration...${NC}"
    OUTPUT=$("$BIN_DIR/saturn-server" -keygen)

    PUB_KEY=$(echo "$OUTPUT" | grep "Public Key" | awk '{print $NF}')
    PRIV_KEY=$(echo "$OUTPUT" | grep "Private Key" | awk '{print $NF}')
    UUID=$(echo "$OUTPUT" | grep "Client UUID" | awk '{print $NF}')
    SHORT_ID=$(echo "$OUTPUT" | grep "Short ID" | awk '{print $NF}')

    cat > "$CONF_FILE" << EOF
{
  "listen": "0.0.0.0:443",
  "private_key": "$PRIV_KEY",
  "public_key": "$PUB_KEY",
  "uuid": "$UUID",
  "short_id": "$SHORT_ID",
  "fallback_dest": "dl.google.com:443",
  "cipher": "chacha20-poly1305"
}
EOF
    chmod 600 "$CONF_FILE"
else
    echo -e "${YELLOW}[*] Existing configuration found at $CONF_FILE. Retaining keys.${NC}"
    if command -v jq &>/dev/null; then
        PUB_KEY=$(jq -r '.public_key' "$CONF_FILE")
        UUID=$(jq -r '.uuid' "$CONF_FILE")
        SHORT_ID=$(jq -r '.short_id' "$CONF_FILE")
    else
        PUB_KEY=$(grep '"public_key"' "$CONF_FILE" | cut -d '"' -f 4)
        UUID=$(grep '"uuid"' "$CONF_FILE" | cut -d '"' -f 4)
        SHORT_ID=$(grep '"short_id"' "$CONF_FILE" | cut -d '"' -f 4)
    fi
fi

# Install systemd service
echo -e "${GREEN}[+] Installing systemd service...${NC}"
cat > "$SERVICE_FILE" << 'EOF'
[Unit]
Description=Saturn Stealth Tunnel Server
After=network.target network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/saturn-server -c /etc/saturn/config.json
Restart=always
RestartSec=3s
LimitNOFILE=1048576
LimitNPROC=512

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable saturn-server
systemctl restart saturn-server

SERVER_IP=$(curl -sSL -m 5 https://api.ipify.org 2>/dev/null || curl -sSL -m 5 https://ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')
SATURN_URI="saturn://${UUID}@${SERVER_IP}:443?pbk=${PUB_KEY}&sid=${SHORT_ID}&sni=dl.google.com&alpn=h2&fp=chrome&cipher=chacha20-poly1305#Saturn-${SERVER_IP}"

echo -e "\n${GREEN}==========================================================${NC}"
echo -e "${GREEN}      Saturn Server successfully installed & running!     ${NC}"
echo -e "${GREEN}==========================================================${NC}"
echo -e "Server IP:   ${CYAN}${SERVER_IP}${NC}"
echo -e "Port:        ${CYAN}443${NC}"
echo -e "Public Key:  ${CYAN}${PUB_KEY}${NC}"
echo -e "Short ID:    ${CYAN}${SHORT_ID}${NC}"
echo -e "UUID:        ${CYAN}${UUID}${NC}"
echo -e "Service:     ${CYAN}systemctl status saturn-server${NC}"
echo -e "----------------------------------------------------------"
echo -e "Client URI (Copy into behradNG or Saturn Client):"
echo -e "${YELLOW}${SATURN_URI}${NC}"
echo -e "==========================================================\n"
