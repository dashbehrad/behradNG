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

### Saturn · Aether & URnetwork chained transport

Saturn establishes a two-hop chained proxy architecture designed for high performance, low latency, and strong privacy:
1. **Hop 1 (Aether / Cloudflare Tunnel):** The client connects first to Aether via Cloudflare Tunnel using WireGuard (`wg`) or MASQUE (`masque`) protocols over HTTP/2 (`h2`) or HTTP/3 (`h3`) carriers with TLS SNI fragmentation.
2. **Hop 2 (URnetwork):** Once Aether is connected, Saturn's built-in proxy chain forwards traffic through the tunnel to the [URnetwork](https://github.com/urnetwork) distributed infrastructure ([ur.io](https://ur.io)).

#### Saturn Configuration Options:
- **Aether Settings:** Select the tunnel protocol (`masque` or `wg`) and HTTP carrier (`h2` or `h3`).
- **URnetwork Settings:**
  - **Quick Account Creation:** Agree to the [URnetwork Terms and Conditions](https://ur.io/terms) with a single tap to provision an account token and traffic allocation.
  - **Account Reset:** Reset your account anytime to generate a fresh identity and fresh traffic quota.
  - **Routing Mode:** Choose between **Web** (balanced multi-hop browsing) and **Streaming** (high-throughput media optimization).
  - **Carrier Selection:** Choose between **HTTP/2 (h2)** and **HTTP/3 (h3)** transport carriers.
  - **Server Locations:** Select from available server locations globally (Automatic, US, Germany, UK, Netherlands, France, Canada, Japan, Singapore, etc.) with a button to update the locations list.
- **DNS & Routing:** Proxy and DNS settings follow your standard behradNG configuration, supporting custom remote/domestic DNS and per-app routing rules.

[Release notes →](RELEASE-3.0.0.md)

## Build from source

The repository has two layers: **`V2rayNG/` contains the complete Android project**, and **`app/` contains behradNG overrides**. The publishing workflow copies the overrides into the Android project before running Gradle. Building the base directory alone does not reproduce the behradNG release.

### Windows

Follow [LOCAL-BUILD.md](LOCAL-BUILD.md) to prepare Java, Go, the Android SDK/NDKs, and local release signing files. From the repository root:

```powershell
# Check prerequisites without downloading or building.
.\build-local.ps1 -CheckOnly

# Prepare native dependencies, apply overrides, test, and build.
.\build-local.ps1
```

The script uses JDK 21 by default and accepts `-JavaPath` for another installation. Release signing requires your own private signing files as described in the build guide.

### Linux / CI

Use the [build workflow](.github/workflows/build.yml) as the reference for the pinned Java, Android SDK, NDK, Gradle, and native build steps:

1. Clone with `git clone --recurse-submodules https://github.com/dashbehrad/behradNG.git`.
2. Build the pinned Xray AAR and HEV libraries following the workflow.
3. Run `prepare-aether.sh`, `prepare-saturn.sh`, and `prepare-iran-rules.sh` with the required toolchains configured.
4. Apply the overlay and build from the prepared project:

```bash
cp -a app/. V2rayNG/app/
cd V2rayNG
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug
```

Before assembling, restore the Gradle wrapper JAR if missing and generate `V2rayNG/debug.keystore` using the workflow's preparation steps. Release builds use `:app:assembleRelease` and require the signing environment variables defined in the workflow. Pull requests build a debug APK; push and manual runs build and publish a release APK.

<details>
<summary><strong>Repository map</strong></summary>

| Path | Purpose |
| :--- | :--- |
| [`V2rayNG/`](V2rayNG/) | Complete Android application and Gradle project |
| [`app/`](app/) | behradNG source, resource, and build overrides |
| [`native/`](native/) | Aether and Saturn native integration |
| [`AndroidLibXrayLite/`](AndroidLibXrayLite/) | Xray native core submodule |
| [`hev-socks5-tunnel/`](hev-socks5-tunnel/) | HEV tunnel submodule |
| [`.github/workflows/build.yml`](.github/workflows/build.yml) | Native preparation, tests, APK checks, and publishing |

</details>

## Contributing

[Report an issue](https://github.com/dashbehrad/behradNG/issues) with reproduction steps, Android version, and relevant logs with private details removed. Keep pull requests focused and include the validation commands you ran. Read [AGENTS.md](AGENTS.md) and any applicable scoped guide before editing.

Keep signing keys, private proxy credentials, generated APKs, and native build caches out of commits.

## Credits & license

Based on [v2rayNG](https://github.com/2dust/v2rayNG), with [Xray-core](https://github.com/XTLS/Xray-core), [HEV](https://github.com/heiher/hev-socks5-tunnel), and [URnetwork](https://github.com/urnetwork) components.

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
- **Saturn (زنجیره پروکسی Aether و URnetwork):** معماری اتصال دو مرحله‌ای سبک و پرسرعت:
  - **مرحله اول (Aether):** اتصال به نفق Cloudflare با پروتکل‌های WireGuard (`wg`) یا MASQUE (`masque`) و حامل‌های `h2` یا `h3` همراه با فرگمنت TLS SNI.
  - **مرحله دوم (URnetwork):** اتصال زنجیره‌ای به شبکه توزیع‌شده [URnetwork](https://github.com/urnetwork) ([ur.io](https://ur.io)) برای تأمین خروجی امن و آزاد.
  - **تنظیمات Aether:** انتخاب پروتکل (`wg` یا `masque`) و حامل (`h2` یا `h3`).
  - **تنظیمات URnetwork:** ساخت سریع حساب با موافقت با [قوانین و شرایط](https://ur.io/terms)، امکان بازنشانی حساب و دریافت ترافیک جدید، انتخاب حالت‌های وب (`Web`) یا استریم (`Streaming`)، انتخاب حامل (`h2` یا `h3`) و انتخاب موقعیت سرور همراه با دکمه بروزرسانی فهرست سرورها.
  - **تنظیمات پروکسی و DNS:** مسیریابی و DNS ساترن مستقیماً بر اساس تنظیمات اصلی کاربر در behradNG اعمال می‌شود.

### ساخت و مشارکت

پروژه کامل اندروید در `V2rayNG/` و تغییرات اختصاصی behradNG در `app/` قرار دارند. برای ساخت نسخه behradNG باید وابستگی‌های بومی آماده و تغییرات `app/` روی پروژه اصلی کپی شوند.

برای ویندوز، [راهنمای ساخت محلی](LOCAL-BUILD.md) و برای مراحل CI، [گردش کار ساخت](.github/workflows/build.yml) را ببینید. دستورهای بخش انگلیسی پس از آماده‌سازی پروژه قابل اجرا هستند.

برای گزارش مشکل از [Issues](https://github.com/dashbehrad/behradNG/issues) استفاده کنید. کلید امضا، اطلاعات خصوصی پروکسی و فایل APK را commit نکنید.

این پروژه تحت مجوز [GNU GPLv3](LICENSE) منتشر می‌شود. مجوزها و اعلان‌های وابستگی‌ها نیز باید رعایت شوند.

</div>
