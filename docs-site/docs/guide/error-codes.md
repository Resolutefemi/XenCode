# Common Error Messages

When an AI request fails or the network fluctuates, the chat page first shows a "Retrying N/6" bubble whose first line states the reason for the retry; if the retries are exhausted and it still fails, the error is shown as a red bubble. This page explains what these messages mean by category and how to troubleshoot them.

## Retry Message Categories

When the connection drops mid-request or the server has a temporary problem, XenCode retries automatically (up to 6 times with exponential backoff). Before each retry, the bubble shows the failure reason:

| Bubble message | Trigger | Possible causes and troubleshooting |
| --- | --- | --- |
| Rate limited (429) | Server returned 429 | Requests too frequent, or the plan quota nearly exhausted. Just wait for the automatic retry; if it happens often, check usage in the vendor's console |
| Server overloaded (503) | Server returned 503 | The model service or relay is overloaded—a temporary fault; just wait for the automatic retry |
| Server error (5xx) | Server returned 500 / 502 / 504, etc. | Server-side issue or gateway problem, unrelated to your configuration; retry later |
| Connection timed out | Timed out establishing the connection or waiting for a response | Slow network, unresponsive service, or a slow relay. Check the network; switch networks or nodes if needed |
| Connection refused | Nothing listening on the target port | The vendor's Base URL is wrong (domain / IP or port), or the service isn't running; verify under "Settings → AI Vendors" |
| DNS resolution failed | Domain name resolution failed | Network not connected, or a typo in the custom Base URL's domain |
| Connection interrupted | Connection dropped after being established, or the stream was cut | Network jitter, an unstable proxy, or the server closing the connection. The automatic retry should handle it; if it recurs, check your network and proxy settings |
| SSL handshake failed | TLS certificate validation failed | With a custom Base URL, check the protocol (https) and the certificate; or the server's certificate is broken |
| Network connection lost | Other network-layer failures | Mobile network switch or weak signal; check the network and retry manually |
| Network error | Unclassifiable errors | Read the specific error text; if it can't be resolved, attach logs when reporting |

## Common HTTP Status Codes

When a request fails, the retry bubble and the final error bubble include the HTTP status code. The common codes mean:

| Code | Meaning | Possible cause |
| --- | --- | --- |
| 401 | Authentication failed | API key wrong, expired, or revoked; check the key under "Settings → AI Vendors" |
| 403 | Insufficient permission | The key lacks access to this model or endpoint, a region restriction, or zero balance |
| 404 | Endpoint not found | The custom Base URL path is wrong (e.g. missing `/v1`); check the vendor settings |
| 429 | Rate limited | Requests too frequent or quota exhausted; retry later or upgrade the plan |
| 500 | Internal server error | Temporary server-side fault, unrelated to your configuration |
| 502 | Bad gateway | The relay's upstream is unavailable |
| 503 | Service overloaded | Server under heavy load; retry later |
| 504 | Gateway timeout | The upstream timed out |

## Relation to Multi-Key Switching

With multi-key enabled under "Settings → AI Vendors", only errors that indicate **a problem with the key itself** (authentication failure, insufficient permission, rate limiting, exhausted balance) count toward the failure counter and trigger a key switch; server 5xx, request timeouts, network unreachable, and the like can't be fixed by switching keys, so no false switch happens. With multi-key off, rate limiting (429) has no key to switch to and backs off and retries like any other transient fault. See [AI Vendors & Models → Multi-Key](/guide/providers).
