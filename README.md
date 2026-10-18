# behradNG

A fast, privacy-focused Android proxy client based on v2rayNG and Xray-core.

behradNG keeps the familiar v2rayNG configuration model while adding two project-specific transports, **Aether** and **Saturn**, with recovery behavior for changing Wi-Fi and cellular networks.

## Features

- Import configurations from links, QR codes, files, clipboard, and subscriptions.
- VLESS, VMess, Shadowsocks, Trojan, Hysteria2, WireGuard, SOCKS, and HTTP support.
- VPN, proxy-only, per-app routing, routing rules, proxy chains, and policy groups.
- DNS customization, fragmentation, mux, sniffing, speed tests, and connection diagnostics.
- Existing v2rayNG profiles and application settings remain supported.

## Aether

Aether is behradNG's adaptive transport. It supports configurable carriers, optional fragmentation, scan and transport settings, and managed startup and shutdown through the Android service lifecycle. It integrates with the existing VPN, proxy-only, and routing modes.

When the active network changes, behradNG restarts the Aether transport and routing core in order so connections do not remain attached to the previous Wi-Fi or cellular network.

## Saturn

Saturn is a Tor-backed transport for restricted networks. It uses enhanced DNS-over-HTTPS through a proxy chain and integrates with [SlipNet](https://github.com/anonvector/SlipNet) for managed Tor, obfs4 bridges, bridge fallback, and DNS routing.

Saturn validates bridge paths during startup and recreates its transport after network handovers. It requires packaged native libraries and supports Android 8 or later on the ABIs included by the build configuration.

## Download

Download APK releases from <https://github.com/dashbehrad/behradNG/releases>.

## Build locally

Requirements: JDK 21, Android SDK/NDK versions required by Gradle, Git, and native build tools.

The Android project is under `V2rayNG/`:

```bash
./gradlew :app:assemblePlaystoreDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assemblePlaystoreDebug
```

For a clean native build, follow `LOCAL-BUILD.md` and the Aether/Saturn preparation scripts.

## Project layout

- `V2rayNG/` — Android application source and Gradle project.
- `app/` — behradNG application overlay used by local and CI builds.
- `native/` — Aether and Saturn native launchers.
- `hev-socks5-tunnel/` — HEV tunnel integration.
- `AndroidLibXrayLite/` — Xray native core source.
- `SlipNet/` — local SlipNet checkout used for Saturn development.

## Contributing

Keep pull requests focused, preserve the existing UI and app settings unless required, and include the validation command used. Never commit signing keys, private proxy credentials, generated APKs, or native build caches.

## License and acknowledgements

behradNG is licensed under the [GNU GPLv3](LICENSE). It is based on [v2rayNG](https://github.com/2dust/v2rayNG) and uses Xray-core, HEV tunnel components, and SlipNet.

---

# راهنمای فارسی

## درباره behradNG

behradNG یک کلاینت اندرویدی سریع و متمرکز بر حریم خصوصی برای V2Ray و Xray است که بر پایه v2rayNG توسعه داده شده است. پروژه امکانات اصلی v2rayNG را حفظ می‌کند و دو ترنسپورت اختصاصی Aether و Saturn را اضافه می‌کند.

## امکانات

- وارد کردن کانفیگ از لینک، QR، فایل، کلیپ‌بورد و سابسکریپشن.
- پشتیبانی از VLESS، VMess، Shadowsocks، Trojan، Hysteria2، WireGuard، SOCKS و HTTP.
- حالت VPN، پروکسی، پراکسی زنجیره‌ای، گروه‌های سیاستی و مسیریابی برنامه‌به‌برنامه.
- تنظیمات DNS، Fragment، Mux، Sniffing، تست سرعت و بررسی اتصال.
- حفظ سازگاری با پروفایل‌ها و تنظیمات فعلی behradNG.

## پروتکل Aether

Aether یک ترنسپورت تطبیقی برای شبکه‌های ناپایدار و محدودشده است. این پروتکل از Carrierهای قابل تنظیم، Fragment، اسکن و حالت‌های مختلف انتقال پشتیبانی می‌کند و با چرخه عمر سرویس اندروید مدیریت می‌شود.

هنگام جابه‌جایی بین وای‌فای و اینترنت سیم‌کارت، ترنسپورت Aether و هسته مسیریابی به ترتیب مناسب دوباره راه‌اندازی می‌شوند تا اتصال روی شبکه قبلی باقی نماند.

## پروتکل Saturn

Saturn یک ترنسپورت مبتنی بر Tor است که DNS-over-HTTPS تقویت‌شده را از طریق Proxy Chain عبور می‌دهد. این پروتکل برای مدیریت Tor، پل‌های obfs4، مسیرهای جایگزین و مسیریابی DNS از کتابخانه [SlipNet](https://github.com/anonvector/SlipNet) استفاده می‌کند.

Saturn هنگام شروع مسیر پل را بررسی می‌کند و بعد از تغییر شبکه، ترنسپورت و هسته مسیریابی را دوباره راه‌اندازی می‌کند. Saturn به کتابخانه‌های Native بسته‌بندی‌شده نیاز دارد و در پیکربندی فعلی برای Android 8 و بالاتر ارائه می‌شود.

## دانلود و ساخت

نسخه‌های APK از صفحه Releases در دسترس هستند: <https://github.com/dashbehrad/behradNG/releases>

کد اندروید در پوشه `V2rayNG/` قرار دارد. برای ساخت، JDK 21، Android SDK، NDK و ابزارهای Native را نصب کنید و سپس دستور زیر را اجرا کنید:

```bash
./gradlew :app:assemblePlaystoreDebug
```

در ویندوز PowerShell:

```powershell
.\gradlew.bat :app:assemblePlaystoreDebug
```

برای ساخت تمیز، دستورهای آماده‌سازی Aether و Saturn را طبق `LOCAL-BUILD.md` اجرا کنید.

## مشارکت و مجوز

تغییرات باید متمرکز باشند و بدون نیاز، رابط کاربری و تنظیمات برنامه را تغییر ندهند. کلید امضا، اطلاعات خصوصی پروکسی، APK و کش‌های ساخت را commit نکنید.

این پروژه تحت مجوز [GNU GPLv3](LICENSE) منتشر می‌شود و از v2rayNG، Xray-core، HEV و SlipNet استفاده می‌کند. مجوزها و noticeهای همراه هر وابستگی را رعایت کنید.
