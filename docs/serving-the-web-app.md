# Serving the web app (local, LAN, tunnel)

Three processes, in this order:

1. **`quiz.web.ViewableServerMain`** — the API, `:7070` by default (first arg overrides).
2. **Vite dev server** — `cd web && npm run dev`, `:5173`. `vite.config.js` sets
   `host: true` (so a phone on the same Wi-Fi can reach it), `allowedHosts: true`,
   and proxies `/api` → `http://localhost:7070`.
3. **A tunnel**, optional — `cloudflared tunnel --url http://localhost:5173`
   (or `ngrok http 5173`, which has the interstitial problem below).

The frontend calls **relative** `/api/...` paths (see `web/src/lib/api.js`), so the API
and images travel through whatever serves the page: localhost, a LAN IP, or the tunnel.
Point the tunnel at **5173, not 7070** — 7070 serves no HTML, and going straight to it
skips the proxy the app relies on.

That also means: if you ever serve the *built* frontend instead of the dev server,
nothing proxies `/api` any more and the API needs its own route.

## ngrok's interstitial can render as a blank page

**Symptom.** The tunnel URL shows nothing at all in a browser — not the app, and not
the "You are about to visit …" warning page either. `curl` of the same URL returns the
full app, and `localhost:5173` works. Nothing in the app's own logs.

**Cause.** ngrok's free tier serves an interstitial (`ERR_NGROK_6024`) before the app,
and that page's own script can crash:

```
[EXCEPTION] cdn.ngrok.com/static/compiled/js/global.js
TypeError: Cannot read properties of undefined (reading 'includes')
    at bt (…/global.js) … at ue (…/allerrors.js)
```

When it does, the interstitial renders empty — so there is no "Visit Site" button to
click, and the app never loads. `curl` is unaffected because a request carrying
`ngrok-skip-browser-warning: 1` skips the interstitial entirely. Diagnosed 2026-08-09.

**Confirming it is this and not the app.** Check the hops in order; if all three are
200 the stack is fine and the problem is the interstitial:

```
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:7070/api/types
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:5173/api/types
curl -s -o /dev/null -w "%{http_code}\n" -H "ngrok-skip-browser-warning: 1" \
     https://<your>.ngrok-free.dev/api/types
```

Then fetch the URL with a browser user-agent and *without* the skip header: if you get
~2.8 kB mentioning `ERR_NGROK_6024`, the browser is being handed the interstitial.

**Per-browser workaround.** Set the bypass cookie on that origin (devtools console),
then reload:

```js
document.cookie =
  'abuse_interstitial=<your>.ngrok-free.dev; path=/; max-age=86400'
```

Only affects that browser profile — a phone hits the crashed interstitial again.

**Proper fixes.** Verify the ngrok account (still free, needs a card) — that removes the
interstitial; a paid plan or custom domain likewise. Cloudflare Tunnel has no
interstitial and works with this setup unchanged, pointed at 5173.

## Cloudflare Tunnel

The tunnel this project uses now, because it has no interstitial:

```
brew install cloudflared
cloudflared tunnel --url http://localhost:5173
```

A **quick tunnel**: no account, no login, no card. It prints
`https://<words>.trycloudflare.com` and serves the app directly. Vite's
`allowedHosts: true` is what lets that random hostname through; without it Vite answers
"Blocked request", which looks like another blank page.

**Finding the URL again.** The banner prints once at startup, and a quick tunnel takes a
NEW hostname every restart — so a scrolled-away terminal loses it. cloudflared's own
metrics server knows the current one:

```
lsof -nP -a -p $(pgrep -f "cloudflared tunnel") -iTCP -sTCP:LISTEN   # → 127.0.0.1:<port>
curl -s http://127.0.0.1:<port>/quicktunnel                          # → {"hostname":"…"}
```

A stable hostname needs a Cloudflare account with a domain on it: `cloudflared tunnel
login`, `cloudflared tunnel create quiz`, `cloudflared tunnel route dns quiz
quiz.<domain>`, then run the named tunnel instead of `--url`.

**Checking a tunnel of either kind serves the real app**, rather than a warning page:

```
curl -s -o /dev/null -w "%{http_code}\n" https://<host>/api/types
curl -s -A "Mozilla/5.0 … Chrome/126.0 Safari/537.36" https://<host>/ | wc -c
```

The app page is ~16 kB; ngrok's interstitial is ~2.8 kB and says `ERR_NGROK_6024`.
