# Network Proxy

::: tip Version note
Network proxy is available since 1.11.0.
:::

"Settings → Runtime → Network Proxy" configures a global proxy so that all of the app's network requests (AI chat, MCP, model lists, update checks, image downloads, web search) and commands inside the container (`curl`, `git`, `npm`, `pip`, etc.) all go through the proxy.

## Proxy settings

- **Enable proxy**: the master switch; turn it off and all requests go direct again.
- **Type**: tap to choose HTTP or SOCKS5 from a bottom sheet.
  - **HTTP**: forwarded through a CONNECT tunnel; can proxy all http/https traffic. A local Clash at `127.0.0.1:7890` is this kind.
  - **SOCKS5**: TCP-level forwarding (Clash usually on port `7891`). Note that npm and pip inside the container have limited SOCKS5 support.
- **Server address / port**: the proxy address.
- **Authentication** (optional): fill in when the proxy needs a username and password. Local proxies without auth (like Clash) need nothing here.
- **Addresses that bypass the proxy**: comma-separated; matched entries connect directly. Supports hostnames, the wildcard `*`, sub-domains like `*.example.com`, `host:port`, and CIDR ranges. The default already covers localhost and common intranet ranges.

Once fully filled in, a **connectivity test** card appears below:

- **Test URL**: defaults to `https://www.google.com`; change it to any target.
- **Test proxy**: sends one request to that URL through the current proxy to confirm it really works. The test does not reveal the exit IP, nor does it send your local IP to any third-party service.

## Vendor-level proxy

A single vendor can also have its own dedicated proxy. Under "AI Vendors → edit page → Options → Network Proxy", the UI is nearly identical to the global proxy page (without "Addresses that bypass the proxy").

When on, this vendor's AI requests go through the dedicated proxy while everything else still follows the global proxy; when off it follows the global setting. Changes take effect together with the save on the vendor edit page.

Matching is exact on the **host plus path prefix** of the vendor's Base URL, so vendors on the same host but different paths can each have a different proxy, with requests dispatched by the most specific path prefix. Only when multiple vendors share an identical host and path (same endpoint, different keys) can they not be told apart — in that case the first one configured wins.

Handy when several vendors share one gateway domain but sit on different paths and each needs a different exit.

::: tip Commands inside the container ignore vendor-level proxy
Commands in the container always use the global proxy.
:::

## When changes take effect

Every change takes effect **immediately** — new connections use the proxy right away, no app restart needed.

Long-running processes already started inside the container do not inherit the new environment variables — restart that process or the whole container. Proxying inside the container is implemented with environment variables: the HTTP type injects `HTTP_PROXY` / `HTTPS_PROXY` / `ALL_PROXY` / `NO_PROXY`, and the SOCKS5 type injects `ALL_PROXY=socks5h://...` (remote DNS).

## Notes

- Proxy configuration lives in the app's private directory and does not touch system-level proxy settings.
- If the proxy is unavailable (wrong address, or the proxy is not running), network requests fail; turn off "Enable proxy" to restore direct connections.
- **In remote SSH mode, commands on the server do not go through the local proxy**; configure it on the server yourself.
- HTTPS proxies are not supported (i.e. TLS between you and the proxy server itself).
