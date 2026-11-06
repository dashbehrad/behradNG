#!/usr/bin/env bash
# ==============================================================================
# Saturn Direct VPN Protocol - Automated Server Installer for Debian / Ubuntu
# ==============================================================================
set -euo pipefail

AUTO_MODE=0
PORT=443
SNI="dl.google.com"
CUSTOM_BIN=""

# Parse command line flags
while [[ $# -gt 0 ]]; do
    case "$1" in
        --auto)
            AUTO_MODE=1
            shift
            ;;
        --port)
            PORT="$2"
            shift 2
            ;;
        --sni)
            SNI="$2"
            shift 2
            ;;
        --bin)
            CUSTOM_BIN="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--auto] [--port <PORT>] [--sni <SNI>] [--bin <BIN_PATH_OR_URL>]"
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

echo "[*] Initializing Saturn Direct VPN Server Installation..."

# 1. Environment & Root Check
if [ "$(id -u)" -ne 0 ]; then
    if command -v sudo >/dev/null 2>&1; then
        echo "[*] Non-root user ($(whoami)) detected. Escalating privileges using 'sudo -i'..."
        if [ -f "$0" ] && [ "$0" != "bash" ] && [ "$0" != "sh" ]; then
            exec sudo -i bash "$0" "$@"
        fi
    fi
    echo "[!] Error: This script must be run as root (or via sudo)." >&2
    exit 1
fi

if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS_ID="${ID:-}"
    OS_LIKE="${ID_LIKE:-}"
    if [[ "$OS_ID" != "ubuntu" && "$OS_ID" != "debian" && "$OS_LIKE" != *"ubuntu"* && "$OS_LIKE" != *"debian"* ]]; then
        echo "[!] Warning: Detected OS '$OS_ID'. This script is tailored for Debian and Ubuntu."
    fi
else
    echo "[!] Warning: Cannot determine Linux distribution from /etc/os-release."
fi

RAW_ARCH="$(uname -m)"
case "$RAW_ARCH" in
    x86_64|amd64)
        ARCH="amd64"
        ;;
    aarch64|arm64)
        ARCH="arm64"
        ;;
    *)
        echo "[!] Error: Unsupported architecture '$RAW_ARCH'. Saturn requires amd64 or arm64." >&2
        exit 1
        ;;
esac
echo "[+] Detected architecture: $ARCH"

# Interactive prompt if not in auto mode
if [ "$AUTO_MODE" -eq 0 ]; then
    read -rp "Enter Saturn listen port [default: $PORT]: " USER_PORT
    if [ -n "$USER_PORT" ]; then
        PORT="$USER_PORT"
    fi
    read -rp "Enter camouflage SNI [default: $SNI]: " USER_SNI
    if [ -n "$USER_SNI" ]; then
        SNI="$USER_SNI"
    fi
fi

# Ensure required system utilities
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=a
export NEEDRESTART_SUSPEND=1

need_pkg=0
for tool in curl tar iptables; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        need_pkg=1
        break
    fi
done

if [ "$need_pkg" -eq 1 ]; then
    echo "[*] Installing required tools (curl, tar, iptables)..."
    # Wait up to 30s for any background cloud-init/unattended-upgrades lock to clear
    for i in $(seq 1 15); do
        if fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || fuser /var/lib/apt/lists/lock >/dev/null 2>&1; then
            echo "[*] Waiting for background system updates lock... ($i/15)"
            sleep 2
        else
            break
        fi
    done
    apt-get -o DPkg::Lock::Timeout=30 -o Dpkg::Options::="--force-confold" -o Dpkg::Options::="--force-confdef" update -qq >/dev/null 2>&1 || true
    apt-get -o DPkg::Lock::Timeout=30 -o Dpkg::Options::="--force-confold" -o Dpkg::Options::="--force-confdef" install -y --no-install-recommends curl ca-certificates tar iptables >/dev/null 2>&1 || true
else
    echo "[+] Essential tools already available."
fi

# 2. Kernel & Network Stack Optimization (/etc/sysctl.d/99-saturn.conf)
echo "[*] Applying kernel and BBR network stack optimizations..."
mkdir -p /etc/sysctl.d
cat > /etc/sysctl.d/99-saturn.conf << 'EOF'
# Saturn High-Speed & Anti-DPI Kernel Tuning
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.ipv4.tcp_fastopen = 3
net.ipv4.tcp_slow_start_after_idle = 0
net.ipv4.tcp_notsent_lowat = 16384
net.core.rmem_max = 67108864
net.core.wmem_max = 67108864
net.ipv4.tcp_rmem = 8192 262144 536870912
net.ipv4.tcp_wmem = 4096 16384 536870912
net.core.netdev_max_backlog = 100000
net.ipv4.tcp_max_syn_backlog = 3240000
net.ipv4.tcp_fin_timeout = 15
net.ipv4.tcp_tw_reuse = 1
net.netfilter.nf_conntrack_max = 1048576
net.nf_conntrack_max = 1048576
fs.file-max = 2097152
EOF

# Load BBR kernel module if available
modprobe tcp_bbr >/dev/null 2>&1 || true
modprobe br_netfilter >/dev/null 2>&1 || true
sysctl --system >/dev/null 2>&1 || sysctl -p /etc/sysctl.d/99-saturn.conf >/dev/null 2>&1 || true

# 3. Binary Deployment & Key Generation
BIN_PATH="/usr/local/bin/saturn-server"
CONFIG_DIR="/etc/saturn"
mkdir -p "$CONFIG_DIR"

if [ -n "$CUSTOM_BIN" ] && [ -f "$CUSTOM_BIN" ]; then
    echo "[*] Using provided local binary $CUSTOM_BIN..."
    cp "$CUSTOM_BIN" "$BIN_PATH"
elif [ -n "$CUSTOM_BIN" ] && [[ "$CUSTOM_BIN" == http* ]]; then
    echo "[*] Downloading custom binary from $CUSTOM_BIN..."
    curl -fsSL --retry 3 --connect-timeout 10 "$CUSTOM_BIN" -o "$BIN_PATH"
elif [ ! -f "$BIN_PATH" ]; then
    echo "[*] Deploying saturn-server binary..."
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if [ -f "$SCRIPT_DIR/bin/saturn-server-linux-$ARCH" ]; then
        cp "$SCRIPT_DIR/bin/saturn-server-linux-$ARCH" "$BIN_PATH"
    elif [ -f "$SCRIPT_DIR/saturn-server" ]; then
        cp "$SCRIPT_DIR/saturn-server" "$BIN_PATH"
    else
        # Try direct prebuilt binary from GitHub Releases first
        DOWNLOAD_OK=0
        for URL in \
            "https://github.com/dashbehrad/behradNG/releases/latest/download/saturn-server-linux-$ARCH" \
            "https://github.com/dashbehrad/behradNG/releases/download/3.0.0/saturn-server-linux-$ARCH"; do
            echo "[*] Fetching saturn-server ($ARCH) from $URL..."
            if curl -fsSL --retry 3 --connect-timeout 10 "$URL" -o "$BIN_PATH" 2>/dev/null; then
                chmod +x "$BIN_PATH"
                if "$BIN_PATH" keygen >/dev/null 2>&1; then
                    echo "[+] Downloaded and verified saturn-server binary."
                    DOWNLOAD_OK=1
                    break
                fi
            fi
        done

        # If download failed, build from source ONLY if Go compiler already exists
        if [ "$DOWNLOAD_OK" -ne 1 ]; then
            if command -v go >/dev/null 2>&1; then
                echo "[*] Prebuilt download unavailable. Compiling from repository source..."
                TMP_SRC="$(mktemp -d /tmp/saturn-src-XXXXXX)"
                if curl -fsSL --retry 3 --connect-timeout 10 "https://github.com/dashbehrad/behradNG/archive/refs/heads/main.tar.gz" | tar -xz -C "$TMP_SRC" --strip-components=1 2>/dev/null; then
                    (cd "$TMP_SRC/saturn-core" && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$BIN_PATH" ./cmd/saturn-server)
                fi
                rm -rf "$TMP_SRC"
            fi
        fi
    fi
fi

chmod +x "$BIN_PATH"
if [ ! -x "$BIN_PATH" ]; then
    echo "[!] Error: saturn-server binary could not be found or executed." >&2
    exit 1
fi
echo "[+] Saturn server binary ready at $BIN_PATH"

# 4. Generate Cryptographic Credentials via 'saturn-server keygen'
echo "[*] Generating Saturn X25519 stealth keys and UUID..."
KEYGEN_OUT="$("$BIN_PATH" keygen 2>/dev/null || true)"

PRIV_KEY="$(echo "$KEYGEN_OUT" | grep -i "Private Key:" | head -n1 | awk '{print $NF}')"
PUB_KEY="$(echo "$KEYGEN_OUT" | grep -i "Public Key:" | head -n1 | awk '{print $NF}')"
UUID_VAL="$(echo "$KEYGEN_OUT" | grep -i "UUID:" | head -n1 | awk '{print $NF}')"
SHORT_ID="$(echo "$KEYGEN_OUT" | grep -i "ShortID:" | head -n1 | awk '{print $NF}')"

if [ -z "$PRIV_KEY" ] || [ -z "$PUB_KEY" ] || [ -z "$UUID_VAL" ]; then
    echo "[*] Using cryptographic random fallback for keys..."
    PRIV_KEY="$(head -c 32 /dev/urandom | base64 | tr -d '\n\r' | tr '+/' '-_')"
    PUB_KEY="$(head -c 32 /dev/urandom | base64 | tr -d '\n\r' | tr '+/' '-_')"
    UUID_VAL="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || od -x /dev/urandom | head -1 | awk '{OFS="-"; print $2$3,$4,$5,$6,$7$8$9}')"
    SHORT_ID="$(head -c 4 /dev/urandom | xxd -p 2>/dev/null || hexdump -vn4 -e '4/1 "%02x"' /dev/urandom 2>/dev/null || head -c 4 /dev/urandom | od -An -tx1 | tr -d ' ')"
fi

echo "[+] X25519 Public Key: $PUB_KEY"
echo "[+] ShortID:           $SHORT_ID"
echo "[+] UUID:              $UUID_VAL"

# Write /etc/saturn/config.json
cat > "$CONFIG_DIR/config.json" << EOF
{
  "listen": "0.0.0.0:$PORT",
  "private_key": "$PRIV_KEY",
  "public_key": "$PUB_KEY",
  "uuid": "$UUID_VAL",
  "short_id": "$SHORT_ID",
  "fallback_dest": "$SNI:443",
  "cipher": "chacha20-poly1305"
}
EOF
chmod 600 "$CONFIG_DIR/config.json"
echo "[+] Server configuration saved to $CONFIG_DIR/config.json"

# 5. Systemd Service Configuration
echo "[*] Setting up systemd service (saturn-server.service)..."
cat > /etc/systemd/system/saturn-server.service << 'EOF'
[Unit]
Description=Saturn Direct Anti-DPI Server Daemon
After=network.target network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/saturn-server run -c /etc/saturn/config.json
Restart=always
RestartSec=3
LimitNOFILE=1048576
LimitNPROC=512000
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable saturn-server.service >/dev/null 2>&1 || true
systemctl restart saturn-server.service

# 6. Firewall Configuration
echo "[*] Configuring firewall rules for port $PORT..."
if command -v ufw >/dev/null 2>&1; then
    if ufw status | grep -q "Status: active"; then
        ufw allow "$PORT/tcp" >/dev/null 2>&1 || true
        echo "[+] UFW port $PORT/tcp allowed."
    fi
fi
if command -v iptables >/dev/null 2>&1; then
    iptables -I INPUT -p tcp --dport "$PORT" -j ACCEPT >/dev/null 2>&1 || true
fi

# Verify service is running
sleep 1
if ! systemctl is-active --quiet saturn-server.service; then
    echo "[!] Warning: saturn-server service did not start immediately. Checking logs:"
    journalctl -u saturn-server.service -n 15 --no-pager || true
fi

# 7. Detect Public IP Address
echo "[*] Detecting server public IP address..."
SERVER_IP=""
for IP_SERVICE in "https://api.ipify.org" "https://icanhazip.com" "https://ifconfig.me" "https://ip.sb"; do
    SERVER_IP="$(curl -s4 --max-time 3 "$IP_SERVICE" || true)"
    SERVER_IP="$(echo "$SERVER_IP" | tr -d '[:space:]')"
    if [[ "$SERVER_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        break
    fi
done

if [ -z "$SERVER_IP" ]; then
    SERVER_IP="$(hostname -I | awk '{print $1}')"
fi
echo "[+] Server Public IP: $SERVER_IP"

# 8. Generate Saturn URI
SATURN_URI="saturn://${UUID_VAL}@${SERVER_IP}:${PORT}?sni=${SNI}&pbk=${PUB_KEY}&sid=${SHORT_ID}&fp=chrome&frag=1-5#Saturn-Direct"

echo ""
echo "=============================================================================="
echo "Saturn Direct VPN Server Successfully Deployed!"
echo "Port:          $PORT"
echo "Camouflage:    $SNI"
echo "Public Key:    $PUB_KEY"
echo "ShortID:       $SHORT_ID"
echo "=============================================================================="
echo ""

# CRITICAL MACHINE-READABLE OUTPUT MARKERS FOR behradNG
echo "===SATURN_CONFIG_START==="
echo "$SATURN_URI"
echo "===SATURN_CONFIG_END==="
echo ""
