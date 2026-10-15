# behradNG 2.5.0

## Changes

- Aether can be the device-side entry of an explicit Xray proxy chain: Aether → Xray → Internet, in VPN or proxy-only mode.
- New chains store profile GUIDs, so renaming a profile does not change chain identity. Older remark-based chains remain readable when their member names are unambiguous.
- New installations use the Iran routing preset and Chocolate4U/Iran-v2ray-rules as the default geo provider. CI and Windows builds package matching checksum-pinned geoip/geosite files and license attribution.
- Iran/private destinations bypass the proxy; other traffic uses the selected proxy or chain. Existing custom routing and provider choices are preserved.
- Remove the Iran preset's unconditional UDP/443 block so QUIC is available when the selected route supports it.
- GUID-backed chain startup reads only its member profiles instead of scanning every server for each hop. Preserve transport-aware mux defaults; do not force extra mux over XHTTP, gRPC or HTTP/2.

## Boundaries

One Aether entry per explicit chain is supported. Aether as a later hop, policy-group or routing target, root mode, and nested chains are rejected. The existing Android/ABI/page-size limits still apply. Aether registration requires a working network path to Cloudflare.

OpenRay is a proxy subscription source, not a modified Xray core. It is not automatically subscribed or selected. Native Xray source is unchanged in this release; performance benefits are workload-dependent and have not been benchmarked on Iranian carriers.

## Validation

Validation results and unavailable checks are recorded during release preparation. An assembled APK and on-device VPN/proxy chain checks are required before claiming end-to-end connectivity or a speed improvement.

New-install domestic DNS uses the system resolver for directly routed Iran/private names,
instead of a China-specific public resolver. Proxy DNS remains on the existing encrypted
remote resolver; saved DNS preferences are not overwritten.
