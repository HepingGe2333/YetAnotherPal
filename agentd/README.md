# agentd — the shared agent core as a standalone JVM service

`agentd` is a small HTTP service that exposes the **shared agent decisions** over the wire.
Since 0.11.0 those decisions live in their own module, `:agentcore`; since 0.12.0 that module is a
Kotlin Multiplatform module and `agentd` consumes its **`jvm()` variant** through the same
`implementation(project(":agentcore"))` dependency (its `installDist`/`fatJar` therefore ship
`agentcore-jvm.jar` / the same classes). The module has **zero third-party runtime dependencies**,
and both JVM clients compile against it:

```
                 ┌─────────────────────────────┐
                 │  :agentcore  (KMP)          │
                 │  com.yap.app.domain         │
                 │  SleepPolicy / DeferReply   │
                 │  ProactiveLevel / Clamp …   │
                 │  AgentCore + InProcess impl │
                 │  AgentCoreProtocol / Codec  │
                 │  commonMain + jvmMain       │
                 └──────────┬──────────────────┘
                            │ implementation(project(":agentcore"))
             ┌──────────────┴───────────────┐
             │                              │
   ┌───────────────────┐          ┌───────────────────┐
   │       :app        │          │      :agentd      │
   │ Android client    │          │ JVM HTTP service  │
   │ InProcess default │          │ validates request │
   │ Remote optional   │◄─HTTP────►│ → InProcessCore   │
   └───────────────────┘ /v1/core  └───────────────────┘
```

- `:app` never depends on `:agentd` — the Android build is unaffected by the service. It depends
  on `:agentcore` and calls it **in-process by default**.
- `:agentd` never re-implements a rule: its handlers validate the HTTP request and then call
  `InProcessAgentCore` (the same object Android uses in-process).
- iOS now links the same Kotlin implementation as a static `AgentCoreKit.framework` built from
  `:agentcore`'s iOS targets (`ios/scripts/build-agentcore-framework.sh`). The older Swift mirror
  (`NativeAgentCoreClient`) is **compiled only in the macOS test build** as of 0.12.0 — the iOS app
  runtime has no second, executable decision implementation; both sides still read the same
  `spec/agent-core/cases/*.json` contract files.

The HTTP layer is a ~250-line minimal HTTP/1.1 loop built on `ServerSocket` (one request per
connection, `Connection: close`; no keep-alive, no chunked, no TLS). It replaced the JDK's
`com.sun.net.httpserver` in 0.11.1 because that API has no read timeout, its request
`InputStream.available()` is unreliable, and closing a response drains the unread request body in a
blocking read that `exchange.close()` cannot interrupt — which made the audit's P1-1 half-open
denial of service impossible to fix reliably. JSON is parsed/serialized by the recursive-descent
parser in `:agentcore` (`com/yap/app/core/json/Json.kt`). There is no web framework, no JSON
library, no DI container, no logging framework, no coroutines.

Runtime classpath of the distribution is exactly `agentd.jar` + `agentcore.jar` + Kotlin stdlib
(+ the stdlib's `annotations` transitive).

Version: `0.11.1`. Included in the root build as `:agentd`, deliberately **not** a dependency
of `:app`.

## Run it

From the repository root:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home

./gradlew :agentd:run --args="--port 8788"
```

Or build a runnable distribution:

```bash
./gradlew :agentd:installDist
agentd/build/install/agentd/bin/agentd --port 8788
```

Or build the self-contained jar (Kotlin stdlib + agentcore bundled, `Main-Class` set):

```bash
./gradlew :agentd:fatJar
java -jar agentd/build/libs/agentd-all.jar --port 8788
```

Options:

| Flag | Default | Meaning |
| --- | --- | --- |
| `--host` | `127.0.0.1` | Bind address. `0.0.0.0` means **anyone on the network can call it** — there is no TLS and (unless `--token` is set) no auth. Only use it on a trusted LAN or behind your own auth/proxy. |
| `--port` | `8788` | Listen port. |
| `--data <dir>` | `./agentd-data` | Server-authoritative state directory (see [Server-authoritative mode](#server-authoritative-mode-服务端权威模式-0120)). A missing/empty directory is seeded with 3 personas + 1 session; an existing one is read back as-is. Also `YAP_AGENTD_DATA`. |
| `--token <secret>` | — | Optional shared token. When set, **every** request (including `/health`) must send `Authorization: Bearer <secret>`; anything else gets `401`. Can also be supplied as the `YAP_AGENTD_TOKEN` environment variable so it does not show up in `ps`. |
| `--rate-limit <n>` | `60` | Per-source requests per minute. `0` turns rate limiting **off**. Also `YAP_AGENTD_RATE_LIMIT`. |
| `--rate-burst <n>` | `30` | Token-bucket capacity (how many idle requests may burst). Must be ≥ 1. Also `YAP_AGENTD_RATE_BURST`. |
| `--llm-base-url <url>` | — | OpenAI-compatible root, e.g. `https://api.deepseek.com/v1`. **Remote** deployments (`--host` is not loopback) read *only* these server-side values; **loopback** deployments prefer the client-pushed `api_configs.json` + `secrets.json` and fall back to this. Also `YAP_LLM_BASE_URL`. |
| `--llm-key <key>` | — | API key for the server-side config (optional: local gateways often need none). Also `YAP_LLM_KEY`. Never logged, never part of a response. |
| `--llm-model <name>` | — | Model name, e.g. `deepseek-chat`. Required when a server-side config is supplied. Also `YAP_LLM_MODEL`. |
| `--ffmpeg <path>` | — (PATH lookup) | System `ffmpeg` used by the `watch` tool to extract video keyframes. Resolution order: flag → `YAP_FFMPEG` → `ffmpeg` on `PATH`. When nothing is found, `watch` answers **“服务器没有视频解码器，看不了视频”** instead of faking frames. |
| `--help` / `-h` | — | Usage. |

Flags win over environment variables, which win over the defaults (so a container can pin the
values via env and still override one of them for a one-off test).

Smoke test:

```bash
curl -s http://127.0.0.1:8788/health
# {"ok":true,"service":"yap-agentd","version":"0.11.1"}

# only when --token is set:
curl -s -H "Authorization: Bearer $YAP_AGENTD_TOKEN" http://127.0.0.1:8788/health
```

## Limits, timeouts and auth (0.11.1)

`agentd` is an embedded service, not a public API; 0.11.1 adds the minimum hardening needed so
one client cannot take it down or make it amplify traffic. All of it is covered by tests in
`AgentdApiTest` / `AgentdServerTest`.

| Limit | Value | Behaviour |
| --- | --- | --- |
| Request body | 1 MiB JSON routes (`AgentdApi.MAX_BODY_BYTES`); 4 MiB `POST /v1/media` (`MediaRoutes.MAX_REQUEST_BODY_BYTES`); 8 MiB `POST /v1/sessions/{id}/messages/import` (`CrudRoutes.MAX_IMPORT_BODY_BYTES`); 256 MiB `POST /v1/backup` (`AgentdServer.MAX_BACKUP_BODY_BYTES`) | `Content-Length` over the route cap → `413 content_too_large`, body is not read into memory. The media route still rejects files over 2 MiB; the backup route is the only one whose body is streamed to disk instead of into a `ByteArray`. |
| Read timeout | 5 s (`AgentdServer.READ_TIMEOUT_MS`); `POST /v1/backup` gets 300 s for its body (`BACKUP_IMPORT_READ_TIMEOUT_SECONDS`, 5 s per socket read) | A body that stalls mid-transfer → `408 request_timeout`, connection closed, worker released |
| Body framing | `Content-Length` only | `Transfer-Encoding: chunked` → `411 length_required` (both clients send `Content-Length`; a blocking chunked parse would reopen the slow-client hole) |
| Workers | 4 fixed threads + a bounded 64-connection queue | Slow clients can occupy at most the pool; `/health` keeps responding on the remaining threads, and a full queue closes new connections instead of growing memory |
| Error bodies | message ≤ 512 chars | Client input is never echoed (unknown tool / long path / invalid JSON are answered with fixed text); `500` is a fixed message + random `errorId`, details only go to stderr |
| Auth | optional `--token` | Missing/wrong `Authorization: Bearer …` → `401 unauthorized` (constant-time compare) |
| Rate limit | 60 req/min per source, burst 30 (`RateLimiter`) | Over budget → `429 rate_limited` + `Retry-After: <seconds>`; `/health` has its own bucket; `--rate-limit 0` disables it (see [Rate limiting](#rate-limiting-0120)) |

The timeout is enforced as a **total per-request deadline** (request line + headers + body), not a
per-read timeout: a client that sends one byte every few seconds is cut off at 5 s as well. After
the response is flushed the server half-closes the socket, briefly drains whatever the client is
still sending (50 ms / 64 KiB) so a `413`/`401` answer is not lost to a TCP RST, then closes the
connection. All of this lives in `AgentdServer` and is covered by `AgentdServerTest`.

`/health` intentionally keeps reporting `service` and `version`: the Android/iOS remote clients use
it to tell "agentd is up" from "wrong service on this port". It exposes nothing beyond the service
name and the build version (the audit accepted this; see 0.11.1 P2-15). When `--token` is set,
`/health` requires the token too.

**Known limitation:** the token is a shared secret over plain HTTP. It stops random LAN clients
and accidental exposure, not an active network attacker; for that you still need TLS/reverse proxy
(see [Deployment and TLS](#deployment-and-tls-0120)). The Android/iOS remote clients do not send
`Authorization` yet, so keep `--token` off until the clients are updated — or let the reverse proxy
inject the `Authorization: Bearer …` header (the proxy holds the token, clients only speak HTTPS).

## Rate limiting (0.12.0)

Every source (the request's TCP peer address, as a string) gets its own **token bucket**:

- refill rate `--rate-limit` per minute (default **60**, i.e. one token per second);
- capacity `--rate-burst` (default **30**): a client that has been idle may burst up to 30
  requests, then it is throttled to the refill rate;
- each request takes one token. When the bucket is empty the server answers
  `429 rate_limited` with a `Retry-After: <seconds>` header (always ≥ 1) **before** reading the
  request body, so an over-quota client cannot make the service read the body or parse JSON;
- the limiter runs **before authentication**: an over-quota client gets `429` even with a wrong
  token (it saves the constant-time compare, and leaves no `401` vs `200` difference to probe);
- `--rate-limit 0` / `YAP_AGENTD_RATE_LIMIT=0` disables it completely (no buckets are kept).

`GET /health` is rate limited **in its own bucket**. The trade-off:

- sharing one bucket would let business traffic (or — behind a reverse proxy — *everyone*, because
  all requests then come from the proxy's IP) exhaust the quota and turn health probes into `429`,
  which looks like "the service is down" to an orchestrator;
- a separate bucket keeps probes meaningful while still bounding `/health` (it is not a free
  bypass), at the cost of one extra counter per source.

Implementation notes (`agentd/src/main/kotlin/com/yap/agentd/RateLimiter.kt`):

- state per source is `(tokens, lastRefillNanos)` in a `LinkedHashMap(accessOrder = true)`; nothing
  runs in the background — a bucket refills lazily when that source next appears;
- the map is **bounded** (`DEFAULT_MAX_TRACKED_CLIENTS = 4096`). On overflow it first drops buckets
  that have already refilled to full (dropping those is equivalent to keeping them), then evicts
  the least-recently-used source. Scanning many spoofed sources therefore cannot grow memory;
- the limiter is checked per connection on the worker thread; a throttled client only gets an
  immediate `429`, so it never blocks other sources and never holds a worker for long;
- the clock is injectable (`clock: () -> Long`), so the unit tests never sleep.

**Proxy caveat:** this is *per TCP peer*, not per real user. Behind Caddy/Nginx/Traefik every
request arrives from `127.0.0.1`, so all users share one bucket — either raise `--rate-limit`
accordingly or do the per-user limiting at the proxy and treat `agentd`'s limiter as a
backstop. `X-Forwarded-For` is deliberately **not** trusted (it is trivially spoofable unless you
validate the proxy's address); if you need per-client limits behind a proxy, do it there.

## Runtime dependencies: ffmpeg and CJK fonts (0.14.1)

Remote deployments need two host-level dependencies; **local client mode never needs them**
(Android uses `MediaMetadataRetriever` + system fonts, iOS uses `AVAssetImageGenerator` + CoreText):

- **`ffmpeg`** for the `watch` tool (video → ≤8 JPEG keyframes). Resolution: `--ffmpeg` →
  `YAP_FFMPEG` → `PATH`. Without it `watch` answers “服务器没有视频解码器，看不了视频” and never
  fabricates frames. `brew install ffmpeg` / `apt-get install ffmpeg` / `apk add ffmpeg`.
- **A CJK font on the JRE** for `sticker.make` text overlay (JDK logical `SansSerif`). macOS/Windows
  usually ship one; minimal Linux images need `fonts-noto-cjk` / `font-noto-cjk`, otherwise Chinese
  renders as `□□□`. Verify with `fc-list :lang=zh` (or `Font.canDisplay('中')`), then restart agentd.

Full deployment guide (Dockerfile, verification, checklist): [docs/DEPLOY.md](../docs/DEPLOY.md).

## Deployment and TLS (0.12.0)

> **The service itself speaks plain HTTP only. Never expose it directly to the public internet.**
>
> There is no built-in TLS and no plan to add self-signed certificates: a hand-rolled TLS
> listener would mean owning certificate loading, ALPN/HTTP/2, cipher configuration and renewal —
> all of which a reverse proxy already does correctly. `agentd` stays a loopback-bound plaintext
> service; TLS terminates at the proxy.

Recommended shape:

```
App (HTTPS) ──► Caddy / Nginx / Traefik  (TLS, HTTP/2, edge limits)
                                   │  plain HTTP to 127.0.0.1
                                   ▼
                            agentd --host 127.0.0.1 --port 8788
```

Rules that matter:

1. **Bind to loopback.** Keep the default `--host 127.0.0.1` (or bind the proxy and `agentd` to
   distinct loopback addresses). `--host 0.0.0.0` is only for a trusted LAN / container network,
   and even then put auth in front.
2. **Terminate TLS in the proxy**, not in `agentd`. Get certificates from your proxy's ACME
   support (Caddy automatic HTTPS, certbot for Nginx, Traefik's resolver).
3. **Keep the token.** With a proxy in front, `--token` (or `YAP_AGENTD_TOKEN`) is still
   useful as a second factor, and you can keep it out of the client entirely: let the proxy add
   `Authorization: Bearer …` on the upstream request. Do not put the token in a URL or in the
   repository.
4. **Keep request buffering on** (Nginx default) so `agentd` receives `Content-Length`; with
   `proxy_request_buffering off` a chunked request from the client is forwarded as chunked and
   `agentd` answers `411 length_required` by design.
5. **Limit at both layers.** Keep `agentd`'s per-source bucket (it is the only thing that survives
   a proxy misconfiguration) and add an edge limit keyed by real client IP at the proxy.
6. **Log and monitor 429/5xx**, and expose only the four `/v1/core/*` routes plus `/health`.

Minimal Nginx (TLS + HTTP/2 + edge rate limit):

```nginx
# http {} block:
limit_req_zone $binary_remote_addr zone=agentd:10m rate=60r/m;

server {
    listen 443 ssl;
    http2 on;                       # nginx ≥ 1.25.1; older: `listen 443 ssl http2;`
    server_name agent.example.com;

    ssl_certificate     /etc/letsencrypt/live/agent.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/agent.example.com/privkey.pem;

    client_max_body_size 5m;        # 媒体上传 4 MiB；备份导入要单独放到 256m（或走本机/内网直连）
                                    # agentd 自己会按路由拒绝超限 body（媒体 4 MiB / 消息导入 8 MiB / 备份 256 MiB / 其它 1 MiB）
    add_header Strict-Transport-Security "max-age=31536000" always;

    location / {
        limit_req zone=agentd burst=20 nodelay;

        proxy_pass http://127.0.0.1:8788;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        # 若启用了 --token，由代理注入（客户端不需要知道 token）：
        # proxy_set_header Authorization "Bearer <token>";
        proxy_request_buffering on;  # 上游必须收到 Content-Length（agentd 拒绝 chunked）
        proxy_read_timeout 10s;
    }
}
```

Minimal Caddy (automatic TLS, HTTP/2 and HTTP/3):

```caddyfile
agent.example.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:8788 {
        header_up Host {host}
        # 若启用了 --token，由 Caddy 注入；客户端只走 HTTPS：
        # header_up Authorization "Bearer <token>"
    }
}
```

Caddy's core has no rate limiter; either keep `agentd`'s bucket (default 60/min) or use a
rate-limit plugin at the edge. Traefik: use `reverseProxy` + `rateLimit` middleware the same way.

### Configure the clients against the proxy

Both mobile clients build the remote base URL from a build switch / environment variable, and the
release builds only allow **HTTPS** (cleartext is limited to debug builds / local development):

- **Android** (build switch; release forces in-process, so this is a debug/QA path):

  ```bash
  ./gradlew :app:assembleDebug     -PagentCoreRemote=true     -PagentCoreBaseUrl=https://agent.example.com
  ```

  `http://10.0.2.2:8788` still works for the emulator against a local, plaintext `agentd`;
  anything non-loopback should be `https://…` because the Android release manifest forbids
  cleartext. The path is appended to the base URL (`/v1/core/...`), so the proxy must forward
  those paths untouched (the examples above do).

- **iOS** (same `AgentCoreClient` layer, configured at launch):

  ```bash
  SIMCTL_CHILD_YAP_AGENT_CORE=remote \
  SIMCTL_CHILD_YAP_AGENT_CORE_URL=https://agent.example.com \
  xcrun simctl launch booted com.yap.app.ios
  ```

  The release Info.plist only keeps `NSAllowsLocalNetworking`; arbitrary cleartext is a
  debug-build-only thing (`Config/Info-Debug.plist`). Use an `https://` base URL in any build
  that leaves the device.

**Token vs. clients:** neither remote client sends `Authorization` today, so a token-enabled
`agentd` answers `401` to them. Either (a) keep `--token` off and rely on proxy auth (mTLS,
OAuth2-proxy, IP allowlist) — acceptable only because the service is not publicly reachable — or
(b) enable the token and have the proxy inject the header as shown above. Do not disable the token
"because it is only HTTPS": the proxy is exactly the place where an injected header keeps the
secret out of the clients while still authenticating them.

## Point the Android client at it

The Android app can use this service instead of computing the four decisions in-process.
It is a **build switch**, not a user-visible setting (release builds stay in-process):

```bash
# default: in-process, no network involved
./gradlew :app:assembleDebug

# remote mode: sleep / defer / proactive-cap / tool-args POST to agentd
./gradlew :app:assembleDebug \
  -PagentCoreRemote=true \
  -PagentCoreBaseUrl=http://10.0.2.2:8788
```

- `10.0.2.2` is the host machine as seen from the Android emulator. For a physical device use
  the LAN IP and start the service with `--host 0.0.0.0`.
- Cleartext HTTP is allowed only in debug builds (`app/src/debug/AndroidManifest.xml`); a release
  build talks HTTPS or falls back to in-process.
- The switch becomes `BuildConfig.AGENT_CORE_REMOTE`; the URL becomes
  `BuildConfig.AGENT_CORE_BASE_URL` (`RemoteAgentCoreClient`).
- Every remote call has a short timeout (1.5 s by default). **Any** failure — connection refused,
  timeout, 4xx/5xx, malformed JSON, missing field — is logged under the `YapAgentCore`
  logcat tag and answered by the in-process implementation instead, so chat never hangs or breaks
  because the service is down.
- When the switch is off, `AgentCoreClients.create` returns the in-process client and no HTTP
  client is even constructed (there is a unit test asserting zero requests in that case).

Optional end-to-end check against this service (runs the real `RemoteAgentCoreClient` from
`:app`, no fallback allowed):

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.yap.app.core.AgentCoreRemoteE2ETest" \
  -PagentCoreE2eUrl=http://127.0.0.1:8788 --rerun
```

## Endpoints

All request bodies are JSON objects. All responses are JSON with
`Content-Type: application/json; charset=utf-8`.

Path and field names are not duplicated in the service: both sides read them from
`AgentCoreProtocol` in `:agentcore`, and `AgentdApiTest` asserts that the route table equals
`AgentCoreProtocol.CORE_PATHS`.

### `GET /health`

```json
{"ok":true,"service":"yap-agentd","version":"0.11.1"}
```

The version is exposed on purpose (clients and humans use it to verify which build is answering);
it is the only "internal" detail any response carries. When `--token` is set this endpoint needs
the token as well.

### `POST /v1/core/sleep-decision`

Wraps `SleepPolicy.evaluate` (via `InProcessAgentCore.sleepDecision`; Swift `SleepGate.evaluate`).

```json
{
  "sleepEnabled": true,
  "sleepMinutes": null,
  "wakeMinutes": null,
  "nowMinutes": 120,
  "dndOnSleep": false,
  "userMessagesInWindow": 6,
  "wakeThreshold": 6
}
```

```json
{"wake":true,"moodRelaxed":true,"reason":"被你叫醒了，缓了缓","marksRead":true}
```

- `sleepMinutes` / `wakeMinutes`: minutes after midnight, nullable (null = 23:30 / 08:00).
  Values are wrapped mod 1440; a sleep/wake pair on the same minute falls back to the default
  window (same rule as Android).
- `nowMinutes`: minutes after midnight; wrapped mod 1440.
- `wake` = "this round he will reply" (`Decision.shouldReply`); `moodRelaxed` = he was woken and
  his mood is pulled back (`Decision.woken`).
- `marksRead`: whether the user message gets a read receipt. **Added in 0.11.0** (compatible,
  additive) because remote mode would otherwise silently lose the "do not disturb leaves no read
  receipt" rule (spec §6.2). Older clients ignore it; when it is missing, clients default to
  `true`.
- `reason` is a stable Chinese sentence for clients that want to log/display it:
  `"醒着，会正常回复"`, `"免打扰中，不会醒"`, `"被你叫醒了，缓了缓"`,
  `"睡着了 · 08:00 醒"` (wake clock derived from the persona's schedule).

### `POST /v1/core/defer-plan`

Wraps `DeferReply.plan` (Kotlin / Swift `DeferReply.plan`).

```json
{"minutes": 90, "reason": "现在不想说话", "now": 1700000000000}
```

```json
{"earliestAt":1700005400000,"latestAt":1700007200000,"minutes":90,"reason":"现在不想说话"}
```

- `minutes` is optional (default 45) and clamped to 5–720.
- `reason` is optional, trimmed and truncated to 80 characters.
- `now` is **required** and explicit (milliseconds) so the call is deterministic; this service
  does not read the wall clock for pure functions.
- `earliestAt` / `latestAt` are epoch milliseconds: `now + minutes*60_000`, and the decision
  window is 30 minutes wide.

### `POST /v1/core/proactive-cap`

Wraps `ProactiveLevel.dailyCap` and, when a mood is given, `MoodBehavior.effectiveDailyCap`
(via `InProcessAgentCore.proactiveCap`).

```json
{"level": 3, "moodLabel": "雀跃", "moodIntensity": 4}
```

```json
{"dailyCap":4}
```

- `level` is clamped to 1–5.
- Mood needs **both** `moodLabel` and `moodIntensity` to move the cap, because the domain model
  decides "raise / lower / no change" from the label's valence and energy — an intensity alone
  carries no direction. If only `moodIntensity` is sent, the base cap is returned.
- `moodIntensity` defaults to 2 when a label is present.

### `POST /v1/core/tool-args`

Wraps `ToolArgClamp` for the numeric boundaries the two clients must share
(via `InProcessAgentCore.clampToolArgs`).

```json
{"tool": "remember", "args": {"content": "她妹妹叫小雨", "drawer": "USER", "importance": 9}}
```

```json
{"args":{"content":"她妹妹叫小雨","drawer":"USER","importance":5}}
```

Supported tools and clamps:

| `tool` | Numeric field | Range | Default when missing |
| --- | --- | --- | --- |
| `remember` | `importance` | 1–5 | 3 |
| `web_search` | `count` | 1–12 | 6 |
| `defer_reply` | `minutes` | 5–720 | 45 |

All other keys in `args` are preserved untouched (including their original JSON number spelling).
Unknown tools return 400 `unknown_tool`.

## Server-authoritative mode (服务端权威模式, 0.12.0)

This is the server side of `docs/design/server-authoritative.md` phase P1 (+ the server half of P2):
state, block-paged history, streaming chat and the incremental event stream all live in `agentd`.
Clients keep only UI and caches. The data format is plain JSON/JSONL under a data directory —
**deliberately no SQLite/JDBC** (the module has zero third-party dependencies; see
"Why this shape"). The trade-offs of that choice are listed under [Limits and risks](#limits-and-risks-of-this-phase).

### Data directory

```bash
agentd --data /var/lib/yap            # or: YAP_AGENTD_DATA=/var/lib/yap agentd
```

Default is `./agentd-data` (relative to the process working directory). The flag wins over the
environment variable, which wins over the default — same precedence as every other option.

```
<data>/
  meta.json                      {"version":1,"cursor":<long>,"seeded":<bool>}   ← global event cursor
  personas.json                  {"personas":[…]}                                ← the 3 seeds on first start
  sessions.json                  {"sessions":[…]}                                ← lastMessageAt / unread / lastBlockIndex
  moods.json  presence.json      {"moods":[…]} / {"presence":[…]}                ← mood.changed / presence.changed sources
  usage.json                     {"usage":{personaId:{promptTokens,completionTokens,replies}}}
  settings.json                  {"settings":{…}}                                ← whitelist only; never API keys
  api_configs.json               {"configs":[…]}                                 ← metadata only, **no keys**
  secrets.json                   {"keys":{…}}                                    ← 0600, server-side only, never in a response
  memories.json                  {"memories":[{id,personaId,content,importance,tags,createdAt,updatedAt}]}
  presets.json                   {"presets":[{id,name,…,worldJson,speechStyleJson}]}
  push_tokens.json               {"tokens":[{platform,token,bundleId,createdAt,updatedAt}]}
  events.jsonl                   one event per line, append-only                 ← GET /v1/events cursor
  message_queue.json             0.14.1: pending deliveries ({deliveries:[…]}), survives a restart
  client_extras.json             0.14.1: client-only backup sections kept verbatim (proactiveRules / memoryConsolidations / unknown)
  sessions/<sessionId>/block_index.json   {"sessionId":…,"blocks":[{index,firstMessageId,lastMessageId,firstAt,lastAt,count}]}
  sessions/<sessionId>/blocks/<blockIndex>.jsonl   one message per line (append-only, UTF-8)
  media.json                     {"media":[{id,kind,personaId,mime,bytes,sha256,createdAt}]}
  media/<id>.<ext>               raw image bytes (jpg/png/webp; extension comes from mime)
  .staging/                      transient: streamed backup upload + media decode targets (wiped on start)
```

`memories.json`, `presets.json` and `push_tokens.json` are optional for old data directories
(missing = empty). `GET /v1/settings` and `GET /v1/backup` filter `settings.json` through a
whitelist, so even a hand-edited `apiKey` key never appears in a response or a backup. The
whitelist is: `themeMode`, `accentColor`, `fontScale`, `bubbleStyle`, `animationsEnabled`,
`hapticsEnabled`, `chatBackgroundColor`, `agentLoopEnabled`, `activePersonaId`,
`autoTranslatePersona`, `userAvatarUri`.

`userAvatarUri` holds the signed-in user's own avatar as a media reference (`"/v1/media/<id>"`, or
`null` to clear it) and travels with backups. The older alias `myAvatarUri` is still **accepted on
write** but normalised: `settings.json`, `GET /v1/settings` and backups only ever contain
`userAvatarUri` (see `FileStore.SETTINGS_ALIASES`).

**Seeding.** A missing/empty directory is seeded with three personas and one empty session, so the
`curl` examples below work immediately. `:agentd` has no Android resources, so the ids are fixed
strings the server defines itself; the names match the Android seeds in `app/src/main/res/values/seed.xml`
(`values-en/seed.xml`) so the two sides can be compared:

| persona id | `name` | `nameEn` | default | seed mood | seed presence |
| --- | --- | --- | --- | --- | --- |
| `seed-linshen` | 林深 | Elias | yes | 平静 (2) | `IDLE` |
| `seed-alan` | 阿岚 | Finn | no | — | — |
| `seed-mo` | 墨 | Mo | no | — | — |

The seeded session is `seed-session-linshen` ("和林深的对话"). Seeding runs **once**: a directory
with data is read back as-is (`seeded` stays in `meta.json`). A directory that has data files but no
`meta.json` is refused instead of silently re-seeded, and a `meta.json` with an unknown `version`
fails startup with a readable message.

**Atomic writes.** Every whole-file JSON write goes to `<name>.json.tmp` first and is then moved over
the target with `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` (falling back to a plain replace on file
systems without atomic rename). Block files are append-only for new messages; delete/mark-read rewrites
one block file atomically. All mutations share a single `ReentrantLock`, so readers never observe a
half-written block index, and two requests cannot append the same block concurrently. There is **no
fsync**: a killed process keeps the data (rename is atomic), a power cut may lose the last few writes.
`*.tmp` files are never left behind by a completed write.

**Block maintenance** (same contract as `spec/messages-blocks/README.md`, `BLOCK_SIZE = 200`):
append fills the newest block; a full block starts `blockIndex + 1`; deleting a message recomputes
`first*/last*/count` of its block; deleting the last message of a block deletes the block's `.jsonl`
and removes the row from `block_index.json`, leaving a **numbering hole**. Blocks are never
rebalanced and never renumbered. Paging always asks the block table for the neighbouring existing
index, so holes are skipped. A missing `block_index.json` is rebuilt from the block files on first
access (manual recovery for the crash window described below).

### API

All endpoints sit behind the same `--token` / rate-limit / error-body rules as `/v1/core/*`.
The request-body cap is **1 MiB for JSON routes**, **4 MiB for `POST /v1/media`** (whose file limit
is still 2 MiB), **8 MiB for `POST /v1/sessions/{id}/messages/import`** and **256 MiB for
`POST /v1/backup`** (streamed to a staging file, never held as one `ByteArray`).

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/v1/state?since=&personaId=` | Snapshot. `since` filters personas/sessions by `updatedAt > since` and messages by `createdAt > since`. `serverCursor` is the current global event cursor. |
| `GET` | `/v1/sessions?personaId=` | Session list with `lastMessageAt`, `unread`, `lastBlockIndex` (plus `messageCount`/timestamps). |
| `POST` | `/v1/sessions` | `{personaId,title?}`. One session per persona; if it already exists the existing row is returned (`created:false`). |
| `POST` | `/v1/sessions/{id}/read` | `{upTo?}` → mark assistant messages read, `unread:0`, returns `{unread,readAt,marked,session}`. |
| `GET` | `/v1/sessions/{id}/messages?beforeBlock=&limitBlocks=1` | One block per page. No offset. `beforeBlock` = older page, `afterBlock` = newer page (additive extension for `hasMoreNewer` navigation). |
| `POST` | `/v1/sessions/{id}/messages` | Send a message; responds with SSE (`accepted → delta* → tool* → done`). `attachments` are media ids or `/v1/media/<id>` and are stored/returned as URLs. |
| `POST` | `/v1/sessions/{id}/messages/import` | **0.12.1.** Batch-import client-side history (`{"messages":[…]}`; ≤200 per batch, ≤8 MiB body). Idempotent by message id, atomic per batch, never touches `unread` and never calls the LLM. See “Batch history import” below. |
| `POST` | `/v1/media?kind=&mime=&personaId=` | Raw image bytes (`Content-Type: image/jpeg`/`png`/`webp`), single file ≤ 2 MiB. `201` new, `200` when the same sha256 already exists (dedup). |
| `GET` | `/v1/media/{id}` | Raw bytes + `Content-Type` + `Cache-Control: private, max-age=31536000, immutable` + `ETag`. `If-None-Match` → `304`. |
| `DELETE` | `/v1/media/{id}?force=1` | `409 media_in_use` + `references` while a message attachment / persona `avatarUrl` / preset `avatarUri` points at it; `force=1` deletes anyway (historical references then 404). |
| `GET` | `/v1/personas` | Persona list, including client fields (`worldJson`, `speechStyleJson`, `traits`, `translationJson`, …) and parsed `activity`. |
| `POST` | `/v1/personas` | Create (no `id`) or update (`id` must exist). Known core fields are typed; every other field is kept verbatim. |
| `DELETE` | `/v1/personas/{id}` | Delete persona + its sessions/messages/memories/activity/proactive counters/mood/presence. Media files are **not** deleted. |
| `POST` | `/v1/personas/{id}/activity` | Report/clear the current activity (M3 client integration). |
| `GET` | `/v1/personas/{id}/memories?limit=&since=` | Memories for a persona, newest `updatedAt` first. |
| `POST` | `/v1/personas/{id}/memories` | Create (no `id`) or update (`id` must exist and belong to this persona). |
| `DELETE` | `/v1/memories/{id}` | Delete one memory (404 when absent). |
| `GET` | `/v1/presets` | Persona presets including `worldJson` / `speechStyleJson`. |
| `POST` | `/v1/presets` | Create/update a preset; unknown fields are kept verbatim. |
| `DELETE` | `/v1/presets/{id}` | Delete a preset (404 when absent). |
| `GET` | `/v1/settings` | Whitelisted global settings (no API keys/secrets, ever). |
| `POST` | `/v1/settings` | Merge whitelisted fields; unknown keys (including `apiKey`) are ignored. |
| `GET` | `/v1/backup` | Export a **v6** backup JSON, client-shaped (personas/sessions/messages/memories/presets/settings/apiConfigs/media/stickers/diaryNotes/agentIntents, **no secrets**). **Streamed** (`Transfer-Encoding: chunked`, no `Content-Length`) — the whole backup is never built in memory. |
| `POST` | `/v1/backup` | `{mode:"merge"|"replace",data:{…}}` or a raw client v6 file (`?mode=replace`); validate everything first, then write. The request body is streamed to a temp file and parsed incrementally (body cap 256 MiB). |
| `GET` | `/v1/stickers?personaId=&limit=` | Sticker list (`{serverTime,stickers[]}`); `file`/`fileName` are the bare name, `mediaId`/`url` fetch the bytes. |
| `POST` | `/v1/stickers` | `{mime,data(base64),personaId?,width,height,frames,source,createdBy}` or `{mediaId}` (already uploaded `kind=STICKER`). |
| `DELETE` | `/v1/stickers/{id}` | Delete the row and its file; unknown id ⇒ 404. |
| `GET` | `/v1/personas/{id}/diary?limit=&since=` | Diary notes (`{personaId,notes[]}`), newest first. |
| `POST` | `/v1/personas/{id}/diary` | Create (no `id`) or update; `remindAt` / `remindInMinutes` (1–43200) or `remindAt:null` to clear. |
| `DELETE` | `/v1/personas/{id}/diary/{noteId}` | Hard delete; wrong persona/unknown id ⇒ 404. |
| `POST` | `/v1/push/register` | `{platform:"ios"|"android",token,bundleId?}`; idempotent per `platform+token`. |
| `DELETE` | `/v1/push/register` | Unregister by body `{token,platform?}` or query `?token=&platform=`; idempotent. |
| `GET` | `/v1/events?since=&timeoutSeconds=&limit=` | Incremental events (`message.created`, `mood.changed`, `presence.changed`). Long poll, 25 s max by default. |
| `POST` | `/v1/tools/web-search` | Server-side search; the search key never leaves the server (see below). |
| `POST` | `/v1/tools/web-fetch` | Server-side fetch of a public page, body text extracted. |
| `POST` | `/v1/proactive/tick` | Run one proactive heartbeat now (debug/testing; production is timer-driven). |

```bash
# 1. full snapshot (first sync: newest block per session + cursor for /v1/events)
curl -s "http://127.0.0.1:8788/v1/state"
# 2. incremental sync: only what changed after the last cursor/timestamp
curl -s "http://127.0.0.1:8788/v1/state?since=1700000000000&personaId=seed-linshen"
# 3. sessions
curl -s "http://127.0.0.1:8788/v1/sessions"
# 4. one block page (older: beforeBlock; newer: afterBlock)
curl -s "http://127.0.0.1:8788/v1/sessions/seed-session-linshen/messages?beforeBlock=1&limitBlocks=1"
# 5. send a message and read the stream (curl -N disables buffering)
curl -N -s -X POST "http://127.0.0.1:8788/v1/sessions/seed-session-linshen/messages" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"clientMessageId":"0b1c2d3e-0000-4000-8000-000000000001","content":"在吗"}'
# 6. long poll for events (wakes up as soon as something happens, max 25 s)
curl -s "http://127.0.0.1:8788/v1/events?since=0&timeoutSeconds=25"
# 7. server-side search (provider/base/key come from the server environment, not from this body)
curl -s -X POST "http://127.0.0.1:8788/v1/tools/web-search" \
  -H 'Content-Type: application/json' -d '{"query":"杭州 天气","count":6}'
# 8. fetch a public page; the answer is {"url","title","text"}
curl -s -X POST "http://127.0.0.1:8788/v1/tools/web-fetch" \
  -H 'Content-Type: application/json' -d '{"url":"https://example.com/"}'
# 9. run one proactive heartbeat immediately (debug endpoint; same now only fires once)
curl -s -X POST "http://127.0.0.1:8788/v1/proactive/tick" \
  -H 'Content-Type: application/json' -d '{"now":1700000000000}'
# 10. one session per persona; creating twice returns the existing row
curl -s -X POST "http://127.0.0.1:8788/v1/sessions" \
  -H 'Content-Type: application/json' -d '{"personaId":"seed-linshen"}'
# 11. mark a session read (unread → 0)
curl -s -X POST "http://127.0.0.1:8788/v1/sessions/seed-session-linshen/read" \
  -H 'Content-Type: application/json' -d '{}'
# 12. persona / memory / preset CRUD
curl -s -X POST "http://127.0.0.1:8788/v1/personas" \
  -H 'Content-Type: application/json' \
  -d '{"name":"新朋友","worldJson":"{\"worldName\":\"临港\"}","traits":["慢热"]}'
curl -s -X POST "http://127.0.0.1:8788/v1/personas/seed-linshen/memories" \
  -H 'Content-Type: application/json' -d '{"content":"他喜欢雨天","importance":80,"tags":["天气"]}'
curl -s "http://127.0.0.1:8788/v1/personas/seed-linshen/memories?limit=20"
curl -s -X POST "http://127.0.0.1:8788/v1/presets" \
  -H 'Content-Type: application/json' -d '{"name":"夏夜","worldJson":"{}"}'
# 13. backup (v6, client-shaped, no secrets) and merge/replace import
#     GET is a chunked stream (no Content-Length); curl writes it straight to a file
curl -s "http://127.0.0.1:8788/v1/backup" > backup.json
#     POST streams the body to a staging file server-side; build the envelope without
#     loading the backup into a shell variable (`--data-binary @-` + printf/cat)
{ printf '{"mode":"merge","data":'; cat backup.json; printf '}'; } \
  | curl -s -X POST "http://127.0.0.1:8788/v1/backup" \
      -H 'Content-Type: application/json' --data-binary @-
# 13b. batch history import (client-side local history → server, ≤200 per batch, ≤8 MiB)
curl -s -X POST "http://127.0.0.1:8788/v1/sessions/seed-session-linshen/messages/import" \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"id":"0b1c2d3e-0000-4000-8000-000000000010","role":"USER",
       "content":"本地历史第一条","createdAt":1763200000000},
       {"id":"0b1c2d3e-0000-4000-8000-000000000011","role":"ASSISTANT",
       "content":"本地历史第二条","createdAt":1763200000500,"readAt":1763200000900}]}'
# -> {"sessionId":"…","imported":2,"skipped":0,"blockRange":{"first":0,"last":0},"messageCount":2}
# 14. push token registration (APNs/FCM delivery is deployment-side)
curl -s -X POST "http://127.0.0.1:8788/v1/push/register" \
  -H 'Content-Type: application/json' -d '{"platform":"ios","token":"device-token","bundleId":"com.example.yap"}'
curl -s -X DELETE "http://127.0.0.1:8788/v1/push/register" \
  -H 'Content-Type: application/json' -d '{"platform":"ios","token":"device-token"}'
# 15. media: raw image bytes in, raw bytes out (client already compresses to ≤800 KB)
curl -s -X POST "http://127.0.0.1:8788/v1/media?kind=CHAT_IMAGE&mime=image/jpeg" \
  -H 'Content-Type: image/jpeg' --data-binary @chat.jpg
# -> {"id":"…","kind":"CHAT_IMAGE","mime":"image/jpeg","bytes":…,"sha256":"…","url":"/v1/media/…"}
curl -s "http://127.0.0.1:8788/v1/media/<id>" -o downloaded.jpg
# 16. attach that media to a message (bare id or /v1/media/<id>)
curl -N -s -X POST "http://127.0.0.1:8788/v1/sessions/seed-session-linshen/messages" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"clientMessageId":"0b1c2d3e-0000-4000-8000-000000000002","content":"看图","attachments":["<id>"]}'
```

### CRUD, backup and push (M3–M6 server side)

The routes in the table above follow the same token/rate-limit/error-body rules as the rest
of `/v1` (1 MiB JSON body cap, with three deliberate exceptions: `POST /v1/media` 4 MiB,
`POST /v1/sessions/{id}/messages/import` 8 MiB and `POST /v1/backup` 256 MiB). All writes go
through the single `FileStore` lock, so a read-modify-write update (for example merging fields into
one persona) cannot lose a concurrent request's field. Errors are
`404 not_found` for unknown ids, `400 missing_field` / `invalid_argument` / `invalid_json` for bad
input, `409 unsupported_backup_version` when an import is newer than v6, and `405
method_not_allowed` for a known path with the wrong verb.

**Personas, memories and presets.** These endpoints accept the client field names directly.
- `POST /v1/personas`: no/`null` `id` creates a new persona (server-generated id); a non-null `id`
  updates that existing persona and is `404` when it does not exist. Core fields (`name`,
  `nameEn`, `description`, `avatarUrl`, `isDefault`, `activityJson`) are typed; **every other key**
  (`worldJson`, `speechStyleJson`, `traits`, `likes`, `behaviors`, `backstory`, `quirks`,
  `translationJson`, `translationLang`, `personaCardJson`, unknown future fields) is stored and
  returned as raw JSON. Missing fields on update keep their old value. Setting `isDefault:true`
  clears the other personas' default flag.
- `DELETE /v1/personas/{id}` removes the persona's sessions, block files/messages, memories,
  activity (stored on the persona row), proactive counters, mood and presence. `media/` is left
  alone — the client owns media GC (contract §3.6).
- Memories live in `memories.json`; `since` filters on `updatedAt > since`, `limit` is 1–500
  (default 200). `tags` is a JSON string array on the wire (the client database stores it as a
  JSON string, but the HTTP/backup contract uses an array).
- Presets live in `presets.json`. They carry `worldJson` / `speechStyleJson` and keep unknown
  fields verbatim.
- Settings are merged through a whitelist and filtered on every read/export. There is **no API
  key route**: `apiKey`, `apiKeys`, `secrets` and friends are ignored by the write path and can
  never appear in a response or backup.

**Backup.** `GET /v1/backup` returns (v5 shape shown; 0.13 writes the client's v6 shape — see
[Backup v6](#backup-v6-interoperable-with-the-clients)):

```json
{
  "format": "myfriend.backup",
  "formatVersion": 5,
  "appVersion": "0.12.0",
  "exportedAt": 1700000000000,
  "apiKeysOmitted": true,
  "personas": [ … ],
  "sessions": [ … ],
  "messages": [ … ],
  "memories": [ … ],
  "presets": [ … ],
  "media": [
    { "id": "…", "kind": "CHAT_IMAGE", "personaId": null, "mime": "image/jpeg",
      "bytes": 12345, "sha256": "…", "createdAt": 1700000000000, "data": "<base64>" }
  ],
  "mediaSkipped": 0,
  "settings": { … }
}
```

`POST /v1/backup` takes `{"mode":"merge"|"replace","data":{…}}` (the `data` object is exactly the
export shape). `merge` updates/inserts by id and keeps everything not mentioned; `replace` clears
personas/sessions/messages/memories/presets/settings (and orphaned mood/presence/proactive rows)
before importing. Validation of ids, references, uniqueness, roles and importance happens **before
any mutation**, so a bad request leaves the data directory untouched. It is not a database
transaction: a process kill between the multiple atomic file writes can leave a partially imported
directory (see risks). Only `formatVersion == 5` is accepted; anything else is `409
unsupported_backup_version`. `usage.json`, `api_configs.json`, `secrets.json` and
`push_tokens.json` are not part of a backup and are preserved by `replace`.

**Media in backups.** `media[]` carries the raw bytes as base64 (`data`, never a filesystem path).
A single media file is exported only when it is ≤ 8 MiB, and the total exported media is capped at
128 MiB (raw bytes); entries above either limit are **skipped and counted** in `mediaSkipped`, so a
reader can tell "this backup deliberately omitted N images" from "images were lost". On import the
base64 is decoded back to `media/<id>.<ext>` and `media.json` is rewritten; `sha256` and `bytes` are
verified, so a corrupted/base64-mismatched entry rejects the whole import with `400 invalid_backup`
before anything is written. `replace` also deletes old media files (the backup is the new state);
`merge` keeps existing media and overlays entries with the same id (a same-id/different-sha conflict
is rejected). `POST /v1/backup` accepts up to 256 MiB of body (streamed to disk, see the streaming
section below), so media-heavy backups **can** be imported over HTTP as of 0.12.1.

### Batch history import (0.12.1)

`POST /v1/sessions/{id}/messages/import` pushes a client's **local history** to the server when the
client switches to server mode (local data is not migrated automatically).

```json
{"messages":[
  {"id":"<client message id>","role":"USER","content":"嗨","createdAt":1763200000000,
   "readAt":1763200000500,"attachments":["/v1/media/<id>"],"source":"IMPORT",
   "clientMessageId":"<optional>","replyTo":"<optional>"}
]}
```

- `id` (required): **kept verbatim** (no new id is generated) and must match `[A-Za-z0-9_-]{1,128}`
  (UUID strings are fine). `role` (required): `USER`/`ASSISTANT`/`SYSTEM`, case-insensitive on input
  and stored upper-case. `content` (required, may be **empty** — image-only history). `createdAt`
  (required, epoch millis). `readAt` (optional, epoch millis; absent/`null` = unread).
  `attachments` (optional, ≤8; a media id or `/v1/media/<id>`, normalised to the URL form; the media
  **must already exist**, otherwise `400 unknown_media`). `source` (optional; `CHAT`/`PROACTIVE`/
  `IMPORT`, unrecognised values are stored as `IMPORT`). `clientMessageId`/`replyTo` (optional,
  ≤128 chars). `blockIndex` is ignored — the server owns block layout.
- Limits: **≤200 messages per batch** (`400 too_many_messages`) and **≤8 MiB** per request
  (`413 content_too_large`).
- Response: `{"sessionId","imported","skipped","blockRange":{"first","last"}|null,"messageCount"}`.
  `blockRange` is the block range touched by this batch (`null` when `imported == 0`).
- Idempotency: a message id that already exists is **skipped, not overwritten** (`skipped++`), and the
  status is always `200` — retrying a batch is safe. A batch is atomic: one invalid item rejects the
  whole batch with `400` and writes nothing.
- Ordering: messages are sorted by `(createdAt, id)` and inserted into the block they belong to
  (block contents stay ordered). Existing messages keep their `id`, `content`, `createdAt`, `readAt`
  and `blockIndex`; only when an insert would push a block past 200 messages does the whole session
  get re-blocked (block indices are structural, message contents are not modified).
- Side effects: the session's `lastMessageAt`, `messageCount`, `lastBlockIndex` and `updatedAt` are
  updated; **`unread` is not touched** (history must not turn into "N unread"), no `message.created`
  event is emitted, and no LLM call happens.
- Errors use the standard `{"error":{"code","message"}}`: `not_found` (unknown session),
  `invalid_argument`, `missing_field`, `invalid_json`, `unknown_media`, `too_many_messages`,
  `content_too_large`.
- Scale note: each batch re-reads the session's message ids to enforce idempotency (O(history) per
  batch), so migrating 100k messages as 500 batches costs on the order of tens of seconds of IO. That
  is fine for a one-time "switch to remote" push, but this route is not a bulk-copy engine.
- Typical client flow: `GET /v1/sessions`; if every session has `messageCount == 0`, push local
  history in `(createdAt, id)` order, 200 messages per batch. Reading history back:
  `GET /v1/sessions/{id}/messages` returns the newest block, `beforeBlock=<blockIndex>` walks older
  pages, and `hasMoreOlder == false` ends the walk (block indices may have holes, so do not use
  `blockIndex == 0` as the stop condition). An empty session answers `blockIndex: null`,
  `messages: []` with both `hasMoreOlder`/`hasMoreNewer` false.

### Streaming backup (0.12.1)

Both backup directions used to hold the whole payload in memory (a v5 JSON tree + string, plus every
media `ByteArray`/base64 string). With 100k messages and 500 images that was hundreds of MiB of heap.
0.12.1 makes both directions streaming.

**Export (`GET /v1/backup`).** The response is `HTTP/1.1 200` with `Transfer-Encoding: chunked` and
**no `Content-Length`** (`Connection: close` ends the body; an HTTP/1.0 request gets a close-delimited
body instead). The server:
1. takes a metadata snapshot under the store lock (personas/sessions/memories/presets/settings/media
   list — all small);
2. writes the JSON head (`format`, `formatVersion`, `appVersion`, `exportedAt`, `apiKeysOmitted`) and
   the small sections, then streams `messages` as a **k-way merge**: one lazy cursor per session
   reading one block (≤200 messages) at a time, so global `(createdAt, id)` order is preserved with
   memory independent of history length;
3. streams `media[]` one file at a time: a streaming SHA-256 pre-check (8 KiB buffer) followed by
   chunked base64 encoding (24 KiB of raw bytes per chunk), so an 8 MiB image never becomes an
   in-memory `ByteArray` + base64 `String`. Entries that are missing/tampered/too large are skipped
   and counted in `mediaSkipped`.

Peak heap is therefore a few tens of KiB of buffers + one message, regardless of backup size (target
≤32 MiB for 100k messages + 500 images; the test below runs in a 256 MiB heap). Trade-off: the store
lock is only held for the snapshot, so a message appended while the export runs may or may not be
included (the backup is always valid JSON; a session deleted mid-export is simply skipped).

**Import (`POST /v1/backup`).** The request body is streamed (64 KiB at a time, with its own 300 s read
deadline) into `<data>/.staging/upload-*.json`; that file is then parsed **incrementally** by a small
streaming JSON reader (`store/BackupJson.kt`) instead of `ByteArray` + `String` + `Json.parse`:
- `personas`/`sessions`/`memories`/`presets` are small and stay in memory as model objects;
- `messages` are validated one item at a time and appended to a per-session JSONL spool
  (`.staging/messages-<hash>.jsonl`), then read back **per affected session** when the block files are
  rewritten (peak = one session's messages);
- `media[].data` is base64-decoded **straight to a staging file** (`.staging/media-<n>.bin`, 3-byte
  quartets, ≤8 MiB enforced while decoding), with `sha256`/`bytes` verified before committing;
  committing moves the file into `media/` with an atomic rename;
- unknown top-level sections/keys are skipped (forward compatibility); the envelope requires `mode`
  **before** `data` (both our export and the clients' `JSON.stringify` keep that order).

Validation (ids, references, one-session-per-persona, media conflicts, settings whitelist) still runs
**before any mutation**, so a bad request leaves the data directory untouched. `.staging/` is always
removed afterwards (and swept on startup if the process was killed). Peak heap is dominated by "one
message + one media chunk + one session's messages" (target ≤64 MiB for a 100k-message backup). Disk:
the request body is on disk twice while an import runs (the upload file + the media staging files), so
size that volume accordingly.

**Testing the memory bound.** `agentd/build.gradle.kts` runs the whole `:agentd:test` task with
`maxHeapSize = "256m"`, and `BackupStreamMemoryTest` proves the guarantee end to end: it seeds
**10,000 messages + 50 × 64 KiB images**, exports over real HTTP with
`HttpResponse.BodyHandlers.ofFile` (chunked response → file), wraps the result into a
`{"mode":"replace","data":…}` envelope by streaming, posts it back with
`HttpRequest.BodyPublishers.ofFile`, and asserts the imported counts and media bytes. Any
implementation that buffers the whole backup would `OutOfMemoryError` under that heap. The test also
asserts `Runtime.maxMemory() <= 320 MiB`, so "it did not OOM" is meaningful. `BackupStreamTest`
covers the transport details: chunked framing replayed from a raw socket, absence of
`Content-Length`, "chunked body == handler-level export", 8 MiB single-media round-trip, corrupt
base64 rejected without writing media, and unknown sections skipped.

> Format-version note: the client's `BACKUP_FORMAT_VERSION` is **5** and the M3–M6 write-path task
> asked for v5; 0.13 bumps it to v6 to stay aligned with the clients. v1–v5 imports still work. The design document's endpoint table still
> says "v6"; if that becomes the intended contract, bump the client constant, this constant and the
> accepted version together (never silently accept both and guess).

### Media upload, download and attachments (M5)

Images are the last client-owned state moved to the server. The wire format is deliberately dumb:
**raw bytes in, raw bytes out**, no multipart, no base64 on the hot path.

```bash
# upload: Content-Type is the mime; kind is required; personaId is optional (and must exist)
curl -s -X POST "http://127.0.0.1:8788/v1/media?kind=CHAT_IMAGE&mime=image/jpeg" \
  -H 'Content-Type: image/jpeg' --data-binary @chat.jpg
# 201 new, 200 when the same sha256 already exists:
{"id":"6f1c…","kind":"CHAT_IMAGE","mime":"image/jpeg","bytes":8123,
 "sha256":"…","url":"/v1/media/6f1c…"}

# download: same token as every /v1 route; immutable + strong ETag for cheap revalidation
curl -s "http://127.0.0.1:8788/v1/media/6f1c…" -H "If-None-Match: \"<sha256>\"" -o chat.jpg

# delete: 409 while referenced; force removes the file too
curl -s -X DELETE "http://127.0.0.1:8788/v1/media/6f1c…?force=1"
```

- Storage: metadata in `media.json`, bytes in `media/<id>.<ext>`. `id` is a server-generated UUID;
  the extension comes from the mime, so clients never supply a path/filename. Writes are
  `*.tmp` → `ATOMIC_MOVE`, and metadata is rewritten atomically under the store lock.
- Dedup: sha256 is the identity. Re-uploading identical bytes returns `200` with the **existing**
  item (including its original `kind`/`personaId`), and no second file is written; concurrent
  uploads of the same bytes are serialised by the store lock.
- Limits: mime must be `image/jpeg` / `image/png` / `image/webp` (`415` otherwise; `?mime=` must
  match `Content-Type`), single file ≤ 2 MiB (`413`), media route body ≤ 4 MiB, message import ≤ 8 MiB,
  backup import ≤ 256 MiB (streamed to disk), all other routes 1 MiB.
  The server does **not** decode or sniff the bytes (Content-Type is trusted; see risks).
- Attachments: `POST /v1/sessions/{id}/messages` takes `attachments: ["<id>" | "/v1/media/<id>"]`,
  validates every entry exists (`400 unknown_media`) and stores `/v1/media/<id>`. `GET` messages
  (and `/v1/state`, backups) return the same **string-array-of-URLs** shape.
- References / GC: `DELETE` scans message attachments + persona `avatarUrl` + preset `avatarUri`.
  With references it returns `409 media_in_use` and the count; `?force=1` deletes anyway. Deleting
  a persona still leaves its media alone (the client owns GC), and old messages are never rewritten.
- Restart: `media.json` is optional for old data directories (missing = no media). A metadata row
  whose file disappeared returns `404` plus one stderr warning instead of throwing.

**Push registration (M6 server half).** `POST /v1/push/register` stores
`{platform,token,bundleId}` in `push_tokens.json` (idempotent on `platform+token`); `DELETE`
removes it by body or query. `agentd` does **not** implement APNs or FCM itself. It exposes a
pluggable interface:

```kotlin
fun interface PushSender {
    fun send(tokens: List<PushRegistration>, message: PushMessage)
}
```

The default is `NoopPushSender`, so without credentials the server simply persists the proactive
message and clients catch up via `/v1/state`, `/v1/events` or SSE. When tokens exist,
`ProactiveTicker` calls the injected sender after the proactive message is committed; a sender
failure is logged and never rolls back the message or breaks `POST /v1/proactive/tick`. Production
credentials and the actual APNs/FCM HTTP client are **deployment-side** — wire a real `PushSender`
through `AgentdServices.pushSender`; nothing secret belongs in this repository.

### Server-side search, fetch and proactive tick (0.12.0)

The mobile clients no longer hold search credentials or call providers themselves: these three
routes run on the server. The request/response shapes are intentionally small:

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| `POST` | `/v1/tools/web-search` | `{"query":"…","count":6,"provider":"MESSAGEAPI\|TAVILY\|SEARXNG\|BING\|ZHIHU\|TOUTIAO\|QUORA"}` (`count`, `provider` optional) | `{"provider":"…","results":[{"title","url","snippet"}]}` |
| `POST` | `/v1/tools/web-fetch` | `{"url":"https://…"}` | `{"url","title","text"}` (scripts/styles/tags removed, whitespace collapsed, ≤ 8000 chars) |
| `POST` | `/v1/proactive/tick` | optional `{"now":<epoch millis>}` (defaults to the server clock) | `{"now","fired","decisions":[{"personaId","sessionId","messageId","reason"}]}` |

Provider configuration is **server-side only**:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `YAP_AGENTD_SEARCH_PROVIDER` | `MESSAGEAPI` | Default provider when the request body does not name one. Dirty values are normalised like every other provider setting. |
| `YAP_AGENTD_SEARCH_BASE` | — | Provider base URL. `MESSAGEAPI` / `SEARXNG` / `GENERIC` have no fixed default and need it; Tavily/Brave/Serper/Bing fall back to their public endpoints. |
| `YAP_AGENTD_SEARCH_KEY` | — | Provider API key. **The client never sends it and the server never accepts one from the request body.** |

- **Only `SEARXNG` may override the base per request** (`{"provider":"SEARXNG","baseUrl":"http://…"}`):
  a self-hosted instance address is not a secret, and one deployment may point at different instances.
  For every other provider the request `baseUrl` is ignored — otherwise a client could aim the server's
  key at an arbitrary host (SSRF + credential exfiltration).
- A body-level `apiKey` is ignored for the same reason: the key always comes from
  `YAP_AGENTD_SEARCH_KEY`. Neither the key nor the upstream error body (which may echo it) appears
  in responses or logs; the HTTP client's log preview replaces the secret with `***`.
- Results are normalised exactly like the clients' `WebSearchClient`: non-http(s) URLs dropped,
  deduplicated by URL (first wins), snippets tag-stripped and capped at 500 chars, at most `count`
  (1–12) items.
- Error mapping: `400` for an empty query, missing key, missing/invalid base or a rejected URL;
  `401` when the provider rejects the key; `429` when the provider rate limits; every other upstream
  failure (`403`, `4xx`, `5xx`, timeouts, unparsable responses…) is `502`. Error bodies keep the usual
  `{"error":{"message","code"}}` shape (`search_bad_request` / `search_unauthorized` /
  `search_rate_limited` / `search_upstream_error`).
- `web-fetch` always connects **directly** (`config = null`; it does not use `MESSAGEAPI`'s
  `tools/fetch`) and goes through the same `WebUrlPolicy` as the clients: http(s) only, no loopback /
  private hosts / userinfo.
- `proactive/tick` reads personas, sessions, the latest `lastMessageAt` and mood from the data
  directory, then runs the same `ProactiveEngine` as the timer. A fired decision asks the model for
  one opener (`LlmProactiveTextSource`), persists it as `role=ASSISTANT, source=PROACTIVE` in that
  persona's most recently active session and emits the usual `message.created` event. If the model
  call fails the tick is reported as `generation_failed` and **nothing is written** (no template
  fallback). The same `now` only fires once (in-process `lastTickAt` dedupe); the route answers
  `503 store_disabled` when the server was started without `--data`.


Trimmed responses (0.12.0; `mood` gained `valence`/`arousal`/`dominance`/`reason` in 0.13):

```json
{"serverTime":1700000000000,
 "personas":[{"id":"seed-linshen","name":"林深","nameEn":"Elias","avatarUrl":null,"isDefault":true,"updatedAt":1000}],
 "sessions":[{"id":"seed-session-linshen","personaId":"seed-linshen","title":"和林深的对话",
              "lastMessageAt":null,"unread":0,"lastBlockIndex":-1}],
 "messages":[],
 "mood":{"personaId":"seed-linshen","label":"平静","intensity":2,"updatedAt":1000,
         "valence":0,"arousal":2,"dominance":3,"reason":""},
 "presence":{"personaId":"seed-linshen","kind":"IDLE","detail":"在家，安静地待着","updatedAt":1000},
 "serverCursor":0,
 "hasMoreMessages":false}
```

`hasMoreMessages` is an additive field: the first snapshot returns **only the newest block per
session** (the same rule as the clients' initial block load), so clients continue with the paging
endpoint for older history. `since > 0` walks blocks from newest to oldest and stops at
`MAX_STATE_MESSAGES = 500` (also flagged by `hasMoreMessages`).

```json
{"sessionId":"seed-session-linshen","blockIndex":0,"hasMoreOlder":false,"hasMoreNewer":false,
 "messages":[{"id":"…","sessionId":"seed-session-linshen","blockIndex":0,"role":"USER",
              "content":"在吗","createdAt":1001,"readAt":null,"attachments":[],
              "source":"CHAT","clientMessageId":"0b1c…","replyTo":null}]}
```

`blockIndex` is `null` when the requested page does not exist (paging past either end, or an empty
session); `hasMoreOlder`/`hasMoreNewer` then tell the client which direction still has content.
`limitBlocks` only accepts `1` (otherwise `400 unsupported_limit_blocks`) — a page is one block by
contract.

SSE response (`curl -N`; `Content-Length` is intentionally absent, the stream ends when the server
closes the connection):

```
event: accepted
data: {"userMessageId":"…","createdAt":1001}

event: delta
data: {"text":"你说：「在吗」\n收到啦，我在。"}

event: delta
data: {"text":"（agentd 回显回复：还没接 LLM…）"}

event: done
data: {"assistantMessageId":"…","usage":{"promptTokens":2,"completionTokens":34}}
```

### SSE: idempotency and "keep generating after the client leaves"

- `clientMessageId` is the idempotency key (required, ≤ 128 chars). The user message is persisted
  **before** the first byte is written, and the `accepted` event carries its id.
- The assistant message is persisted with `source: "CHAT"` and `replyTo: <userMessageId>`; a second
  POST with the same `clientMessageId` finds both and replays `accepted` + `done` **without calling
  the generator again**. Concurrent duplicates are serialised on a per-key lock and also generate
  exactly once (the follower replays).
- If the user message is there but its reply is not (the process died between the two writes), the
  next request generates the missing reply once.
- **Disconnect handling:** the generator keeps running when the socket is gone. `HttpSseSink` turns a
  failed write into a `broken` flag (logged once) instead of an exception, and `ChatService`'s
  `SafeSink` swallows write failures, so the delta loop always reaches the persist step. The assistant
  message, its usage and its event land in the store even if nobody received the stream; the client
  picks it up via `/v1/state` or `/v1/events` on the next sync.
- `event: error` (`generation_failed`) ends the stream without `done`; whatever text was already
  generated is still persisted and counted. A generator that produced *nothing* persists no empty
  assistant message.
- Token usage (`promptTokens`/`completionTokens`) is supplied by the generator and accumulated per
  persona in `usage.json`; the same numbers are stored on the assistant message so a replayed `done`
  stays truthful.

### Events

Each line of `events.jsonl` (and each `events[]` item) is:

```json
{"cursor":3,"type":"message.created","at":1002,"sessionId":"seed-session-linshen","personaId":"seed-linshen",
 "payload":{"message":{…}}}
```

0.14.1 adds two event types (`spec/experience` §8):

```json
{"cursor":12,"type":"typing.started","at":1002,"sessionId":"seed-session-linshen","personaId":"seed-linshen","payload":{}}
{"cursor":13,"type":"typing.stopped","at":1006,"sessionId":"seed-session-linshen","personaId":"seed-linshen","payload":{}}
```

They are sent only while a client is “foreground online” (a `/v1/` request within the last 60 s);
one `typing.started` / `typing.stopped` pair per assistant turn. Offline clients get no typing events
at all — messages are stored with `visibleAt = createdAt` and the next sync shows them immediately.

`mood.changed` / `presence.changed` carry `"payload":{"mood":{…}}` / `"payload":{"presence":{…}}` and
have `sessionId: null`. Cursors come from `meta.cursor`, increase by one per event and never get
reused; `GET /v1/events?since=<cursor>` returns events with a strictly larger cursor. When the long
poll times out, it answers `200` with an empty `events` array and **the same cursor** — clients must
not advance their cursor on an empty page. `AgentdServer` keeps a 20 000-event in-memory window and
falls back to reading `events.jsonl` for older cursors.

### Limits and risks of this phase

Honest list; most of these are deliberate scope cuts, not oversights.

- **One page = one block, no offset.** `limitBlocks` must be `1`; `beforeBlock`/`afterBlock` pick the
  neighbouring *existing* block, so a hole (deleted block) is skipped. Blocks shrink but never merge.
- **Long poll and SSE occupy HTTP worker threads** (`WORKER_THREADS = 4`, fixed). A 25 s long poll
  holds one worker for its whole wait, and a generation holds one while streaming; five concurrent
  `/v1/events` therefore starve `/health`/other requests until they time out. Fine for the intended
  single-user deployment, but moving long poll/SSE onto their own threads is a P2/P3 follow-up.
- **SSE has no write timeout.** Java sockets only expose a read timeout, so a client that opens the
  stream and then stops reading can block one worker on a full send buffer for as long as the OS
  keeps the connection. Combined with the point above this is a denial-of-service risk on a hostile
  network; keep the service behind the token/reverse proxy until streaming moves to dedicated
  threads with a write deadline.
- **No SQL, no transactions.** All state is JSON/JSONL with one process-wide write lock. Queries are
  bounded per block, but idempotency lookups build a per-session in-memory index by scanning blocks
  once (then maintained incrementally). Running two `agentd` processes against one data directory is
  **not** supported — the lock is in-process only. Backup import validates everything before mutating
  memory/disk, but the multi-file write itself is not an atomic transaction: a process kill in the
  middle can leave a partially imported directory (re-import from the same backup to finish).
- **No fsync / crash window.** Rename gives atomic replacement, and a killed process keeps
  consistent files, but a message line that was appended before `block_index.json`/`sessions.json`
  were rewritten can leave the block count off by one. Recovery: delete that session's
  `block_index.json` and let the server rebuild it from the block files (it is not automatic). A torn
  trailing line in a block/event file is skipped with a warning instead of losing the file.
- **No event compaction, no delete events.** `events.jsonl` grows forever (`message.created` for
  user and assistant, `mood.changed`, `presence.changed` only). Deleting a message (`FileStore.deleteMessage`,
  used by tests) does not emit an event, so an incremental client will not learn about deletions
  until a full sync / a P3 delete-event contract.
- **Read receipts are all-or-nothing per session.** `POST /v1/sessions/{id}/read` always sets
  `unread = 0`; `upTo` only limits which messages get a `readAt` timestamp. This matches the
  contract phrase `{upTo?} → unread=0`, but it means a client cannot represent "I read only the
  older half and still have something new" through this endpoint.
- **No push protocol, no background scheduler wiring.** `PushSender` is an interface and
  `NoopPushSender` is the default; APNs/FCM credentials, HTTP/2 clients and retry policy are
  deployment-side. Tokens are stored, but a proactive message is only pushed if a real sender was
  injected; without one, clients catch up on the next foreground sync. The proactive timer runs as a
  daemon thread inside `agentd` (started by `Main`), so it stops when the process stops.
- **Media validation is Content-Type only.** The server does not decode or sniff image bytes: a
  client with a valid token can upload arbitrary bytes labelled `image/jpeg`. Keep the token on and
  the service behind a reverse proxy; magic-byte checks / re-encoding are a later hardening step.
  Related media edges: dedup is by sha256, so uploading identical bytes for a different `kind`
  returns the first item's `kind` with `200`; `DELETE ?force=1` does not rewrite historical messages
  (they keep `/v1/media/<id>` and downloads then 404); `DELETE /v1/personas/{id}` still does **not**
  delete media (the client owns media GC). Media metadata lives in `media.json` under the same single
  process-wide lock (a missing file is treated as empty; a metadata row whose file disappeared
  returns 404 with a stderr warning). `POST /v1/sessions/{id}/messages/import` validates
  `attachments` by asking `media.json`, so an attachment pushed before its image is uploaded is
  rejected with `400 unknown_media` — upload media first, then messages.
- **Backup streaming has deliberate constraints (0.12.1).** `GET /v1/backup` now needs a client that
  understands `Transfer-Encoding: chunked` (all mainstream HTTP stacks do; a hand-written socket
  parser must decode chunks; the response has no `Content-Length`). The import envelope requires
  `mode` **before** `data`; a JSON writer that reorders top-level keys gets `400 invalid_backup`
  instead of a half-applied import. The incremental parser is a second JSON implementation (~450
  lines) kept to the strict subset of the backup format — it is covered by the stream/round-trip
  tests, but it is not a general-purpose JSON parser and should not be reused elsewhere. During an
  import the request body and media staging files occupy disk under `<data>/.staging/` (worst case
  roughly body size + decoded media size); a process kill mid-import leaves that directory behind and
  it is swept on the next start (the *data* itself is still all-or-nothing up to the commit point).
  Import memory is bounded by the largest affected session (its messages are materialised to rewrite
  its block files), not by the whole backup — a single session with hundreds of thousands of messages
  will still use correspondingly more heap. Export no longer holds the store lock for its whole
  duration, so a concurrent append/delete can be reflected "as of a slightly fuzzy instant"
  (still valid JSON; the alternative was blocking chat for the whole transfer).
- **LLM is wired, but only for text.** `LlmReplyGenerator` (chat), `LlmProactiveTextSource`
  (proactive openers), `EvolutionService` and `MoodService` call the model for real; the prompt is
  built on the server and clients still never send prompts. **The tool loop is not implemented**
  (`web_search` / `remember` / `defer_reply` / `stay_silent` only exist as separate endpoints/tools),
  so the model cannot act, only talk, and the prompt says so. Every generated reply costs tokens —
  a proactive tick that fires, an evolution evaluate and a mood evaluate each make one completion
  call; there is no budget gate on the server yet. `secrets.json` (0600, best-effort on non-POSIX
  file systems) is the only place an API key may live and it is never part of a response; keys are
  redacted from logs (`LlmConfig.redacted`).
- **The client-push path for `api_configs.json` / `secrets.json` is still missing.** The resolver
  reads them for loopback deployments, but no HTTP route writes them yet (backups and the settings
  whitelist deliberately exclude keys). Until that endpoint / local bridge exists, a loopback
  deployment must either place the files itself or use `--llm-*`.
- **Mood auto-evaluation is not scheduled.** `POST /v1/personas/{id}/mood/evaluate` works and the
  "every 4 user messages / 24 h" rule is implemented and tested, but nothing calls it automatically
  after a chat turn yet (cost/queue decision). Same for persona evolution: the client has to call
  `evaluate` (the server does not run a 20-message trigger).
- **Evolution patches are a small JSON file, not a table.** `pending_patches.json` is rewritten under
  the same process-wide lock; it is fine for a handful of patches per persona, but it is not a
  queue and there is no history of accepted/rejected patches (accepted ones only bump
  `personas.json`'s `version` and the four fields).
- **Prompt budget is count-based.** Chat sends at most the last 40 messages; history trimming by
  tokens/characters (`spec/api-defaults`' 1M context is only an upper bound) is not implemented.
  Very long messages are sent as-is (the message itself is capped at 65 536 chars on write).
- **Search credentials are environment config, not a settings API.** `YAP_AGENTD_SEARCH_*` is
  read through `AgentdServices.env` (production: `System.getenv`) on every request, but a JVM cannot
  change its own environment, so rotating the key or the default provider needs a restart. There is
  also no per-persona search configuration yet: the whole server shares one provider/base/key. The
  search/fetch paths are only verified against local fake HTTP servers in CI — no real provider or
  web page is contacted by the tests.
- **The proactive timer is now started by `Main` (0.13).** A single daemon tick every 60 s drives
  `ProactiveTicker`; `POST /v1/proactive/tick` remains a debug/test entry point. De-duplication of
  the same `now` and the "just fired" overlay are still **in-process**
  (`ProactiveTicker` → `ProactiveScheduler`), so they do not survive a restart and are not shared
  between processes (two processes on one data directory are unsupported anyway). Today's count and
  last-fired timestamp are persisted in `proactive_state.json`; a tick whose LLM generation fails is
  skipped (`generation_failed`) and retried on a later tick. The timer thread blocks for the length
  of the model call, so a slow provider delays the next tick (single-threaded scheduler).
- **Still plain HTTP.** The token/rate-limit docs above apply unchanged; TLS belongs to the reverse
  proxy.

### Tests for this phase

`MessageImportApiTest` (19) covers `POST /v1/sessions/{id}/messages/import`: normal/out-of-order
import into the right block, idempotent skip (no overwrite, `readAt` untouched), unknown session,
>200 per batch, bad role / illegal id / missing `createdAt`, unknown attachment, empty batch, 8 MiB
body cap, restart persistence, pagination consistency over 450 messages (blocks 200/200/50),
"existing messages keep every field", `unread`/event cursor untouched, and `GET` on the path → 405.
`BackupStreamTest` (14) covers the streaming transport: chunked framing decoded from a raw socket
(no `Content-Length`), chunked body == handler-level export, HTTP import with a streamed body +
staging cleanup, bad JSON → `400 invalid_json` with no leftovers, `data` before `mode` rejected,
non-object `data`, trailing garbage, an 8 MiB single-media round-trip through the file-based
streaming path, oversized media counted in `mediaSkipped`, corrupt base64 rejected without writing
media, sections in any order + unknown sections skipped, and the `userAvatarUri`/`myAvatarUri`
settings normalisation. `BackupStreamMemoryTest` (1) is the memory proof: 10,000 messages + 50
images exported/imported over real HTTP inside a 256 MiB heap (`maxHeapSize = "256m"` on the whole
test task) — a buffering implementation would OOM there.
`FileStoreTest` (19) covers seeding/read-back, block splitting at 200, delete boundary recomputation,
emptied-block holes, missing-index rebuild, atomic writes (no `*.tmp`, parseable JSON), 8 threads ×
125 concurrent appends with no loss, idempotency lookup, `secrets.json` permissions, usage,
event cursors/long-poll timeout, torn-line tolerance and path-traversal guards.
`StateApiTest` (18) covers full/incremental state, persona filtering, session list fields, both paging
directions, hole skipping, invalid paging parameters, events long poll (immediate/timeout/wake-up)
and the "no data directory → 503" path. `MediaApiTest` (24) covers upload/dedup/PNG+WebP/type+kind+
persona+size rejects, download headers/ETag/304, unknown and traversal-shaped ids, delete with
message/persona/preset references + `force`, attachment id/URL canonicalisation and `unknown_media`,
backup media round-trip / 8 MiB skip count / corrupt-sha rejection / replace clearing old media,
restart persistence and 8 concurrent same-content uploads (one file). `MediaServerTest` (4) is the
real-socket half: binary upload/download over HTTP, 304 revalidation, token enforcement on media
routes, and the 4 MiB media vs 1 MiB other-route declared-body limits. The whole `:agentd:test`
suite is now **473 tests** (0.12.1's 392 + 81 for the 0.13 LLM/proactive/evolution/mood work). `ChatSseTest` (10) covers event order, tool forwarding,
sequential and concurrent idempotency, orphan-reply completion, a dead sink, attachment persistence
and validation-before-stream. `AuthorityServerTest` (6) is the real-socket half: SSE over HTTP, a
client that disconnects mid-stream still gets a persisted reply, token/rate limit on the new routes,
long poll over HTTP and JSON errors before a stream starts. `MainOptionsTest` grew to 13 with the
`--data` and `--llm-*`/`YAP_LLM_*` precedence/validation cases. `ToolsRoutesTest` (17) covers the three new routes: provider /
base / key resolution (`MESSAGEAPI` default, `SEARXNG` base override, request `baseUrl` ignored for
every other provider, client `apiKey` ignored), result normalisation, the 400/401/429/502 error
mapping, "the key never appears in a response or a log", tag-stripped fetch + `URL_REJECTED`, and
proactive tick (one `source=PROACTIVE` assistant message persisted + event emitted, same `now` fires
once, `now` defaults to the server clock, `503` without a data directory), plus one real-socket case
proving `AgentdServer` dispatches the new paths behind the same `--token`.
`CrudRoutesTest` (23) covers sessions (`POST`, one-per-persona, read/upTo), persona create/update/
delete with unknown-field preservation and cascade cleanup, memory/preset/settings CRUD, 400/404/405,
concurrent updates of one persona (no lost fields), and restart persistence. `BackupApiTest` (12)
covers export contents/no secrets, replace/merge semantics, export→import→export equality,
version-6 → 409, reference validation before any write, and restart. `PushRoutesTest` (8) covers
registration/idempotency/unregistration/persistence, the `PushSender` invocation after a proactive
message, no-token silence, and a throwing sender that must not break the tick. The 0.13 batch adds
`LlmConfigResolverTest` (12: loopback uses the client config + secret, newest config wins, loopback
falls back to `--llm-*`, remote never reads the client config, missing/incomplete configs,
`YAP_LLM_*`, endpoint normalisation, key redaction), `LlmClientTest` (12: real chunked SSE deltas,
request body `temperature=0.75`/`max_tokens=16384`/`stream=true`/`Authorization`, comment/event
lines, usage estimation, plain-JSON fallback, 401/429/stream-error/empty-stream/network/invalid-URL
mapping), `LlmReplyGeneratorTest` (5: streamed deltas persisted verbatim, no config →
`llm_not_configured` with no assistant row, 401, partial-then-error persistence, history roles),
`ChatPromptBuilderTest` (7: normative section order incl. `# 你的心情` between state and voice,
world/speech/profile/card rendering, busy activity, multi-dimensional mood without self-report,
history/attachment note, proactive instruction), `ProactiveLlmTest` (8: model-written proactive text
+ `message.created` event, prompt content, LLM failure → `generation_failed` with nothing written,
no-config no-request, scheduler start/stop, tick route `messageId`, whitespace source, persona state
passed through), `PersonaEvolutionRulesTest` (8), `EvolutionRoutesTest` (11: evaluate/pending/accept
/version+1/reject/404/410/dedup/field-shape), `MoodRulesTest` (9: parse/clamp/±2 limit/noticeable/
decay/prompt/trigger/legacy migration) and `MoodServiceTest` (7: stored dimensions + `mood.changed`,
±2 clamp, no-change no-write, decay and upstream-failure fallbacks, route/state output, 404/405).

## LLM, proactive messages, persona evolution and mood (0.13)

0.13 turns `agentd` from "an HTTP skeleton with an echo generator" into the part that actually
generates: chat replies, proactive openers, persona-evolution proposals and multi-dimensional mood.
Everything still happens on the server — the client renders the SSE stream and reads state; it does
not send prompts and does not hold the key.

### Which LLM config the server uses

| Deployment (`--host`) | Where the config comes from | Client-pushed config |
| --- | --- | --- |
| **loopback**: `127.0.0.1`, `localhost`, `::1`, `127.*` | `api_configs.json` + `secrets.json` in the `--data` directory (newest usable config first); if there is none, the server-side `--llm-*` / `YAP_LLM_*` | **used** |
| **remote**: `0.0.0.0`, a LAN address, … | only `--llm-base-url` / `--llm-key` / `--llm-model` (or `YAP_LLM_*`) | **never read** |

Why the asymmetry: on a loopback deployment the client and the server are the same person's machine,
so reusing the config the desktop/mobile client already has avoids typing the key twice. A remote
deployment belongs to an operator; letting a client decide "which URL the server posts to, with
which key" would be SSRF plus key exfiltration, so `LlmConfigResolver` does not even look at
`api_configs.json` in that mode.

A "usable" config has a non-blank `baseUrl` and `model`; the key may be absent (Ollama/one-api
gateways). `baseUrl` is the OpenAI-compatible root (`.../v1`); a value that already ends with
`/chat/completions` is accepted as-is. An explicit server-side config that is missing `baseUrl` or
`model` fails with `llm_invalid_config` instead of being silently ignored.

**Known gap:** there is no HTTP route yet that *writes* `api_configs.json` / `secrets.json` from a
client (the CRUD settings whitelist deliberately refuses keys, and backups never contain them). The
store API (`FileStore.putApiConfig` / `setApiKey`) and the resolver are ready; the client-facing
push endpoint / local-file bridge is still to be wired (see "What this service does not do yet").

### Chat: real streaming (`LlmReplyGenerator`)

- `LlmClient` uses the JDK `java.net.http.HttpClient` (no third-party dependency) and POSTs to
  `{baseUrl}/chat/completions` with `stream: true`, `stream_options.include_usage: true`,
  `temperature: 0.75` and `max_tokens: 16384` (`spec/api-defaults/README.md`), plus
  `Authorization: Bearer <key>` when a key exists.
- SSE `data:` frames are parsed as they arrive and forwarded as `delta` events; `[DONE]` ends the
  stream; usage from the final (`choices: []`) frame is recorded. A gateway that ignores
  `stream=true` and returns a plain completion is accepted as a single `delta`. A stream with no
  text at all is `llm_empty_reply`, never a fake empty success.
- `LlmReplyGenerator` builds the system prompt from **server** state only:
  `:agentcore` `PersonaPromptScaffold` supplies the normative Chinese sections and their order
  (`身份 → 世界 → 此刻 → 心情 → 话风 → 关系 → 你能做什么 → 边界`); the server adds the persona
  profile (`traits` / `likes` / `behaviors` / `quirks`), `worldJson`, `speechStyleJson`, the
  persona card (`age` / `role` / `relation` / `address` / `bond`), the current activity
  (`activityJson`) and the multi-dimensional mood. History is the last 40 messages; a message with
  attachments gets a "(you cannot see the image content)" note instead of pretending to see it.
  The 0.75/16 384 defaults are for chat; the background calls intentionally override them
  (evolution evaluation 0.3/900, mood judgement 0.3/400, proactive opener 300 tokens) because they
  ask for one short JSON object / one sentence. The 1M context window is only an upper bound: chat
  keeps at most 40 messages and drops the oldest if the rough token estimate would exceed it.
- Failures are SSE `event: error` with stable codes: `llm_not_configured`, `llm_invalid_config`,
  `llm_unauthorized` (401/403), `llm_rate_limited` (429), `llm_upstream_error`, `llm_network_error`,
  `llm_timeout`, `llm_empty_reply`, `llm_invalid_response`. There is **no Echo fallback**: without a
  config the stream is `accepted` then `error`, and no assistant message is persisted. Text that was
  already streamed before an upstream failure **is** persisted (and then `error` is sent) — the same
  "generation survives a client disconnect" path as before.
- **The tool loop runs on the server** (0.13). `LlmReplyGenerator` speaks OpenAI
  function-calling: the `tools[]` array comes from `tools/ServerToolCatalog`, the model's
  `tool_calls` are executed by `tools/ToolExecutor`, the results are fed back as `role=tool`
  messages, and the round repeats **at most 3 times** (the last round is sent without tools so the
  model has to answer with what it already has). Every call emits `event: tool`
  (`running` → `done` + a one-line summary) and is persisted as `messages.toolTraceJson` in the
  client's `ToolTraceItem` shape. `# 你能做什么` is generated from the same registry, so the prompt
  can never promise a tool the server cannot execute. See
  [Standalone operation, tool loop, stickers/diary and backup v6](#standalone-operation-tool-loop-stickersdiary-and-backup-v6-013).

### Proactive messages (`ProactiveScheduler` + LLM)

`Main` now starts the existing `ProactiveScheduler` (one tick every 60 s, daemon thread) against
`ProactiveTicker`/`StoreProactiveSource`/`StoreProactiveSink`; the HTTP `POST /v1/proactive/tick`
remains the debug/test entry point. When a tick fires:

1. `LlmProactiveTextSource` asks the model for **one line** using the persona prompt, the
   multi-dimensional mood, the current activity, the last 20 messages and a "you want to reach out"
   instruction ("no 在吗, one sentence, no narration");
2. the text is persisted through `FileStore.appendProactiveMessageIfAllowed` as
   `role=ASSISTANT, source=PROACTIVE` (the daily cap and cooldown are re-checked inside the store
   lock, so two concurrent ticks cannot both write);
3. a `message.created` event is appended to `events.jsonl`, so `GET /v1/events` delivers the
   proactive message to a foreground client.

If the model fails or returns nothing, the tick is recorded as `generation_failed`: **nothing is
persisted and no fixed template is sent** — a generic "忽然想起你" line is worse than staying quiet.
Today's counter / last-fired timestamp are stored in `proactive_state.json` (since 0.12.0).

### Persona evolution (server-side)

| Method | Path | Meaning |
| --- | --- | --- |
| `POST` | `/v1/personas/{id}/evolution/evaluate` | recent 12 messages + persona + current mood → model JSON → `sanitize` → `pending_patches.json` |
| `GET` | `/v1/personas/{id}/evolution/pending` | pending patches (expired ones are pruned on read) |
| `POST` | `/v1/personas/{id}/evolution/{patchId}/accept` | merge the four fields + `version + 1`, write `personas.json`, delete the patch |
| `POST` | `/v1/personas/{id}/evolution/{patchId}/reject` | delete the patch, persona untouched |

Rules are the same as the client's (`PersonaEvolutionRules`): only `traits` / `likes` /
`behaviors` / `quirks`; at most 3 changes per evaluation (≤2 add, ≤1 remove); confidence ≥ 0.6;
value ≤ 40 chars; no duplicate adds / no removing values that do not exist; empty patch is best.
Patches expire after **7 days** (`410 patch_expired` and the row is deleted).
`pending_patches.json` is server-side review state and is **not part of a v6 backup** (backups carry
personas/sessions/messages/memories/presets/settings/media/stickers/diaryNotes/agentIntents; an
unaccepted proposal is disposable). Unknown patch → 404
`patch_not_found`; a missing LLM config on an explicit evaluate is `503 llm_not_configured` (not a
silent "no changes"). `accept` keeps the client's field shape: a string column stays
`"[\"…\"]"`, an actual JSON array stays an array. Nothing outside the four fields is touched
(name/description/world are the user's).

### Multi-dimensional mood

`moods.json` now carries `valence` (-2..2), `arousal` (0..5), `dominance` (0..5) and `reason`
alongside `label` / `intensity` (`spec/mood/README.md` §1). Old files without them are migrated on
read (valence from the label via `MoodLexicon`, arousal from intensity, dominance = 3). The fields
appear in `GET /v1/state` and in `mood.changed` payloads (unknown fields are ignored by the
existing clients), and the chat prompt injects the `# 你的心情` block with all dimensions plus 1–2
behaviour hints ("don't state the numbers, don't say 我的情绪是…").

`POST /v1/personas/{id}/mood/evaluate` asks the model with "previous mood + last 8 messages" and
applies the rules: ranges are clamped, a single change is limited to **±2 per dimension**, and a
patch with no noticeable change is **not written**. Failure or no config falls back to decay — one
step per hour toward `0 / 2 / 3` — and still answers `200` with `"source": "decay"` (the spec asks
for a quiet fallback, not an error). `"source"` is `llm` / `decay` / `unchanged`.

**Automatic trigger (0.13):** `automation/AutoTriggers` runs **after every chat turn**
(`ChatService` → `onChatCompleted`), independent of any client connection: mood is judged every
**4 user messages** (or after 24 h with new messages), persona evolution every **20** (or 24 h).
The cursors live in `auto_state.json`, so a restart does not re-judge the same history. Failures
never break the chat: a mood failure falls back to time decay, and evolution with no LLM config
leaves its cursor alone (so the backlog is evaluated once a key exists).

### curl examples

```bash
# chat: real streamed generation (delta frames come from the model)
curl -N -X POST http://127.0.0.1:8788/v1/sessions/seed-session-linshen/messages \
  -H 'Content-Type: application/json' \
  -d '{"clientMessageId":"demo-llm-1","content":"你在干嘛呢？"}'

# proactive heartbeat (debug): fires one evaluation now; the body of the message comes from the model
curl -s -X POST http://127.0.0.1:8788/v1/proactive/tick -d '{}'

# persona evolution: evaluate, list, accept / reject
curl -s -X POST http://127.0.0.1:8788/v1/personas/seed-linshen/evolution/evaluate
curl -s http://127.0.0.1:8788/v1/personas/seed-linshen/evolution/pending
curl -s -X POST http://127.0.0.1:8788/v1/personas/seed-linshen/evolution/<patchId>/accept
curl -s -X POST http://127.0.0.1:8788/v1/personas/seed-linshen/evolution/<patchId>/reject

# mood: judge from the recent conversation (falls back to decay without a config)
curl -s -X POST http://127.0.0.1:8788/v1/personas/seed-linshen/mood/evaluate
```

## Standalone operation, tool loop, stickers/diary and backup v6 (0.13)

The point of 0.13 is that **`agentd` is a service, not a helper the client has to keep alive**: with
no client connected at all it still runs the whole product loop, and its backup file is the *same
file* the Android/iOS clients write.

### What runs without a client

| Driver | Cadence | What it does | Where the state lives |
| --- | --- | --- | --- |
| **proactive heartbeat** (`ProactiveTicker.start()`, started by `Main`) | every 60 s | decides whether he reaches out (sleep/DND/cooldown/daily cap/activity gate), writes the message with the model, appends `message.created` | `proactive_state.json`, `sessions/*/blocks/*.jsonl` |
| **diary reminders** (same tick) | every 60 s, each note fires once | `diaryNotes[]` rows whose `remindAt <= now` become one proactive message; `remindedAt` is stamped so it never repeats; more than 24 h late ⇒ cleared **without** a message | `diary_notes.json` |
| **deferred intents** (same tick) | every 60 s, each intent fires once | `defer_reply` left a `PENDING` row; when `earliestAt <= now` it becomes a proactive message (body by the model, fallback line if generation fails); `latestAt + 24 h` ⇒ `EXPIRED` | `agent_intents.json` |
| **automatic mood** (`AutoTriggers`, after every chat turn) | every **4** user messages, or 24 h with new messages | asks the model for `{label,valence,arousal,dominance,intensity,reason}`, clamps to ±2, writes `moods.json` | `auto_state.json` (cursor) |
| **automatic evolution** (same hook) | every **20** user messages, or 24 h with new messages | writes a **pending** patch (`pending_patches.json`); the persona itself is only changed by `accept` | `auto_state.json` (cursor) |

A client only *syncs*: `GET /v1/state`, `GET /v1/sessions/{id}/messages`, `GET /v1/events`. Nothing
above requires a foreground app, a foreground service, or a push token.

Two deliberate limits: reminders and deferred intents **do not consume** the proactive daily cap
(they are user-scheduled, not him feeling chatty), and the automatic hooks run **in the request
thread after `done`** — at most one extra model call every 4/20 messages, in exchange for a
completely deterministic, testable path.

### Tool loop (`tools/ServerToolCatalog` + `tools/ToolExecutor`)

```
round 1: messages + tools[]  → text and/or tool_calls
         each call: emit `tool running` → execute → emit `tool done` → append assistant(tool_calls) + tool(result)
round 2: ask again …                 (max 3 rounds; the 3rd is sent WITHOUT tools)
round 3: the model has to answer with what it already has
```

| Tool | What the server really does | Limits / notes |
| --- | --- | --- |
| `stay_silent` | ends the turn: **no assistant message**, session marked read, `done.silent = true` | never leaves a trace bubble in the UI; not for avoiding a serious topic |
| `defer_reply` | writes a `PENDING` intent (`earliestAt = now + minutes`, `latestAt = +30 min`) | minutes clamped to 5–720; the heartbeat turns it into a message later |
| `set_mood` | `moods.json` with `label/valence/arousal/dominance/intensity/reason` | ranges clamped (`-2..2`, `0..5`, `1..5`) |
| `set_activity` | `personas.activityJson` (`kind/activity/until/availability`) | minutes clamped 15–480; expires on read |
| `diary` | `add` / `list` / `remove` on `diary_notes.json` | content ≤ 2000 chars; `remindAt` or `remindInMinutes` (1–43200) |
| `remember` | new row in `memories.json` | importance 1–5 → 0–100 |
| `recall` | keyword/tag search over that persona's memories (no embeddings) | returns the hits back to the model |
| `web_search` | `WebSearchService.search` with the **server** provider/base/key | count 1–12; the key never appears in model arguments |
| `web_fetch` | `WebSearchService.webFetch` (public http(s) only, `WebUrlPolicy`) | text capped at 8000 chars by the search layer |
| `sticker` | `send` attaches an existing sticker to the assistant message; `list` shows "my stickers"; **`make`** (0.14.1) composes a new GIF from a base (`baseStickerId` / public `url` / built-in blank), an optional `overlayStickerId` and `text` at `TOP`/`CENTER`/`BOTTOM`, stores it as `kind=STICKER` media and returns `做好了（id=…）` | output obeys `spec/stickers`: ≤512 KB, long edge ≤320 px, ≤60 frames, ≤6 s; static JPEG/PNG and animated GIF inputs; downloads go through `WebUrlPolicy` and are capped at 8 MiB |
| `listen` | **0.14.1.** Reads the newest (or `audioId`) voice message of the session, base64-encodes it and feeds it back to the model as an `input_audio` content part (`spec/audio-video`) | audio ≤8 MB; blocked/deleted/missing media fail explicitly; a model that cannot hear audio surfaces “这个模型听不了语音/看不了图片” |
| `watch` | **0.14.1.** Extracts ≤8 JPEG keyframes (long edge ≤1024 px) from the newest (or `videoId`) video with the system `ffmpeg` and feeds them back as `image_url` parts | requires `--ffmpeg` / `YAP_FFMPEG` / `ffmpeg` on `PATH`; without a decoder it answers **“服务器没有视频解码器，看不了视频”** — never a fabricated frame; video ≤64 MB |
| `update_persona` | **0.14.1.** Files a **pending patch** (`pending_patches.json`) for `traits` / `likes` / `behaviors` / `quirks` additions & removals and/or `address` (how he calls you, ≤20 chars). The persona is only changed when the user accepts the patch (`/v1/personas/{id}/evolution/{patchId}/accept`), exactly like evolution proposals | never touches the user-written persona text (name/description/world/voice); ≤3 changes per call via `PersonaEvolutionRules.sanitize`; a no-op proposal is refused, not stored |
| `set_quiet_hours` | **0.14.1.** Writes `personas.quietSleepMinutes` / `quietWakeMinutes` (local minutes after midnight) and `version + 1`; the proactive engine’s `SleepPolicy` then treats that window as sleep (cross-midnight works: `23:30–08:00`) | `start`/`end` accept `HH:MM`, `H:MM`, `HHmm`, `23点30`; equal or unparsable times are refused (no guessing). `null`/absent = no quiet window |

The prompt lists **exactly** the registered tools — `ServerToolCatalogTest` fails if that ever
drifts. Since 0.16 `think` is registered too: it is not a prompt trick on this side, it raises the
turn's `reasoning_effort` to `high` (see the 0.16 section).

Limits and failure behaviour: `MAX_TOOL_ROUNDS = 3`, a 2-minute wall-clock deadline for the whole
loop (a slow search cannot hold the turn forever), every tool **returns** a result instead of
throwing (a bad argument becomes `ok:false` and the model can correct itself), and the execution
never depends on the client socket — a disconnected client still gets tool effects persisted.

`messages.toolTraceJson` uses the client's `ToolTraceItem` shape
(`[{name,title,subtitle?,detail,ok}]`), written at call time, so the "he searched for two pages"
card renders on both clients without translation.

### Stickers and diary

New server tables (plain JSON, same atomic-write rules as everything else):

```text
stickers.json      {"stickers":[{id,ownerUserId,personaId,file,mime,width,height,frames,bytes,source,createdBy,createdAt}]}
diary_notes.json   {"notes":[{id,personaId,content,createdAt,remindAt,remindedAt,updatedAt}]}
agent_intents.json {"intents":[{id,personaId,sessionId,kind,earliestAt,latestAt,note,hint,status,createdAt,sentAt}]}
auto_state.json    {"states":[{personaId,moodCursor,moodEvaluatedAt,evolutionCursor,evolutionEvaluatedAt,userMessagesSeen}]}
```

Sticker bytes use the media table (`kind=STICKER`): `Sticker.id` is the media id for
server-created rows, and `file` is the bare file name (the same string the backup uses as
`fileName`). Imported client rows may have a different `id` than the media row; the importer
normalises it by `fileName`, and `FileStore.stickerMediaId` resolves it either way.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/v1/stickers?personaId=&limit=` | `{"serverTime","stickers":[…]}`; newest first; `file`/`fileName` are the bare name, `mediaId`/`url` fetch the bytes |
| `POST` | `/v1/stickers` | one request with `{mime,data(base64),personaId?,width,height,frames,source,createdBy}` → `{"created":true,"sticker":{…}}`; or `{"mediaId":"…"}` for an already uploaded `kind=STICKER` media |
| `DELETE` | `/v1/stickers/{id}` | `{"deleted":true,"id":…}`; row + file removed; unknown ⇒ 404 |
| `GET` | `/v1/personas/{id}/diary?limit=&since=` | `{"personaId","notes":[…]}`; newest first; `since` filters on `updatedAt` |
| `POST` | `/v1/personas/{id}/diary` | `{id?,content,remindAt?|remindInMinutes?}` → `{"note":{…}}`; no `id` = create, `remindAt:null` clears the reminder; changing the content does **not** reset `remindedAt` |
| `DELETE` | `/v1/personas/{id}/diary/{noteId}` | `{"deleted":true,"id":…}`; wrong persona ⇒ 404 |

Why the base64 upload is capped: the JSON parser refuses any single string longer than 256 KiB
(security hardening in `:agentcore`), so inline uploads cover files up to ≈192 KiB — which is what
a compressed 320 px emoji actually weighs. Bigger files use `POST /v1/media?kind=STICKER` (raw
bytes, 4 MiB route) and then `POST /v1/stickers {"mediaId":…}`.

### Backup v6 (interoperable with the clients)

`GET /v1/backup` now writes **the client's v6 shape** (`formatVersion: 6`), because a backup you
cannot hand to the other side is not a backup. The mapping (`store/BackupV6.kt`) is mechanical but
was the core of this task:

| Server state | v6 section | Field mapping worth knowing |
| --- | --- | --- |
| `personas.json` + `moods.json` + `media.json` | `personas[]` | `avatarUrl` → `avatarUri` (bare file name of the media); `moods.json` → `mood/moodIntensity/moodReason/moodUpdatedAt`; client columns (`traits`, `likes`, `behaviors`, `personaCardJson`, `worldJson`, `speechStyleJson`, `activityJson`, …) are stored verbatim in the persona's extra fields and written back; `nameEn` is written as a server extension |
| `sessions.json` | `sessions[]` | `unread` → `unreadCount`; `apiConfigId/model/pinned/summary/summarizedUpTo/summaryAt/*Override/lastPersonaVersion` come from the session's extra fields |
| `sessions/*/blocks/*.jsonl` | `messages[]` | `attachments` (`/v1/media/<id>`) → `attachmentsJson` (`[{"uri":"<fileName>","mime":…,"kind":…}]`); `toolTraceJson` preserved; `status: COMPLETE`, `updatedAt = createdAt` |
| `memories.json` | `memories[]` | `tags` is written as a **JSON string** (the client's Room column shape), not an array |
| `media.json` + `media/<id>.<ext>` | `media[]` | `fileName` (bare name), base64 `data` inline once; `sha256` is an extra key the clients ignore |
| `stickers.json` | `stickers[]` | `file` **and** `fileName` (Android/iOS aliases), bytes only in `media[]` |
| `diary_notes.json` | `diaryNotes[]` | seven columns, `remindAt`/`remindedAt` omitted when null |
| `agent_intents.json` | `agentIntents[]` | `PENDING`/`SENT`/`EXPIRED` |
| `settings.json` | `settings` | the client's nine whitelist keys are always written (defaults filled in); server-only keys (`userAvatarUri`, `autoTranslatePersona`) are appended as extensions |
| `proactiveRules[]` / `memoryConsolidations[]` | same | **0.14.1: preserved verbatim.** The server does not interpret them; an import stores the raw JSON in `client_extras.json` and an export writes it back untouched (an unknown future top-level section is treated the same way). With no client data the arrays stay `[]` so the key set still matches the clients. `pending_patches.json` is not exported |
| server-only data | `extensions` | **0.14.1:** `{mediaSkipped, mentalActivities[], sessionMemory[]}` — a single optional object the clients ignore. The old top-level placement (`mediaSkipped` / `mentalActivities` / `sessionMemory`) is still **accepted on import** (0.14.0 backups keep importing); exports only write `extensions` |

`POST /v1/backup` accepts **both** envelopes:

```bash
# A. the historical envelope (mode must come before data, because the body is parsed as a stream)
printf '{"mode":"replace","data":' > /tmp/body.json && cat backup-v6.json >> /tmp/body.json && printf '}' >> /tmp/body.json
curl -X POST http://127.0.0.1:8788/v1/backup -H 'Content-Type: application/json' --data-binary @/tmp/body.json

# B. the raw client export, exactly as downloaded from the phone (mode defaults to merge)
curl -X POST "http://127.0.0.1:8788/v1/backup?mode=replace"   -H 'Content-Type: application/json' --data-binary @fixtures/backup/android-export-v6.json
```

`v1`–`v5` files still import (unknown/absent sections become empty). The import resolves every
`avatarUri` / `attachmentsJson[].uri` / `stickers[].fileName` back to a real `media` row, so history
attachments and avatars keep working after a restore. `BackupV6Test` imports both client v6
fixtures, re-exports, re-imports and compares every section byte-for-byte.

### curl examples

```bash
# stickers: upload one (base64), list, delete
curl -s -X POST http://127.0.0.1:8788/v1/stickers -H 'Content-Type: application/json'   -d "{\"personaId\":\"seed-linshen\",\"mime\":\"image/gif\",\"width\":1,\"height\":1,\"frames\":1,\"data\":\"$(base64 < emoji.gif)\"}"
curl -s 'http://127.0.0.1:8788/v1/stickers?personaId=seed-linshen&limit=30'
curl -s -X DELETE http://127.0.0.1:8788/v1/stickers/<stickerId>

# diary: write one with a reminder, list, remove
curl -s -X POST http://127.0.0.1:8788/v1/personas/seed-linshen/diary   -H 'Content-Type: application/json' -d '{"content":"记得周四给妈妈打电话","remindInMinutes":60}'
curl -s http://127.0.0.1:8788/v1/personas/seed-linshen/diary
curl -s -X DELETE http://127.0.0.1:8788/v1/personas/seed-linshen/diary/<noteId>

# one heartbeat: proactive decision + due diary reminders + due deferred intents
curl -s -X POST http://127.0.0.1:8788/v1/proactive/tick -d '{}'
# -> {"now":…,"fired":1,"decisions":[{"personaId":…,"sessionId":…,"messageId":…,"reason":"diary_reminder"}],"skipped":[…]}
```

## Experience features: blocking, multi-message turns, inner monologue and session memory (0.14)

0.14 的规则由 `spec/experience/README.md` 定义，服务端和客户端跑**同一套**规则。服务端这一侧
不依赖客户端连接：拉黑、心理活动、会话记忆都由 `ChatService` 的收尾与 `ProactiveTicker`
的每一拍推进（`experience/ExperienceService`）。

### What runs without a client (0.14 additions)

| Driver | Cadence | What it does | Where the state lives |
| --- | --- | --- | --- |
| **block expiry** (every chat turn + heartbeat) | on read and once per tick | clears `blockedUntil`/`blockReason` once the moment has passed; reading already treats an expired block as unblocked | `personas.json` |
| **mental activities**（心理活动，same tick + after chat） | every **3 minutes** or every **3 turns** inside the 10-minute active window; **mandatory** when arousal ≥ 4 or a mood dimension moved ≥ 2 | one private thought (≤ 60 chars, never sent), a mood nudge of at most **1 step per dimension**, injected into the next prompt as `# 你刚才心里想的` | `mental_activities.json` (newest 50 per persona) |
| **session memory**（会话记忆，same tick + after chat） | every **5** non-blocked user messages → `shortSummary` (≤ 200 chars); every **30** → `longSummary` (≤ 500 chars) | injected as `# 你的备忘录` (short before long) in the **variable** area, never written into the persona | `session_memory.json` |
| **pending block proposal** (`block_user`, per chat turn) | expires after 10 minutes | two-round confirmation state (`personaId/sessionId/userMessageId/hours/reason/proposedAt`) | `block_proposals.json` |

A failed background generation keeps the old state (`mental_activities.json` / `session_memory.json`
are not touched), records one stderr line, and does not block the reply.

### `block_user`（拉黑）: two rounds, then it is real

```
turn N   : block_user{hours: 6, reason: "想清静一会儿"}
           → pending_confirmation, nothing is stored on the persona
turn N+K : (at least one new user message in between)
           block_user{hours: 6, confirm: true} → personas.blockedUntil = now + 6h
```

- `hours` is clamped to 1–24; a proposal older than 10 minutes is discarded (the next call starts over);
- confirming **in the same turn** is refused — the proposal remembers the user-message id, and it must differ;
- while blocked: the user can still send. The message is stored with `blocked: true`, **no model call** happens,
  the stream is `accepted → done{silent:true}`, and the session is marked read;
- blocked messages are **permanently** excluded from the model context (`latestMessages(..., includeBlocked = false)`),
  including after the block expires; they still appear in `GET /v1/state`, paging and backups so the client can
  show the red exclamation mark and “对方已将你拉黑”;
- expiry is automatic and silent (no notification, no catch-up); there is **no** `unblock` tool and the user cannot
  unblock manually.

DTO additions (additive; clients ignore unknown keys): `personas[].blockedUntil` / `blockReason` in
`GET /v1/state` and CRUD responses, `messages[].blocked` (always written, `false` for everything else).

### Multi-message turns（连发）and the SSE shape

`send_message{text}` sends one real assistant message per call (≤ 120 chars, at most **4 per turn**;
the 5th returns a tool failure). A reply that does not use the tool is split by **blank lines** into 1–3
messages (extra paragraphs are folded into the last one, never dropped). No placeholder / “…” message
is ever stored: `stay_silent` and “tool-only” turns produce `done{silent:true}` and nothing else.

SSE for a multi-message turn (contract confirmed with both clients):

```
event: accepted        data: {"userMessageId":"…","createdAt":…}
event: delta           data: {"text":"…"}          ← typing indicator / legacy single-bubble path
event: tool            data: {"name":"send_message","status":"done","summary":"发出第 1 条"}
event: typing.started  data: {"sessionId":"…","personaId":"…","at":…}      ← 0.14.1, once per turn
event: message.created data: {"message":{…full message DTO…}}  × N, in order (at each visibleAt)
event: typing.stopped  data: {"sessionId":"…","personaId":"…","at":…}      ← 0.14.1, once per turn
event: done            data: {"assistantMessageId":"<last id>","usage":{…}}
```

- **one `done`** at the end (`assistantMessageId` = the last message, usage on its row); single-message
  turns keep the old shape (`accepted → delta* → done`) so older clients are unaffected;
- each `message.created` payload is identical to the `GET /v1/events` `message.created` payload;
- usage/tool trace are recorded on the last message; every message points `replyTo` at the same user message;
- idempotent replay of such a turn re-sends every `message.created` and then **one** `done`.

### `recall` audit (0.14 fixes)

Three real bugs were fixed in `ToolExecutor.recall`:

1. the candidate set was `memories(limit = 500)` — older memories silently disappeared; it now scans the
   whole per-persona list (the index is in memory, no disk scan);
2. the whole query was used as one substring (`"那家店 怕冷"` could never match); it is now tokenised on
   whitespace/punctuation and any token counts, with the exact phrase scoring higher;
3. ranking was importance-only; it now scores phrase hits (3) + token hits in content (2) / tags (1), then
   importance and recency, deduplicates identical content, clamps the query to 200 chars and the rendered
   answer to ≤ 2,000 chars.

### Backup v6 additions

`GET /v1/backup` keeps the client v6 key set and **adds** (clients ignore unknown keys):

| Where | Field | Meaning |
| --- | --- | --- |
| `personas[]` | `blockedUntil`, `blockReason` | optional; present only when blocked |
| `messages[]` | `blocked` | only written when `true` (permanent context exclusion) |
| top level | `mentalActivities[]` | id/personaId/sessionId/at/thought/mood snapshot |
| top level | `sessionMemory[]` | sessionId/personaId/short+long summaries and their cursors |

Import accepts v1–v6 as before; the new sections are optional (an old v6 file without them still imports),
references are validated (persona/session must exist, session memory must match the session's persona),
and `replace` clears these tables while `merge` overwrites by id / sessionId. The import result reports
`mentalActivities` / `sessionMemories` counts as additive fields.

### Tests for 0.14 (`:agentd:test`)

| Test class | What it pins |
| --- | --- |
| `BlockUserTest` | two-round confirmation, same-turn refusal, 10-minute expiry, hours clamp, permanent context exclusion, automatic expiry, state/message DTO |
| `MultiSendTest` | SSE order with multiple `message.created` + single `done`, independent rows/events, 4-per-turn cap, 120-char clamp, blank-line splitting, silent turns, idempotent replay |
| `MentalActivityTest` | active window, 3 min / 3 turn triggers, arousal/Δ≥2 mandatory, 1-step clamp, prompt injection order, 50-row cap, heartbeat independence, failure writes nothing |
| `SessionMemoryTest` | 5/30-message throttles, blocked messages excluded, failure keeps old summary, blank/overlong answers, short-before-long injection, persistence across reopen |
| `ExperienceBackupTest` | export fields/sections, round trip, merge keeps state, old client v6 file still imports |
| `ToolExecutorTest` | recall audit: >500 memories, tokenised query, dedupe/ranking, truncation, persona isolation |

## 0.14.1 fixes (server)

0.14.1 closes the five server-side gaps that 0.14 left open. Nothing else in the HTTP contract
changed: no version bump, no new dependency, same 1 MiB / 4 MiB / 8 MiB / 256 MiB body limits.

### Backup symmetry (`spec/experience` §11)

- **Same key set as the clients' v6 file.** `GET /v1/backup` still writes exactly the client keys;
  the server-only data now lives in one optional `extensions` object
  (`{mediaSkipped, mentalActivities[], sessionMemory[]}`) that the clients ignore. The old
  top-level placement is still accepted on import (0.14.0 files keep working).
- **Client-only sections round-trip verbatim.** On import, `proactiveRules[]`,
  `memoryConsolidations[]` and any *unknown* top-level section are stored as raw JSON in
  `<data>/client_extras.json`; an export writes them back unchanged (byte-for-byte field order,
  no re-shaping). `merge` overlays a section only when the incoming backup mentions it;
  `replace` clears the stored extras (the backup is the new state).
- **Client fields are never replaced by server extension keys.** Unknown persona/session/message
  fields still travel in the rows' `extraFields`; the new `extensions` object is additive only.
- Guard rails: at most 64 client sections / 8 MiB in total are kept (an oversized section is
  skipped with one stderr line instead of failing the import), and backups with a duplicate
  top-level key are rejected by the parser.
- `BackupSymmetryTest` imports both v6 fixtures, exports, re-imports and re-exports and compares
  the client sections field by field; it also proves two round trips are idempotent and that a
  future unknown section survives.

### `sticker.make` (server-side compositing)

`sticker` gained the `make` action. The renderer is `sticker/StickerComposer` + a **verbatim port**
of the clients' GIF89a codec (`sticker/StickerGif.kt`, same algorithm, only the package changed —
`:agentd` still has zero third-party dependencies):

- static JPEG/PNG bases are decoded with `javax.imageio`, GIF bases with the ported codec;
- every frame is composited (base frame + optional overlay + caption), then the whole sequence is
  re-encoded as GIF89a — an animated base really keeps its per-frame delays;
- the output is pushed through the `spec/stickers` limits with a degradation ladder
  (colours 256→128→64→32→16, canvas 320→240→200→160, frame decimation) until it fits ≤512 KB;
- binaries go through the normal media table (`kind=STICKER`), so `GET /v1/media/<id>` serves them
  and the sticker row is the media id; `url` sources go through `WebUrlPolicy` and are capped at
  8 MiB. `StickerComposerTest` covers text, overlay, animated GIF frame count, size/edge caps and
  the failure codes.

### `listen` / `watch` (`spec/audio-video`)

Both tools are now registered and executed:

- `listen(audioId?)`: resolves the explicit id or the newest audio attachment of the session
  (blocked messages are excluded), enforces the 8 MB cap and sends the bytes as an
  `input_audio` content part. The tool result and the audio are fed back to the model in one
  follow-up `user` message (tool messages can only carry text).
- `watch(videoId?)`: resolves the newest/explicit video, enforces the 64 MB cap, asks the
  injectable `FrameExtractor` for ≤8 JPEG frames (long edge ≤1024) and sends them as `image_url`
  parts. The production extractor shells out to `ffmpeg` (`--ffmpeg` / `YAP_FFMPEG` / `PATH`,
  30 s timeout, temp dir cleaned up); with no decoder the tool fails with
  **「服务器没有视频解码器，看不了视频」** and the model is told to say it cannot watch.
- `LlmClient` serialises `content` as an OpenAI content array (`text` / `image_url` /
  `input_audio`) only for messages that actually carry parts; everything else stays a plain
  string. If the provider rejects a modality (`400`, detail mentions audio/image/modality) the
  failure is reported as “这个模型听不了语音/看不了图片”.
- `MultimodalToolsTest` uses a fake extractor (and a fake SSE provider) for the deterministic
  cases; the real-ffmpeg smoke test is `Assume`-skipped when `ffmpeg` is absent.

### `update_persona` / `set_quiet_hours`

- `update_persona` files a **pending patch** (the same `pending_patches.json` + accept/reject flow
  as 0.13 evolution, so the user keeps the final say). Allowed: `traits` / `likes` / `behaviors` /
  `quirks` (add/remove, ≤3 per call through `PersonaEvolutionRules.sanitize`) and `address`
  (`personaCardJson.voice.address`, ≤20 chars). `accept` applies the four fields **and** the
  address, then bumps `version`; `reject` leaves the persona untouched. Nothing else (name,
  description, world, speech style) can be changed by the model. The capability prompt already
  lists both tools because it is generated from the registry.
- `set_quiet_hours` writes `quietSleepMinutes` / `quietWakeMinutes` directly (a behaviour switch,
  not “who he is”) and bumps the version. `QuietHours` is the single implementation of the
  cross-midnight `[start, end)` rule and of the loose `HH:MM` / `HHmm` / `23点30` parsing; the
  proactive ticker feeds the window into `SleepPolicy`, so a persona inside its quiet window
  reports `asleep` and does not send proactive messages. `PersonaToolsTest` pins the formula,
  boundaries, timezone conversion and both tools' failure modes.

### Server message queue and typing (`spec/experience` §8)

- **The server decides the delay at enqueue time:**
  `visibleAt = now + clamp(0.4 + chars × 0.025 + mood adjustment, 0.4, 4.0)` seconds
  (`delivery/TypingDelay`, identical to §5; low arousal / unhappy → slower, high arousal →
  faster). Multi-message turns are staggered: message _n_ is due at message _n−1_'s `visibleAt`
  plus its own delay.
- **Foreground online** (a `/v1/` request within the last 60 s, `delivery/ClientPresence`):
  one `typing.started` at enqueue → one `message.created` per message at its `visibleAt` →
  one `typing.stopped` after the last one. The events are written to `events.jsonl` (so
  `GET /v1/events` sees them) and streamed on the POST SSE at the same moments; the final `done`
  comes after `typing.stopped`.
- **Offline:** no typing events; messages are stored with `visibleAt = createdAt` and their
  `message.created` event is emitted immediately, so the next sync shows them directly.
- **`GET /v1/sessions/{id}/messages` never waits.** It returns the stored rows (now with
  `visibleAt`) regardless of the queue, so history/sync cannot be held hostage by a delay.
- **Persistent per-session FIFO:** pending deliveries go to `<data>/message_queue.json` before
  the wait starts, deliveries of one session are serialised by a per-session lock, and a
  background recovery thread re-emits anything that was in flight when the process was killed
  (worst case a duplicate `message.created`, which clients dedupe by message id).
- `ChatService` uses the queue only when `AgentdServices.delivery` is set; `Main` always sets it.
  Unit tests that assert the 0.14 SSE shape keep `delivery = null` (immediate path), while
  `DeliveryTest` injects a fake clock + fake sleeper and asserts the exact per-message timing.

### Unified block protocol (`spec/experience` §9)

- `blockProposalJson` has exactly seven fields:
  `{"proposalId","personaId","hours","reason","userMessageId","proposedAt","expiresAt"}`
  (`expiresAt = proposedAt + 600000`; no `sessionId`, no message-count field).
  It is stored in `block_proposals.json`, exposed as a string on `personas[].blockProposalJson`
  in `GET /v1/state` and the CRUD responses, and is the same shape the clients keep in
  `personas.blockProposalJson`.
- An unexpired proposal is reused (not renewed) by a repeated non-confirming call; `expiresAt`
  is the **moment it becomes invalid** (`now >= expiresAt` ⇒ expired, matching the clients).
- Confirmation requires a later turn with a **different `userMessageId`**; the proposal's `hours`
  are what gets applied (a `hours` in the confirming call is ignored). Tool results are the fixed
  JSON `{"status":"pending_confirmation","expiresAt":…}` / `{"status":"blocked","blockedUntil":…}`;
  refusals are `{"status":"rejected","code":"no_pending_proposal|proposal_expired|need_more_user_messages|already_blocked"}`
  with `ok = true` (a protocol-level refusal, not a broken tool — same as both clients).
- `BlockUserTest` pins the field set, the expiry boundary, the tool JSON, the persona DTO and the
  permanent context exclusion.

### Tests for 0.14.1

Added: `BackupSymmetryTest` (7), `DeliveryTest` (10), `PersonaToolsTest` (9),
`MultimodalToolsTest` (9), `StickerMakeTest` (5), `StickerComposerTest` (10),
`MediaUnderstandingTest` (15), one `--ffmpeg` case in `MainOptionsTest`, and the updated
`BlockUserTest` / `ServerToolCatalogTest` / `ToolExecutorTest` expectations. The whole
`:agentd:test` suite is now **668 tests** (0 failures, 1 skipped: the real-`ffmpeg` smoke test on
a machine without ffmpeg); `:agentd:test` + `:agentd:build` pass on JDK 17 with `--offline`.

## Errors

Every error is an object with a Chinese `message` and a stable `code`, plus a 4xx/5xx status:

```json
{"error":{"message":"字段 now 必须是整数","code":"invalid_argument"}}
```

| Status | `code` | When |
| --- | --- | --- |
| 400 | `empty_body` | No request body. |
| 400 | `invalid_json` | Body is not valid JSON (too deep / too long / duplicate key / non-finite number also land here). |
| 400 | `invalid_body` | Body is valid JSON but not an object; also used when the body could not be read completely. |
| 400 | `missing_field` | A required field is absent. |
| 400 | `invalid_argument` | A field has the wrong type / can't be parsed. |
| 400 | `unknown_tool` | `/v1/core/tool-args` got an unsupported tool (the tool name is **not** echoed back). |
| 400 | `invalid_backup` | Backup export body failed validation (duplicate ids, missing persona/session references, bad role/importance…). No data was written. |
| 401 | `unauthorized` | `--token` is set and the request has no/incorrect `Authorization: Bearer …`. |
| 404 | `not_found` | Unknown route / media / persona (the path is not echoed back). |
| 405 | `method_not_allowed` | Known route with the wrong method. |
| 408 | `request_timeout` | The request body did not arrive within `READ_TIMEOUT_MS`. |
| 409 | `unsupported_backup_version` / `media_in_use` | Import of a `formatVersion` outside 1–6 is refused instead of half-imported; `DELETE /v1/media/{id}` is refused while the file is referenced (`references` count in the body) unless `?force=1`. |
| 411 | `length_required` | Request used `Transfer-Encoding: chunked`; only `Content-Length` bodies are accepted. |
| 413 | `content_too_large` | Declared or accumulated body exceeds the route cap (1 MiB JSON, 4 MiB media upload, 8 MiB message import, 256 MiB backup import), or a media file exceeds 2 MiB. |
| 429 | `rate_limited` | This source exceeded its token bucket; the response carries `Retry-After: <seconds>` (see “Rate limiting”). |
| 500 | `internal_error` | Unexpected server-side failure; the response is a fixed message plus a random `errorId`, never the exception text. |

## Why this shape

- **Zero dependencies** keeps the deployment story simple: a JDK 17 `java -jar` is enough, no
  framework, no transitive CVE surface, no version skew with the Android build.
- **One implementation, not two copies.** Before 0.11.0 the service compiled the Android source
  tree via `kotlin.srcDir("../app/src/main/java/com/yap/app/domain")` plus an exclude list of
  14 files. That worked but had two failure modes: the exclude list could drift, and `:app` /
  `:agentd` produced two separate copies of the same classes. Now the 36 portable files live in
  `:agentcore` and both modules compile the *same* classes.
- **Handler layer is a pure function** (`AgentdApi.handle(method, path, body)`) so tests cover
  every route and error without opening a port; a handful of integration tests still start a real
  server on an ephemeral port with the JDK `HttpClient` / raw sockets (slow-client, size limits,
  token).

### What stayed in `:app`

These domain files cannot leave Android and remain in `app/src/main/java/com/yap/app/domain/`:

| File | Why it can't be shared |
| --- | --- |
| `AgentActions.kt` | `kotlinx.serialization` (model action protocol). |
| `ApiCost.kt` | `kotlinx.serialization` + pricing/usage types. |
| `Attachments.kt` | `kotlinx.serialization` (attachment codec). |
| `BackupCodec.kt` | Room entities (`AgentIntentEntity`, ...). |
| `ImportanceScoring.kt` | `kotlinx.serialization` (model output parsing). |
| `LlmConcurrencyGate.kt` | `kotlinx.coroutines`. |
| `MemoryScreening.kt` | `kotlinx.serialization` (`MemoryTags`). |
| `Models.kt` | Room entities (`ChatSessionEntity`, `MessageEntity`). |
| `OpenSessionTracker.kt` | `javax.inject` (Hilt). |
| `PersonaCard.kt` | `kotlinx.serialization`. |
| `PersonaProfile.kt` | Room entity `PersonaEntity`. |
| `Recall.kt` | Depends on `MemoryScreening.MemoryRow`. |
| `ToolProtocol.kt` | `kotlinx.serialization` (tool protocol). |
| `ToolTrace.kt` | `kotlinx.serialization` (tool traces). |

Everything else (36 files: `SleepPolicy`, `DeferReply`, `ProactiveLevel`, `Heartbeat`, `Mood`,
`MoodBehavior`, `MemoryLifecycle`, `MemoryDrawer`, `ToolArgClamp`, `ChineseTerms`,
`HistoryCompression`, `LocalEmbedding`, ...) is in `:agentcore` under the same package
`com.yap.app.domain`, so Android imports and tests did not change at all.

## What this service does **not** do yet

- **No TLS of its own.** It binds to `127.0.0.1` by default and speaks plain HTTP; TLS and
  HTTP/2 belong in the reverse proxy in front of it (see “Deployment and TLS” above). Since 0.11.1
  there is also an optional shared token (`--token`), a 1 MiB request-body cap (4 MiB for media upload), a 5 s read timeout
  and 4 fixed worker threads; since 0.12.0 a per-source token bucket (`--rate-limit` /
  `--rate-burst`). A token over plain HTTP is still not a substitute for TLS, and `0.0.0.0`
  remains “trusted network / behind a proxy only”.
- **No SQL/Room.** Since 0.12.0 it *does* persist server-authoritative state (personas, sessions,
  block-paged messages, memories, presets, mood/presence, push tokens, usage, API config metadata,
  image files + media metadata, stickers, diary notes, deferred intents, automatic-trigger cursors)
  as JSON/JSONL/binaries under `--data`, and expose v6 backup export/import. It still does not touch the clients' Room database, migrate existing client data
  automatically, or store "thoughts" — and media is not decoded/re-encoded. Earlier versions only
  exposed deterministic pure functions; see
  [Server-authoritative mode](#server-authoritative-mode-服务端权威模式-0120).
- **LLM calls since 0.13 include the tool loop.** `POST /v1/sessions/{id}/messages` streams a real
  model answer, the server executes the model's tool calls (search/fetch/memory/diary/sticker/mood/
  activity/defer/stay-silent) and feeds the results back, proactive ticks write a model-generated
  opener, evolution/mood are judged automatically after every chat turn, and diary reminders /
  deferred intents fire from the heartbeat. 0.14.1 adds the four tools that were missing here:
  `listen` (audio → `input_audio`), `watch` (video → ≤8 `image_url` frames via system `ffmpeg`),
  `sticker.make` (server-side GIF compositing) and `update_persona` / `set_quiet_hours`.
  Still missing: a video **audio track** (`watch` only looks at frames) and client-supplied
  prompts. See
  [Standalone operation, tool loop, stickers/diary and backup v6](#standalone-operation-tool-loop-stickersdiary-and-backup-v6-013).
- Android does not start it as an embedded service; remote mode is a development build switch.
  iOS does not call this service by default either: its in-process decisions run the same Kotlin
  `:agentcore` code, and remote mode (also a build/launch switch) points at this service. The old
  Swift mirror only exists in the macOS test build now — see `docs/IOS-PARITY.md`.
- Only four functions are exposed. Adding more means adding an `AgentCore` method, a route in
  `Api.kt`, and a test — the shared implementation is already in `:agentcore`.

## Tests

```bash
./gradlew :agentcore:test :agentd:test
```

- `:agentcore` — 60 tests (`commonTest` + `jvmTest`; `:agentcore:test` is the `jvmTest` alias). `AgentCoreSpecTest` (the shared `spec/agent-core/cases/*.json`
  contract through the `AgentCore` interface, protocol pinning, JSON codec round-trips,
  malformed-response rejection), plus the 0.11.1 hardening suites:
  `WebUrlPolicyHardeningTest` (the audit's URL-bypass table and the “normal domains/public IPs
  still pass” side), `JsonLimitsTest` (nesting/string/number/container/dedup limits) and
  `AgentCoreCodecLimitsTest` (codec-level rejection + bounded error text).
- `AgentdApiTest` — 29 handler-level tests: every route, defaults, clamping, error codes, and a
  check that the route table comes from `AgentCoreProtocol`; 0.11.1 additions cover 413 (8 MiB
  body), no-echo of long tool names/paths, generic 500, deeply nested / `1e308` / `NaN` /
  duplicate-key / over-long-string JSON, and a legal 256 KiB body.
- `AgentdServerTest` — 8 integration tests on a real `ServerSocket`-based server (port 0): normal
  requests, half-open body (health stays fast, then `408`), oversized declared body (`413`),
  chunked body (`411`), the optional token (`401`/`200`), and 0.12.0 rate limiting
  (`429` + `Retry-After`, `/health` in its own bucket, `--rate-limit 0` disabling it).
- `MessageImportApiTest` — 19 handler-level tests for batch history import: idempotent by id,
  out-of-order inserts into the right block, atomic rejection (bad role / illegal id / unknown
  attachment / >200), 8 MiB body cap, restart persistence, pagination consistency and
  "`unread`/events untouched".
- `BackupStreamTest` — 14 tests for the streaming backup: `Transfer-Encoding: chunked` replayed from
  a raw socket, body equality with the handler-level export, HTTP import with a streamed request
  body, bad-JSON/envelope-order rejection with zero leftovers, 8 MiB media round-trip, corrupt
  base64, `mediaSkipped`, section-order/unknown-section tolerance and `userAvatarUri` normalisation.
- `BackupStreamMemoryTest` — the 256 MiB-heap proof: 10,000 messages + 50 images exported (chunked)
  and imported (streamed request body) over real HTTP; the whole `:agentd:test` task runs with
  `maxHeapSize = "256m"`, so any regression to "whole backup in memory" fails here with an OOM.
- `FileStoreTest` — 19 storage tests: seeding/read-back, block split at 200, delete boundary
  recomputation, emptied-block holes (no renumbering), missing-`block_index.json` rebuild, atomic
  writes (no `*.tmp`, parseable JSON), 8×125 concurrent appends with no loss/duplication,
  `clientMessageId` lookup, `secrets.json` 0600 permissions, usage accumulation, event cursor +
  long-poll timeout, torn-line tolerance, and path-traversal rejection.
- `StateApiTest` — 18 handler-level tests for `/v1/state` (full/incremental/persona filter/bad
  params/`hasMoreMessages`/no secrets), `/v1/sessions` (fields, filter), one-block paging in both
  directions (hole skipping, `blockIndex: null` past the ends, invalid `limitBlocks`/offsets) and
  `/v1/events` (immediate/timeout/wake-up/negative cursor), plus `503` without a data directory.
- `ChatSseTest` — 10 tests for the SSE send path: event order (`accepted → delta → done`), tool
  event forwarding, sequential **and concurrent** `clientMessageId` idempotency, orphaned
  user-message completion, a dead sink that must not stop generation, attachment persistence, and
  validation-before-stream.
- `MediaApiTest` — 24 handler-level tests for `/v1/media`: upload 201 / sha256 dedup 200 / PNG+WebP /
  Content-Type+kind+persona+2 MiB rejects, download bytes + cache headers + ETag/304 (exact/weak/`*`),
  unknown and traversal-shaped ids, delete with message/persona/preset references + `409`/`force`,
  attachment id-or-URL canonicalisation and `unknown_media`, backup media base64 round-trip / 8 MiB
  skip count / corrupt-sha rejection / `replace` clearing old media, restart persistence, 8
  concurrent same-content uploads, and `503`/`405`.
- `MediaServerTest` — 4 real-socket tests: binary upload/download over HTTP with cache headers and
  304 revalidation, token enforcement on media routes, the 4 MiB media vs 1 MiB other-route
  declared-body limits, and the 2 MiB file limit inside the 4 MiB route.
- `AuthorityServerTest` — 6 real-socket tests: state + SSE chat over HTTP (`text/event-stream`, no
  `Content-Length`, frame order), a client disconnecting mid-stream still getting the reply
  persisted, `--token`/rate limiting on the new routes, HTTP long poll waking up on a new message,
  and JSON errors before a stream starts.
- `MainOptionsTest` — 11 tests for flag/environment parsing and precedence (`--rate-limit`,
  `--rate-burst`, `--data`/`YAP_AGENTD_DATA`, invalid values rejected before the server starts).
- `RateLimiterTest` — 7 tests for the token bucket itself (burst, refill, capped refill, per-source
  isolation, disabling, bounded map / LRU eviction) with an injected clock, so none of them sleep.
- `ToolsRoutesTest` — 17 tests for the three 0.12.0 routes (`/v1/tools/web-search`,
  `/v1/tools/web-fetch`, `/v1/proactive/tick`): provider/base/key resolution and overrides, result
  normalisation, 400/401/429/502 mapping, "the key never leaks", fetch text extraction and
  `URL_REJECTED`, and proactive tick persistence/dedupe/`503`. All upstreams are local
  `com.sun.net.httpserver` fakes; the proactive cases use a temporary `--data` directory.

`:agentd:test` is **556 tests** in total after the 0.13 standalone/tool/backup work (473 before this
round) — the authoritative-mode suites above plus the `search/` (52) and `proactive/` (68) suites
added in the same release. The 43 tests for the M3–M6 server write paths are `CrudRoutesTest`,
`BackupApiTest` and `PushRoutesTest`; the 28 media tests (M5 upload/download) are `MediaApiTest`
and `MediaServerTest`.

The 83 tests added in this round:

- `ServerToolCatalogTest` (6) — the registry **is** the `tools[]` array and the `# 你能做什么`
  section (both directions), every schema's `required` points at real properties, no networking ⇒
  no web tools, and the registry only advertises what the server can execute: 0.14.1 adds
  `update_persona` / `set_quiet_hours` / `listen` / `watch` / `sticker.make`; 0.16 adds `think`
  (the reasoning-effort switch).
- `ToolExecutorTest` (24) — each tool's real effect: `stay_silent` writes nothing, `defer_reply`
  clamps minutes and writes a `PENDING` intent, `set_mood` writes all four dimensions (and clamps),
  `set_activity` normalises `kind`/`availability` and clamps minutes, `diary` add/list/remove,
  `remember`/`recall`, `web_search`/`web_fetch` against a local fake (key only from the server env,
  URL policy honoured), `sticker` send/list/unknown-id/`make`, broken JSON and unknown tools never
  throw, and the trace JSON matches `ToolTraceItem`.
- `ToolLoopTest` (10) — real `LlmClient` + fake SSE server: a tool call is executed and its result
  fed back (`role=tool`, `tool_call_id`), streamed arguments split across frames are reassembled,
  `toolTraceJson`/sticker attachments are persisted, `stay_silent` leaves no assistant message and
  marks read, 5 rounds by default (caller/model may request 1–15; the last round has no `tools[]`),
  an XML-tagged tool call (`<tool_call>` / `<tool_code>` / `<tool>`) is normalised and never leaks to
  the client, the wall-clock deadline stops the loop,
  `toolEnabled=false` sends no tools, and an unknown tool becomes a failed tool result.
- `StickerDiaryApiTest` (10) — sticker CRUD shapes (root `stickers`, `file`/`fileName`/`mediaId`/
  `url`, persona filter, newest-first, `mediaId` registration, mime/size/base64/404/405) and diary
  CRUD (root `notes`, seven columns, `remindInMinutes` → absolute `remindAt`, content edits keep
  `remindedAt`, `remindAt:null` clears, `limit`/`since`, persona isolation, restart).
- `AutoTriggersTest` (10) — mood after **every** user message (0.15) and evolution after 20, both
  **without any client connection**, separate cursors, 24 h fallback, no LLM config leaves the evolution cursor alone,
  upstream failure does not throw, counters survive a restart, and the chat service runs the hooks
  only when an LLM is configured.
- `DiaryReminderTest` (11) — a due reminder becomes exactly one proactive message, future reminders
  wait, >24 h is cleared without a message, reminders survive a restart and still fire once, a
  persona without a session is skipped, deferred intents fire once / wait / expire / survive a
  restart, and user-scheduled reminders are not blocked by the activity gate.
- `BackupV6Test` (12) — client key sets (top level and per section), both v6 fixtures import with
  every section intact (avatar/attachment/sticker resolution, summary, tool trace, diary, mood),
  v5 still imports, bare file and `{mode,data}` envelope both work, merge semantics, client backup
  survives **import → export → import → export** byte-for-byte, server-created content round-trips,
  v7 is still rejected, and a broken media makes the import transactional.

## 0.15: context & memory v2, per-message mood, tool rounds, proactive failures

This round is the server half of `docs/design/context-memory-v2.md`. Everything below is covered by
tests in this module (`:agentd:test`).

### Context assembly v2 (`llm/ChatPromptBuilder` + `context/ContextAssembler`)

One round of context is assembled in a **fixed order**, and the order/titles come from `:agentcore`
contracts (`ExperienceContract`, `MemoryContract`, `PromptContract`):

```
full persona + tools (identity → world → voice → relationship → capabilities → boundaries)
  → # 你的备忘录 (## 长期记忆 ≤500 chars, ## 短期记忆 ≤200 chars)
  → # 你此刻的状态 (+ # 你的心情: intuitive label first, then the three dimensions)
  → ## 对话主题 (≤12 × 120 chars, each with a time slot)
  → # 你刚才心里想 (last mental activity, ≤60 chars)
  → remaining budget: the newest raw chat messages
```

`ContextAssembler` uses `ExperienceContract.practicalPlan()` (28,000 chars ≈ 14k tokens) as a
**hard** budget: `system + history` never exceeds it. On overflow it first compresses the topic
summary (drops the oldest segments, clips each to 120 chars) and only then drops older history;
history is always taken newest-first and emitted in ascending order. There is no fixed “40 messages”
cap any more — the character budget decides (`ChatPromptBuilder.chatMessages`).

### Memory v2 (`experience/SessionMemoryService`, `experience/TopicSummaryRules`, `experience/SessionRecall`)

| cache | when it is updated |
| --- | --- |
| short (≤200 chars) | every 5 user messages, **or** immediately when the model changed the plan (`remember` / `diary` / `defer_reply` in this turn) |
| long (≤500 chars) | between 15 and 40 messages, **decided by the model**: from 15 it is asked at every check and may answer `SKIP`; at 40 it is forced |
| topics (≤12 × 120 chars) | same 15–40 window, or immediately when the topic clearly switches (`TopicSummaryRules.shouldSplit`, keyword overlap); each segment carries a time slot such as `2026-11-18 晚` |

“Check cursor” and “update cursor” are stored separately, so a `SKIP` only advances the check cursor
(next check 15 messages later, forced update at 40) instead of calling the model on every message.

`memorize(query)` is a new tool: it searches the **raw session history** (keyword + topic segment +
time decay, newest 600 messages) and returns the injection shape from the design doc:

```
- <回忆：2026-11-18 晚 —— 加班与方案改版；你原话：「方案又被甲方打回来了」>
```

`recall` still searches the long-term memory store; the capability prompt tells the model the
difference, insists on calling `remember` when the user asks it to remember something, and bans
narration (`沉默了一会儿` / `顿了顿` / parenthesised actions) and XML tool tags in the body.

### Mood after every user message (`mood/MoodService`, `mood/MoodRules`)

- `ChatService` judges the mood **after storing the user message and before building the prompt**, so
  the reply reacts to the message that was just read.
- The “≤2 per dimension” clamp is gone: only the physical ranges are clamped
  (`valence -2..2`, `arousal/dominance 0..5`, `intensity 1..5`). A single message may move the mood
  from the bottom to the top.
- The prompt leads with the intuitive conclusion (`心情：有点累（强度 3/5）`) and then gives the three
  dimensions as calibration values (`PromptContract.Mood`).
- A manual `set_mood` later in the same turn still wins (it writes the store directly; the per-message
  judgement already ran before the tools).

### Tool rounds (`llm/LlmReplyGenerator`)

Default is 5 rounds (`ExperienceContract.DEFAULT_TOOL_ROUNDS`); the caller/model may request more via
the optional `toolRounds` field of `POST /v1/sessions/{id}/messages` (or the generator's constructor),
and everything is clamped to 1..15 by `ExperienceContract.clampToolRounds`. The last round never
carries `tools[]`.

### Proactive messages: background record (`ToolsRoutes.ProactiveTicker`, `store/ProactiveFailure`)

Proactive messages are generated, persisted and recorded entirely on the server, whether or not a
client is connected:

- every proactive message gets a `message.created` event plus a `message_queue.json` record. Online:
  queued + `typing.started` (delivered at `visibleAt`). Offline: the record is enqueued and completed
  immediately (`MessageDelivery.deliverNow`), so the client sees it on its next sync;
- failures are kept in `proactive_failures.json` (stage = `generation` / `persistence` / `delivery` /
  `push`, newest 200) and exposed as the additive `proactiveFailures` field of `GET /v1/state`;
- a generation failure still never falls back to a template message.

### Backup v6+ (`store/BackupV6`, `store/FileStore`)

`mentalActivities[]`, `sessionMemory[]` (including 0.15 topics and cursors) and `proactiveFailures[]`
are exported under the optional `extensions` object — the top-level key set stays client v6 compatible.
Persona `blockedUntil` / `blockReason` and message `blocked` remain additive client-readable keys.
A client v6 backup (no `extensions`) still imports; in `merge` mode the server-only sections are kept.

### XML tool calls (`llm/XmlToolCalls`)

DeepSeek baselines sometimes emit tool calls as body text (`<tool_call>…</tool_call>`, `<tool_code>`,
`<tool>`). `LlmClient` now filters those tags out of the streamed text (even when a tag is split across
deltas) and normalises the content (JSON object/array, `name({...})`, Python kwargs, or a `name=`
attribute on the tag) into ordinary `tool_calls`, so the same tool loop executes them.


## 0.16: tool-call-only replies and reasoning effort

The 0.16 rules live in `spec/tool-call-agents/README.md`. On the server the chat path is now
**tool-call-only**: the model does not produce the reply directly, every visible message is a
`send_message` call, and `reasoning_effort` is a request parameter the server controls.

### `send_message` is the only exit

```
round 1: system prompt + tools[]  → model calls send_message{"text":"…"}
         tool result: 已发送，messageId=<预分配 id>（这一轮第 N 条）  → model may send another one
…
ChatService: persist every collected message (same id) → message.created ×N → one done
```

- `# 你能做什么` starts with `## 怎么说话（必须遵守）`: every sentence must go through
  `send_message`, ≤4 per turn, ≤120 chars each, the tool result carries the `messageId`, and a turn
  with neither `send_message` nor `stay_silent`/`defer_reply` is a protocol violation.
  Text streamed outside a `send_message` call is only the client's "typing…" indicator.
- The `messageId` is **real**: `ToolExecutor.send_message` asks `FileStore.newMessageId()` for the id
  before the message exists, returns it to the model, and `ChatService` persists with that same id
  (`appendMessage(..., id = …)`); so the id the model saw is the id the clients receive. Each
  message also keeps its `toolTraceJson` (the `send_message` entry), like every other tool turn.
- SSE shape stays `accepted → message.created* → done` (tool events in between). The count of
  `message.created` equals the number of `send_message` calls; `done.assistantMessageId` is the last
  one. Caps, crisis priority, blocking, the two-silent-turns limit, the delivery queue and
  typing indicators are unchanged.
- `think` is now a registered tool (no side effect): `ToolExecutor.think` returns a normal tool
  result and asks the generator for `high` reasoning effort for the rest of the turn.

### Legacy fallback, silence, protocol violation

A tool-enabled turn that only produces plain text is **not** persisted any more: the generator puts
that body back as an assistant message, appends an invisible user reminder asking the model to
confirm (*send it → `send_message(text)` / don't send → `stay_silent` / just thinking → no body*),
and retries with tools. This happens at most **2** times (`[agentd] protocol_reminder`). If the
model still writes plain text, the body is dropped: nothing persisted, `done.silent = true`, no
`message.created`, and a `[agentd] protocol_dropped` stderr line — never an automatic legacy send.
Crisis turns (safety note first) and `defer_reply` turns are not confirmed; neither are turns with
tools disabled (pure-text degradation).

| what the model did | what the server does |
| --- | --- |
| one or more `send_message` | persists exactly those messages (same ids), one `message.created` each, one `done` |
| no `send_message`, a body after 2 confirmation reminders (tools enabled, not crisis/defer) | **dropped as silent**: nothing persisted, `done.silent = true`, no `message.created`, `[agentd] protocol_dropped` log |
| no `send_message`, but a final body (tools disabled / old generator / crisis / `defer_reply`) | **legacy fallback**: the body is split on blank lines into 1–3 messages, persisted (no tool trace), one `message.created` each, and a `[agentd] legacy 回退` stderr line is written as the prompt-migration metric |
| `stay_silent` only | nothing persisted, session marked read, `done.silent = true`, no `message.created` |
| `defer_reply` only | the intent is written, nothing persisted, `done.silent = true` (not a violation) |
| a tool call and then nothing at all | nothing persisted, `done.silent = true`, plus a `[agentd] 协议违规` line (never an empty bubble) |

### Migration compatibility

- Old assistant rows have no `toolTraceJson`. When such a row is in the prompt history,
  `ChatPromptBuilder` appends `LEGACY_HISTORY_NOTE` to the capability section: *“历史里有些话是旧版本
  直接说出来的（那些消息没有工具痕迹）：那只是记录，不代表你现在可以跳过 `send_message`。”*
  The stored row is **never** rewritten (no fabricated tool traces).
- Backups: the export format version is 7 (`FileStore.BACKUP_FORMAT_VERSION`) and imports accept
  **v1–v7**, so both v6 client exports (`BackupV6Test` fixtures) and v7 files stay readable; v8 is
  still rejected. New rows keep the client-readable `toolTraceJson` key only when there is a trace.

### Reasoning effort

| where | value |
| --- | --- |
| main chat, every request | `reasoning_effort: "low"` by default (`LlmDefaults.DEFAULT_REASONING_EFFORT`) |
| main chat, after a `think` tool call in the same turn | `"high"` for the remaining requests of that turn |
| next turn | back to the default (`low`, or the server override) |
| auxiliary calls (mood / evolution / proactive / crisis classifier / ASR) | the field is **not sent** (`LlmClient.stream`/`complete` pass `null`) |

The default can be overridden by the deployment: `--reasoning-effort low|medium|high` (or
`YAP_LLM_REASONING_EFFORT`), stored on `ServerLlmConfig.reasoningEffort` →
`LlmConfig.reasoningEffort`. An unknown value fails at startup / with `llm_invalid_config` instead
of being silently ignored. `ToolCallOnlyTest` pins the request bodies (`low` → `high` → `low`), the
config override, the auxiliary calls, and the rejection.

## 桌面端真人测试工具（无 UI）

`DesktopMain` 直接复用本模块的存储、提示词、工具回路、记忆、心情、日记、人格进化与消息队列，
只在终端里对话；用于在电脑上真人测试提示词工程效果。

```bash
./gradlew :agentd:desktopJar
java -jar agentd/build/libs/agentd-desktop.jar \
  --data /tmp/yap-desktop \
  --llm-base-url http://127.0.0.1:11435/v1 --llm-key "$KEY" --llm-model campus-chat
```

命令：`/personas`、`/use <n|id>`、`/new [persona]`、`/state`、`/tools`、`/system`、`/help`、`/quit`。
工具调用、流式文字、tokens/耗时都会打印；数据与 agentd 共享同一套 `FileStore` 结构。
