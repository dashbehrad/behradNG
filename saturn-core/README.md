<div align="center">

<img src="assets/logo.jpg" alt="Saturn Core Logo" width="220" style="border-radius: 50%; box-shadow: 0 8px 30px rgba(0,0,0,0.25);" />

# 🪐 Saturn Core

**The Next-Generation, Ultra-Stealth, Anti-Censorship Tunnel Engine**
*Defeating Deep Packet Inspection (DPI) & Active Probing on Hostile Networks*

[![Go Version](https://img.shields.io/github/go-mod/go-version/dashbehrad/Saturn-core?style=flat-square&logo=go)](https://go.dev/)
[![Release](https://img.shields.io/github/v/release/dashbehrad/Saturn-core?style=flat-square&logo=github)](https://github.com/dashbehrad/Saturn-core/releases)
[![GHCR Container](https://img.shields.io/badge/GHCR-Docker%20Package-blue?style=flat-square&logo=docker)](https://github.com/dashbehrad/Saturn-core/pkgs/container/saturn-core)
[![License](https://img.shields.io/github/license/dashbehrad/Saturn-core?style=flat-square)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Windows%20%7C%20Linux%20%7C%20macOS%20%7C%20Termux-brightgreen?style=flat-square)]()

[⚡ One-Click Installers](#one-click-installers) | [English](#english) | [راهنمای فارسی](#persian) | [Zero-Config Engine](#zero-config)

---

</div>

<a name="one-click-installers"></a>
## ⚡ One-Click Installers (نصب با یک کلیک در تمامی پلتفرم‌ها)

Install and run Saturn across any operating system using a single command:

| Platform / محیط | One-Line Installation Command / دستور نصب یک‌خطی |
| :--- | :--- |
| **🌐 Universal (Auto-Detect)** | `curl -fsSL https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@main/install.sh \| bash` |
| **🐧 Linux Server (Automated)** | `curl -fsSL https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@main/scripts/install-server.sh \| sudo bash` |
| **🪟 Windows (PowerShell - ضد فیلتر ایران)** | `irm https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@v1.0/scripts/install-windows.ps1 \| iex` |
| **🪟 Windows (CMD / بدون نیاز به پاورشل)** | `curl -fsSL https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@v1.0/scripts/install-windows.bat -o install.bat && install.bat` |
| **📱 Android Termux** | `curl -fsSL https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@main/scripts/install-termux.sh \| bash` |
| **🍏 macOS (Intel & M-Series)** | `curl -fsSL https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@main/scripts/install-mac.sh \| bash` |

---

<a name="zero-config"></a>
## 🪄 Zero-Config Auto Generation (تولید خودکار کانفیگ در هر سیستم‌عامل)

> **آیا با اجرای هسته روی هر سیستم‌عامل، کانفیگ به صورت خودکار تولید می‌شود؟**
> **بله! ۱۰۰٪ خودکار.**

هسته ساتورن کاملاً **Zero-Configuration** طراحی شده است:
1. **سرور (`saturn-server`):**
   - به محض اجرای `saturn-server` روی ویندوز، لینوکس، مک یا ترموکس، در صورتی که فایل کانفیگ وجود نداشته باشد، هسته به صورت هوشمند و بدون نیاز به ورودی کاربر:
     - جفت کلید رمزنگاری نامتقارن **Curve25519** را تولید می‌کند.
     - شناسه **UUID** اختصاصی و **ShortID** ایمن می‌سازد.
     - آی‌پی عمومی سرور را به صورت خودکار شناسایی می‌کند.
     - فایل `config.json` را ذخیره کرده، لینک مستقیم اتصال `saturn://` را در ترمینال چاپ می‌کند و بلافاصله پورت ۴۴۳ را باز و شروع به سرویس‌دهی می‌نماید!
2. **کلاینت (`saturn-client`):**
   - در صورت اجرای مستقیم بدون پارامتر، فایل کانفیگ محلی `client.json` را بررسی می‌کند؛ اگر وجود نداشته باشد، به طور خودکار فایل قالب `client.json` را تولید کرده و نمونه نحوه اتصال از طریق لینک `saturn://` را نمایش می‌دهد.

---

<a name="english"></a>
## 🌟 Overview (English)

**Saturn Core** is an enterprise-grade, ultra-stealth tunneling protocol engineered from the ground up to guarantee resilient, high-speed Internet access through stateful national firewalls and aggressive middleboxes (such as the Iranian Great Firewall, GFW, and Russian DPI).

Saturn conceals traffic inside authentic **TLS 1.3 ClientHello** records using genuine browser fingerprints (Google Chrome, Apple Safari, Mozilla Firefox). Under the hood, it features the **Alan DPI Evasion Engine**—a modular library of 24 evasion strategies including polymorphic TCP `MSG_OOB` urgent-pointer desynchronization, SNI fragmentation, dummy preambles, and proactive 1.5MB flow rotation to shatter stateful carrier throttling.

### 🚀 Key Innovations & Architecture

- **Genuine TLS 1.3 Mimicry:** Saturn mimics authentic modern browser ClientHello fingerprints (uTLS `HelloChrome_Auto`, `HelloFirefox_Auto`, `HelloSafari_Auto`) with real GREASE extensions, ALPN (`h2, http/1.1`), supported groups (`X25519, secp256r1`), and key shares. Active probe scanners receive a standard HTTP/2 fallback response from legitimate destinations (e.g. `dl.google.com`).
- **Alan DPI Evasion Engine (24 Vectors):**
  - **SNI Segmentation:** Splits domain name pointers across multiple TCP segments and TLS records.
  - **TCP `MSG_OOB` Urgent Data Desync:** Sends 1-byte out-of-band markers with TCP Urgent pointers (`URG`), causing stateful firewall reassembly buffers to drop or desynchronize without corrupting user-space stream data.
  - **Preamble Decoys & Garbage Padding:** Delivers randomized dummy records ahead of handshake packets to trigger early heuristic acceptance or parser timeouts in middleboxes.
  - **Case Inversion & Fake Headers:** Inverts SNI hostname casing and prepends synthetic HTTP GET requests.
- **Stateful Flow Auto-Rotation (1.5 MB Window):** Iranian mobile carriers (MCI, Irancell LTE/5G) employ flow-based burst throttling and drop long-lived connections after fixed data thresholds. Saturn monitors stream volumes and automatically rotates underlying TCP sessions once 1.5 MB is transmitted, preserving zero perceived latency or dropped downloads.
- **Zero-Stall Multiplexing (`smux` v2):** Multiplexes hundreds of independent SOCKS5 / TUN streams over a resilient connection pool (2-4 active sessions) with mobile-optimized buffer windows (8MB receive / 2MB stream) and aggressive 8s keep-alives.
- **Anti-Fragmentation MTU Clamping:** Caps packet chunks to `<= 1220 bytes` with smart conditional padding to prevent carrier CGNAT packet drops over tight 1360 Path MTU links.
- **Zero-Config Cross-Platform:** Single standalone statically compiled binary for **Windows (x64/ARM64)**, **Linux (x64/ARM64)**, **macOS (Intel/Apple Silicon)**, and **Android Termux**.

---

<a name="persian"></a>
## 🇮🇷 راهنمای فارسی (Persian)

**ساتورن (Saturn Core)** یک هسته تونل‌سازی فوق امنیتی و غیرقابل ردگیری است که منحصراً برای عبور پایدار، نامحدود و پرسرعت از سیستم‌های فیلترینگ شدید و تجهیزات DPI اپراتورهای ایران (همراه اول، ایرانسل، رایتل و مخابرات) طراحی شده است.

### 🛡️ ویژگی‌های برجسته هسته ساتورن

1. **پنهان‌سازی کامل در پوشش TLS 1.3 واقعی:** کلاینت بسته‌های خود را با امضای بیلد مرورگرهای کروم و سافاری به همراه دامنه‌های مجاز (SNI مانند dl.google.com) ارسال می‌کند. سیستم‌های فیلترینگ ترافیک را دقیقاً مانند یک ارتباط وب معمولی می‌بینند.
2. **موتور پیشرفته Alan DPI با ۲۴ متد شکستن فیلتر:**
   - تکه‌تکه کردن SNI در بسته‌های مجزا (SNI Fragmentation).
   - ارسال بایت اضطراری با پرچم `MSG_OOB` / `URG` در لایه TCP برای گمراه کردن و از کار انداختن حافظه بافر فیلترچی بدون ایجاد اختلال در دیتا.
   - تزریق رکوردهای فیک و هدرهای گمراه‌کننده (Fake HTTP / Preamble Records).
3. **چرخش خودکار سشن (Uplink Rotation):** برای خنثی کردن سیستم محدودسازی حجم و بستن کانکشن‌ها توسط ایرانسل و همراه اول بعد از ۱.۵ مگابایت، ساتورن به طور نامحسوس و خودکار کانکشن را بدون قطعی اینترنت رفرش می‌کند.
4. **تنظیم هوشمند MTU (ضد Packet Drop):** جلوگیری از قطعی آپلود و افت سرعت در اینترنت همراه به وسیله محدودسازی بسته‌ها به ۱۲۲۰ بایت و پدینگ هوشمند.
5. **استخر کانکشن‌های مالتی‌پلکس (Multi-Session Pool):** پایداری کامل دانلود و وب‌گردی همزمان بدون ایجاد گلوگاه.
6. **قابل استفاده در تمام سیستم‌عامل‌ها:** ویندوز، لینوکس، مک و ترموکس اندروید (بدون نیاز به روت).

---

## 💻 Platform Guides & Quick Start (راهنمای استفاده در پلتفرم‌ها)

### 1. 🪟 Windows (ویندوز)

> [!TIP]
> **راهنمای کاربران داخل ایران:**
> دامنه `raw.githubusercontent.com` در اکثر اینترنت‌های ایران فیلتر است و دستور قدیمی `irm` ممکن است با تایم‌اوت مواجه شود. برای نصب بدون مشکل، از **روش اول (CDN)** یا **روش دوم (CMD)** استفاده کنید که ۱۰۰٪ روی اپراتورهای ایران کار می‌کنند.

#### روش اول: پاورشل با CDN پرسرعت ضد تحریم (پیشنهادی)
پاورشل (PowerShell) را باز کرده و دستور زیر را اجرا کنید:
```powershell
irm https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@v1.0/scripts/install-windows.ps1 | iex
```

#### روش دوم: خط فرمان معمولی CMD (بدون نیاز به پاورشل)
کلیدهای `Win + R` را بزنید، `cmd` را تایپ و Enter کنید، سپس دستور زیر را پیست کنید:
```cmd
curl -fsSL https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@v1.0/scripts/install-windows.bat -o install.bat && install.bat
```

#### روش سوم: چند مسیره خودکار (Multi-Mirror Auto-Fallback)
اگر در شبکه بسیار بسته‌ای هستید، این دستور در پاورشل به نوبت از ۳ سرور مختلف تست می‌کند:
```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13; @('https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@main/scripts/install-windows.ps1','https://raw.gitmirror.com/dashbehrad/Saturn-core/main/scripts/install-windows.ps1','https://ghproxy.net/https://raw.githubusercontent.com/dashbehrad/Saturn-core/main/scripts/install-windows.ps1') | ForEach-Object { try { $s = (New-Object Net.WebClient).DownloadString($_); if ($s) { Invoke-Expression $s; break } } catch {} }
```

#### روش چهارم: دانلود مستقیم و دستی (بدون دستور و اسکریپت)
1. فایل زیپ باینری را مستقیماً دانلود کنید:
   - [دانلود مستقیم saturn-windows-amd64.zip از گیت‌هاب](https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-windows-amd64.zip)
   - [دانلود از میرور پرسرعت بدون فیلتر](https://ghproxy.net/https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-windows-amd64.zip)
2. فایل را اکسترکت (Extract) کنید.
3. روی فایل `install-windows.bat` یا `saturn-client.exe` دو بار کلیک کنید!
اسکریپت به صورت خودکار شورتکات دسکتاپ **🪐 Saturn Client** را ایجاد می‌کند و با دو بار کلیک، پروکسی لوکال روی `127.0.0.1:10809` فعال می‌شود.

#### Manual Run (اجرای دستی)
```powershell
# Option A: Connect using saturn:// URI
.\saturn-client.exe -u "saturn://UUID@YOUR_SERVER_IP:443?pbk=PUBLIC_KEY&sid=SHORT_ID&sni=dl.google.com&alpn=h2&fp=chrome&cipher=chacha20-poly1305#Saturn"

# Option B: Connect using client.json
.\saturn-client.exe -c .\client.json
```

#### Configure System / Browser Proxy on Windows
- **Windows System Proxy:** Open Windows Settings ➔ Network & Internet ➔ Proxy ➔ Set Manual Proxy Server to `127.0.0.1` and Port `10809`.
- **Browser (Chrome/Firefox/Edge):** Use extensions like *SwitchyOmega* or *FoxyProxy* pointing to SOCKS5 `127.0.0.1:10809`.
- **Telegram Desktop:** Settings ➔ Advanced ➔ Connection type ➔ Use custom proxy ➔ SOCKS5: `127.0.0.1:10809`.

---

### 2. 🐧 Linux (Desktop & Server)

#### 🚀 Automated Server Setup (نصب یک‌کلیکی سرور لینوکس)
Execute the one-line auto-installer with root privileges:
```bash
curl -fsSL https://raw.githubusercontent.com/dashbehrad/Saturn-core/main/scripts/install-server.sh | sudo bash
```

> [!TIP]
> **میرور ضد تحریم / CDN:** در صورت اختلال در دسترسی به گیت‌هاب خام، از لینک CDN استفاده کنید:
> ```bash
> curl -fsSL https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@main/scripts/install-server.sh | sudo bash
> ```

#### 🛠️ Server Management Commands (دستورات مدیریت سرور لینوکس)

| Action / عملیات | Command / دستور |
| :--- | :--- |
| **مشاهده وضعیت سرویس** | `sudo systemctl status saturn-server` |
| **راه‌اندازی مجدد (Restart)** | `sudo systemctl restart saturn-server` |
| **متوقف کردن سرور (Stop)** | `sudo systemctl stop saturn-server` |
| **روشن کردن سرور (Start)** | `sudo systemctl start saturn-server` |
| **فعال‌سازی در بوت سیستم** | `sudo systemctl enable saturn-server` |
| **مشاهده لاگ‌های زنده (Live Logs)** | `sudo journalctl -u saturn-server -f` |
| **مشاهده آخرین ۵۰ خط لاگ** | `sudo journalctl -u saturn-server -n 50 --no-pager` |
| **مشاهده فایل کانفیگ و کلیدها** | `sudo cat /etc/saturn/config.json` |
| **ویرایش کانفیگ (پورت یا SNI)** | `sudo nano /etc/saturn/config.json` *(سپس restart کنید)* |
| **تولید جفت‌کلید و UUID جدید** | `saturn-server keygen` |
| **باز کردن پورت ۴۴۳ در فایروال** | `sudo ufw allow 443/tcp` |

#### 🔗 Display Connection URI (نمایش مجدد لینک اتصال `saturn://`)
برای نمایش مجدد لینک اتصال کانکشن سرور خود:
```bash
sudo python3 -c "import json, urllib.request; c=json.load(open('/etc/saturn/config.json')); ip=urllib.request.urlopen('https://api.ipify.org').read().decode('utf-8'); print(f'saturn://{c[\"uuid\"]}@{ip}:443?pbk={c[\"public_key\"]}&sid={c[\"short_id\"]}&sni=dl.google.com&alpn=h2&fp=chrome&cipher=chacha20-poly1305#Saturn-{ip}')"
```

#### 🗑️ Uninstall Saturn Server (حذف کامل سرور)
```bash
sudo systemctl stop saturn-server && sudo systemctl disable saturn-server
sudo rm -f /etc/systemd/system/saturn-server.service /usr/local/bin/saturn-server
sudo rm -rf /etc/saturn
sudo systemctl daemon-reload
```

#### 🖥️ Client Mode on Linux (کلاینت لینوکس)
```bash
# Universal install
curl -fsSL https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@main/install.sh | bash

# Run client
saturn-client -u "saturn://UUID@SERVER_IP:443?pbk=KEY&sid=SID#Saturn"
```

---

### 3. 📱 Android Termux (ترموکس بدون روت)

اجرای آسان کلاینت ساتورن در محیط ترموکس گوشی بدون نیاز به روت با یک دستور:
```bash
curl -fsSL https://raw.githubusercontent.com/dashbehrad/Saturn-core/main/scripts/install-termux.sh | bash
```
پس از نصب:
```bash
# ۱. اجرای کلاینت در پس‌زمینه
saturn-client -u "saturn://..." &

# ۲. فعال‌سازی پروکسی برای کل ترمینال
source saturn-on

# ۳. تست اتصال (اکنون دستورات pkg, git و curl بدون محدودیت کار می‌کنند)
curl -I https://www.google.com

# ۴. غیرفعال‌سازی پروکسی
source saturn-off
```

---

### 4. 🍏 macOS (Intel & Apple Silicon M1/M2/M3/M4)

نصب خودکار کلاینت و ابزارهای سوئیچ پروکسی در مک:
```bash
curl -fsSL https://raw.githubusercontent.com/dashbehrad/Saturn-core/main/scripts/install-mac.sh | bash
```
پس از نصب:
- اجرای کلاینت: `saturn-client -u "saturn://..." &`
- فعال‌سازی خودکار پروکسی سیستمی مک: `saturn-mac-on`
- غیرفعال‌سازی پروکسی سیستمی: `saturn-mac-off`

---

### 5. 🐳 Docker & GitHub Packages (GHCR)

Pre-built multi-architecture Docker containers (`linux/amd64`, `linux/arm64`) are hosted on GitHub Packages:

#### Pull & Run Saturn Server:
```bash
docker pull ghcr.io/dashbehrad/saturn-core:latest

# Run Server (Zero-Config: automatically generates keys and serves)
docker run -d \
  --name saturn-server \
  --restart always \
  --network host \
  ghcr.io/dashbehrad/saturn-core:latest
```

#### Run Saturn Client via Docker:
```bash
docker run -d \
  --name saturn-client \
  --restart always \
  -p 10809:10809 \
  --entrypoint saturn-client \
  ghcr.io/dashbehrad/saturn-core:latest \
  -u "saturn://..." -l "0.0.0.0:10809"
```

---

## ⚙️ Client Configuration & CLI Parameters

### Client Configuration File (`client.json`)
```json
{
  "local_listen": "127.0.0.1:10809",
  "connections": 2,
  "profile": {
    "server": "1.2.3.4",
    "port": 443,
    "uuid": "d3b07384-d113-4660-8438-d6eb406e2329",
    "public_key": "<SERVER_PUBLIC_KEY_BASE64>",
    "short_id": "4f8b2a1e",
    "sni": "dl.google.com",
    "alpn": "h2",
    "cipher": "chacha20-poly1305",
    "fingerprint": "chrome",
    "dpi_vector": "VEC-10"
  }
}
```

### Supported DPI Vectors (`-dpi-vector`)
| Vector | Description |
| :--- | :--- |
| `auto` | Dynamic priority dispatching based on network conditions |
| `VEC-01` | SNI Split at offset 1 with 2ms interleaving |
| `VEC-05` | Reverse Fragment Order transmission |
| `VEC-10` | Polymorphic TCP `MSG_OOB` urgent data marker + SNI split |
| `VEC-14` | Dual-fragment overlap with dummy padding byte |
| `VEC-18` | TLS Preamble decoy record before ClientHello |
| `VEC-23` | Mixed Case SNI + TCP Urgent Pointer Desynchronization |

---

## 🛠️ Building from Source

Requirements: Go 1.21 or newer.

```bash
# Clone the repository
git clone https://github.com/dashbehrad/Saturn-core.git
cd Saturn-core

# Run unit tests
go test -v ./...

# Compile for your current platform
go build -o bin/saturn-server ./cmd/saturn-server
go build -o bin/saturn-client ./cmd/saturn-client

# Cross-compile for all operating systems
bash ./scripts/build.sh
```

---

## 💖 Acknowledgments & Credits

- DPI evasion algorithms, polymorphic TCP urgent pointer mutation, and record desynchronization techniques are inspired by and adapted from the research in **[UAC-SNI-Spoofer](https://github.com/Floxu1/UAC-SNI-Spoofer-Android)** by **Floxu1** and **[Alan-RIP-Dpi-Windows](https://github.com/DrAlanK/Alan-RIP-Dpi-Windows)** by **DrAlanK**.
- uTLS library by **Refraction Networking**.
- smux multiplexing by **xtaci**.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
