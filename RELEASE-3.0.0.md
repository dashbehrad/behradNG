# behradNG 3.0.0 Release Notes

**Version Name:** `3.0.0` | **Version Code:** `4000776`

behradNG 3.0.0 introduces **Saturn (`saturn`)**, a custom, high-speed, direct anti-DPI VPN protocol engineered specifically to defeat sophisticated Deep Packet Inspection (DPI), SNI filtering, JA3/JA4 TLS fingerprinting, active probing, and packet entropy analysis in heavily restricted environments (such as Iran's network infrastructure).

Unlike conventional proxy configurations (VMess, Trojan, VLESS+WS) or relay tunnels, Saturn connects **directly** from the Android client to your foreign Linux VPS with zero intermediate relay overhead.

---

## What's New in 3.0.0

### 1. Saturn Protocol Engine (`saturn-core`)
- **Zero-Certificate Stealth Handshake & Active Probe Defense:**
  - Authenticates during the `TLS 1.3 ClientHello` using an `X25519` key exchange and `HKDF-SHA256` token embedded inside the 32-byte `SessionID` and `ClientRandom` fields (incorporating timestamp and ShortID to prevent replay attacks).
  - **Active Probe Defense:** When censors, scanners, or active probers probe the server port, `saturn-server` transparently splices the raw TCP stream to the configured camouflage target (e.g., `dl.google.com:443`). Probers receive genuine Google TLS certificates and HTTP responses, completely neutralizing IP detection and blocking.
- **Client-Side Anti-DPI Evasion (`utls` + Fragmentation):**
  - Spoofs modern browser TLS fingerprints (`HelloChrome_Auto`, `HelloFirefox_Auto`) via `utls` to bypass JA3/JA4 inspection.
  - **Dynamic Record Fragmentation:** Splits the initial `ClientHello` across 2–4 TCP segments directly slicing through the SNI extension with configurable micro-delays (`1ms–5ms`) and `TCP_NODELAY`.
- **Compact Binary Header & First-N-Packets Traffic Morphing:**
  - Standardized 24-byte binary framing header with 96-bit IVs, 32-bit sequence numbers, and `smux v2` stream multiplexing.
  - Encrypted with hardware-accelerated `ChaCha20-Poly1305` or `AES-128-GCM` with a 128-bit authentication tag.
  - Appends `0–512` bytes of dynamic obfuscation padding on the first 12 packets of every stream to break entropy and packet-size signatures.
- **BBR Multiplexing:**
  - Persistent connection pooling (1–4 persistent connections) over BBR congestion-controlled TCP, eliminating per-request handshake delay (TTFB).

### 2. Built-in One-Click SSH Server Provisioner
- Deploy Saturn directly to any fresh **Ubuntu (20.04/22.04/24.04)** or **Debian (11/12)** Linux VPS directly from the app via **+ → Deploy Saturn Server (One-Click)**.
- Authenticate via SSH with **Password** or **SSH Private Key**.
- Automatically tunes the Linux kernel (`/etc/sysctl.d/99-saturn.conf` with `fq`, `bbr`, `tcp_fastopen=3`, conntrack), installs `saturn-server`, creates systemd service, configures firewall, and generates X25519 stealth keys.
- Real-time deployment console streams progress directly to the screen.
- Automatically parses and imports the generated `saturn://` configuration with a single tap on **Finish & Connect**.

### 3. Automated Server Installer (`install-saturn.sh`)
- Standalone, idempotent installer script supporting interactive CLI and non-interactive headless (`--auto --port 443 --sni dl.google.com`) execution.
- Emits machine-readable output markers:
  ```text
  ===SATURN_CONFIG_START===
  saturn://<UUID>@<IP>:<PORT>?sni=<SNI>&pbk=<PUBKEY>&sid=<SHORTID>&fp=chrome&frag=1-5#Saturn-Direct
  ===SATURN_CONFIG_END===
  ```

### 4. Standard URI Specification (`saturn://`)
- Full support for sharing, copying, and importing direct Saturn profiles:
  `saturn://<UUID>@<SERVER_IP>:<PORT>?sni=<MASK_DOMAIN>&pbk=<X25519_PUBLIC_KEY>&sid=<SHORT_ID>&fp=chrome&frag=1-5#Saturn-Direct`
- Backward-compatible parsing for existing encrypted capability links.

---

## Compatibility & System Requirements
- Android 8.0+ (API 26+) for Saturn direct and Aether transports; Android 7.0+ (API 24+) for base v2rayNG protocols.
- Supported ABIs: ARM64 (`arm64-v8a`), ARMv7 (`armeabi-v7a`), x86_64, and x86.
- Full parity maintained across all app locales (`en`, `fa`, `ar`, `az`, `bn`, `bqi-rIR`, `ru`, `vi`, `zh-rCN`, `zh-rTW`).
