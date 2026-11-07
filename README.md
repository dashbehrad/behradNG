<div align="center">

<img src="V2rayNG/bng_logo.jpg" alt="behradNG logo" width="128" />

# behradNG

**Your connections. Your routes. Your Android client.**

An Android proxy client built on v2rayNG and Xray-core, with Aether and Saturn transports.

[![Download APK](https://img.shields.io/badge/Download-APK-16a085?style=for-the-badge&logo=android&logoColor=white)](https://github.com/dashbehrad/behradNG/releases/latest)
[![Build](https://github.com/dashbehrad/behradNG/actions/workflows/build.yml/badge.svg)](https://github.com/dashbehrad/behradNG/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPL_v3-blue.svg)](LICENSE)

[Features](#features) · [Transports](#aether--saturn) · [Build](#build-from-source) · [فارسی](#persian)

</div>

---

## Get started

1. Download the APK from [GitHub Releases](https://github.com/dashbehrad/behradNG/releases).
2. Install it on your Android device and open behradNG.
3. Import your configuration using a link, QR code, file, clipboard, or subscription.
4. Select a profile, connect, and accept Android's VPN permission when prompted.

The base client supports **Android 7.0+**. Aether and Saturn require **Android 8.0+** and packaged native libraries for ARMv7, ARM64, or x86_64. See the [installation guide](INSTALLATION.md) for more details.

## Features

| | What you can do |
| :--- | :--- |
| **Flexible profiles** | Use VLESS, VMess, Shadowsocks, Trojan, Hysteria2, WireGuard, SOCKS, and HTTP configurations. |
| **Simple imports** | Add individual profiles or subscriptions through links, QR codes, files, and the clipboard. |
| **Routing control** | Configure VPN and proxy-only modes, per-app routing, routing rules, proxy chains, and policy groups. |
| **Connection tools** | Customize DNS, fragmentation, mux, and sniffing; run latency and speed tests. |
| **Additional transports** | Configure Aether or Saturn alongside the familiar v2rayNG profile model. |

## Aether & Saturn

### Aether · Adaptive transport

Aether provides configurable carriers, optional fragmentation, and scan and transport settings. The Android service manages its native process and restarts the transport and routing core when the active network changes.

[Integration notes →](AETHER-INTEGRATION.md)

### Saturn · Direct Anti-DPI Protocol & One-Click VPS Provisioner

Saturn (`saturn`) is a custom, high-speed, direct VPN protocol engineered specifically to defeat sophisticated Deep Packet Inspection (DPI), SNI filtering, JA3/JA4 TLS fingerprinting, active probing, and packet entropy analysis in heavily censored environments (e.g., Iran's filtering infrastructure).

Unlike conventional configurations (VMess, Trojan, VLESS+WS) or relay tunnels, Saturn connects **directly** from the Android client to your foreign Linux VPS with zero intermediate relay overhead.

#### Key Protocol Innovations:
1. **Zero-Certificate Stealth Handshake & Active Probe Defense:**
   - Authenticates during the `TLS 1.3 ClientHello` using `X25519` key exchange and `HKDF-SHA256` tokens embedded directly inside `SessionID` and `ClientRandom` fields (incorporating timestamp and ShortID to prevent replay attacks).
   - **Active Probe Defense:** If an incoming connection is from a censor, active prober, or port scanner, `saturn-server` transparently splices the connection to the camouflage target (e.g., `dl.google.com:443`). Probers receive genuine Google TLS certificates and HTTP responses, completely neutralizing IP blacklisting.
2. **Client-Side Anti-DPI (uTLS + Dynamic Record Fragmentation):**
   - Spoofs genuine modern browser TLS fingerprints (`HelloChrome_Auto`, `HelloFirefox_Auto`) via `utls` to bypass JA3/JA4 inspection.
   - **Dynamic TLS Fragmentation:** Splits the initial `ClientHello` across 2–4 TCP segments directly slicing through the SNI domain with micro-delays (1–5ms) and `TCP_NODELAY`.
3. **Binary Header & First-N-Packets Traffic Morphing:**
   - 24-byte compact binary framing header (Word 1: Type 8b + StreamID 24b, Word 2: Len 16b + Pad 16b, Word 3: Seq 32b, Word 4-6: IV 96b).
   - Encrypted with hardware-accelerated `ChaCha20-Poly1305` or `AES-128-GCM` with a 128-bit authentication tag.
   - Appends `0–512` bytes of dynamic obfuscation padding on the first 12 packets of every stream to disrupt packet size and entropy signatures.
4. **Multiplexing with Native BBR:**
   - Integrated `smux v2` stream multiplexing across 1 to 4 persistent TCP connections over BBR-tuned Linux sockets, eliminating per-request handshake delay (TTFB).
5. **Built-in One-Click SSH Server Provisioner:**
   - Deploy Saturn to any fresh Ubuntu (20.04/22.04/24.04) or Debian (11/12) VPS directly from behradNG via **+ → Deploy Saturn Server (One-Click)**.
   - Connects via SSH (Password or Private Key), tunes the Linux kernel (`fq`, `bbr`, `tcp_fastopen=3`, conntrack), installs `saturn-server`, configures systemd and firewall, generates X25519 keys, streams logs in real-time, and automatically imports the resulting `saturn://` configuration with a single tap on **Finish & Connect**.
6. **Alan DPI Evasion Engine (24 Bypass Vectors & TCP OOB Desync):**
   - Incorporates the sophisticated DPI bypass and mutation algorithms adapted from [UAC-SNI-Spoofer-Android](https://github.com/Floxu1/UAC-SNI-Spoofer-Android) by [Floxu1](https://github.com/Floxu1).
   - Features 24 bypass vectors (`VEC-00` through `VEC-23`) with Autopilot priority dispatching (`VEC-21` Core Omega with Phantom Seam OOB, `VEC-22` Dual-Window Pincer, `VEC-01` Alpha, `VEC-18` Delta, `VEC-17` Aligned Relay, `VEC-20` Fibonacci 7-record segmentation, preambles, and polymorphic `MSG_OOB` urgent data injection).
   - Implemented via both an ANSI C library ([`libalandpi`](saturn-core/pkg/dpi/c_bridge/)) and a high-performance pure Go native engine ([`pkg/dpi`](saturn-core/pkg/dpi/)) with server-side multi-record assembly.

[Release notes →](RELEASE-3.0.0.md)

## Build from source

The repository uses **`V2rayNG/` as the single, unified Android project** containing the complete application, native integrations, and behradNG custom features.

### Windows

Follow [LOCAL-BUILD.md](LOCAL-BUILD.md) to prepare Java, Go, the Android SDK/NDKs, and local release signing files. From the repository root:

```powershell
# Check prerequisites without downloading or building.
.\build-local.ps1 -CheckOnly

# Prepare native dependencies, test, and build.
.\build-local.ps1
```

The script uses JDK 21 by default and accepts `-JavaPath` for another installation. Release signing requires your own private signing files as described in the build guide.

### Linux / CI

Use the [build workflow](.github/workflows/build.yml) as the reference for the pinned Java, Android SDK, NDK, Gradle, and native build steps:

1. Clone with `git clone --recurse-submodules https://github.com/dashbehrad/behradNG.git`.
2. Build the pinned Xray AAR and HEV libraries following the workflow.
3. Run `prepare-aether.sh`, `prepare-saturn.sh`, and `prepare-iran-rules.sh` with the required toolchains configured.
4. Build directly from the unified Android project:

```bash
cd V2rayNG
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug
```

Before assembling, restore the Gradle wrapper JAR if missing and generate `V2rayNG/debug.keystore` using the workflow's preparation steps. Release builds use `:app:assembleRelease` and require the signing environment variables defined in the workflow. Pull requests build a debug APK; push and manual runs build and publish a release APK.

<details>
<summary><strong>Repository map</strong></summary>

| Path | Purpose |
| :--- | :--- |
| [`V2rayNG/`](V2rayNG/) | Complete unified Android application and Gradle project |
| [`saturn-core/`](saturn-core/) | Saturn direct anti-DPI VPN protocol engine (Go) |
| [`native/`](native/) | Aether native integration launcher |
| [`AndroidLibXrayLite/`](AndroidLibXrayLite/) | Xray native core submodule |
| [`hev-socks5-tunnel/`](hev-socks5-tunnel/) | HEV tunnel submodule |
| [`.github/workflows/build.yml`](.github/workflows/build.yml) | Native preparation, tests, APK checks, and publishing |

</details>

## Contributing

[Report an issue](https://github.com/dashbehrad/behradNG/issues) with reproduction steps, Android version, and relevant logs with private details removed. Keep pull requests focused and include the validation commands you ran. Read [AGENTS.md](AGENTS.md) and any applicable scoped guide before editing.

Keep signing keys, private proxy credentials, generated APKs, and native build caches out of commits.

## Credits & license

Based on [v2rayNG](https://github.com/2dust/v2rayNG), with [Xray-core](https://github.com/XTLS/Xray-core), [HEV](https://github.com/heiher/hev-socks5-tunnel), and [URnetwork](https://github.com/urnetwork) components.

DPI evasion engine, mutation vectors, and urgent OOB desynchronization algorithms adapted from [Floxu1](https://github.com/Floxu1/UAC-SNI-Spoofer-Android) by [Floxu1](https://github.com/Floxu1).

behradNG is distributed under the [GNU GPLv3](LICENSE). Bundled dependencies retain their respective licenses and notices.

---

<a id="persian"></a>

<div dir="rtl">

## راهنمای فارسی

**behradNG؛ مدیریت اتصال و مسیریابی در اندروید**

behradNG بر پایه v2rayNG و Xray-core ساخته شده و در کنار امکانات آشنای آن، دو ترنسپورت **Aether** و **Saturn** را ارائه می‌کند.

### شروع سریع

۱. فایل APK را از [صفحه انتشارها](https://github.com/dashbehrad/behradNG/releases) دانلود و نصب کنید.

۲. کانفیگ یا اشتراک خود را با لینک، کد QR، فایل یا کلیپ‌بورد وارد کنید.

۳. یک پروفایل انتخاب کنید، اتصال را بزنید و در صورت نمایش، مجوز VPN اندروید را تأیید کنید.

برنامه از **اندروید ۷ به بالا** پشتیبانی می‌کند. Aether و Saturn به **اندروید ۸ به بالا** و کتابخانه‌های بومی برای ARMv7، ARM64 یا x86_64 نیاز دارند.

### امکانات

- پشتیبانی از VLESS، VMess، Shadowsocks، Trojan، Hysteria2، WireGuard، SOCKS و HTTP.
- حالت VPN و پروکسی، مسیریابی برنامه‌به‌برنامه، قوانین مسیریابی و زنجیره پروکسی.
- تنظیمات DNS، Fragment، Mux و Sniffing، همراه با تست تأخیر و سرعت.
- **Aether:** ترنسپورت تطبیقی با تنظیمات Carrier، اسکن و Fragment و راه‌اندازی مجدد هنگام تغییر شبکه.
- **Saturn؛ پروتکل مستقیم ضد فیلترینگ و نصب خودکار سرور:**
  - **اتصال مستقیم به VPS:** اتصال مستقیم از گوشی به سرور لینوکس خارج از کشور بدون نیاز به هیچ رله یا واسط، با پینگ فوق‌العاده پایین و سرعت بالا.
  - **دست‌تکان نامرئی (Stealth Handshake):** احراز هویت کلاینت با تبادل کلید `X25519` و توکن `HKDF-SHA256` درون فیلدهای `SessionID` و `Random` پکت اول `TLS 1.3 ClientHello` بدون ایجاد هیچ امضای اضافه.
  - **خنثی‌سازی اسکنرهای فایروال (Active Probe Fallback):** هدایت یکپارچه و نامرئی (Splice) اسکنرها و پروب‌های فیلترینگ به سایت استتار (مانند `dl.google.com:443`) به طوری که سرور گواهی معتبر گوگل را به فایروال نشان می‌دهد.
  - **دور زدن DPI با uTLS و فرگمنت پویا:** جعل اثرانگشت مرورگرهای مطرح و برش چندتکه‌ای پکت اولیه در محل SNI با تاخیرهای ۱ تا ۵ میلی‌ثانیه برای دور زدن فیلترینگ مبتنی بر بازرسی بسته‌ها.
  - **موتور دور زدن فیلترینگ Alan DPI (۲۴ وکتور و تزریق OOB):** ادغام موتور جهش و دور زدن DPI با ذکر منبع از پروژه [Alan-RIP-Dpi](https://github.com/DrAlanK/Alan-RIP-Dpi-Windows) اثر [DrAlanK](https://github.com/DrAlanK) شامل ۲۴ وکتور دور زدن فیلترینگ (`VEC-00` تا `VEC-23`)، ارسال بایت‌های اضطراری `MSG_OOB` برای به هم ریختن بافر استیت‌فول فایروال‌های DPI ایران، قطعه‌بندی فیبوناچی ۷ رکوردی حول SNI، پیش‌درآمدها (Preambles) و اسمبل هوشمند چندرکوردی در سرور ساتورن.
  - **فریم‌بندی باینری سبک با رمزنگاری سخت‌افزاری:** هدر ۲۴ بایتی فشرده، رمزنگاری `ChaCha20-Poly1305` یا `AES-128-GCM` و تزریق پدینگ تصادفی در ۱۲ پکت اول برای به هم زدن تحلیل سایز و آنتروپی ترافیک.
  - **نصب‌کننده خودکار سرور با یک کلیک (One-Click SSH Deploy):** امکان نصب کامل سرور روی اوبونتو یا دبیان خام، بهینه‌سازی BBR، راه‌اندازی سرویس و دریافت کانفیگ `saturn://` مستقیماً از داخل برنامه.

### ساخت و مشارکت

پروژه کامل اندروید در پوشه تک‌منبعی و یکپارچه `V2rayNG/` قرار دارد. تمام ماژول‌ها، وابستگی‌های نیتیو و تغییرات اختصاصی behradNG مستقیماً در این پروژه پیکربندی شده‌اند.

برای ویندوز، [راهنمای ساخت محلی](LOCAL-BUILD.md) و برای مراحل CI، [گردش کار ساخت](.github/workflows/build.yml) را ببینید. دستورهای بخش انگلیسی بدون نیاز به هیچ کپی یا بازنویسی دستی، مستقیماً از پوشه `V2rayNG` قابل اجرا هستند.

برای گزارش مشکل از [Issues](https://github.com/dashbehrad/behradNG/issues) استفاده کنید. کلید امضا، اطلاعات خصوصی پروکسی و فایل APK را commit نکنید.

موتور دور زدن DPI، وکتورهای جهش و مکانیزم‌های تزریق OOB با ذکر منبع و اقتباس از پروژه [UAC-SNI-Spoofer-Android](https://github.com/Floxu1/UAC-SNI-Spoofer-Android) اثر [Floxu1](https://github.com/Floxu1) پیاده‌سازی شده است.

این پروژه تحت مجوز [GNU GPLv3](LICENSE) منتشر می‌شود. مجوزها و اعلان‌های وابستگی‌ها نیز باید رعایت شوند.

</div>
