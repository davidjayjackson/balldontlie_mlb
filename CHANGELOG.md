# Changelog

All notable changes to the balldontlie MLB LibreOffice Calc add-in are
documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-07-27

First release. A Java UNO add-in (`com.sun.star.sheet.AddIn`) exposing
[balldontlie](https://www.balldontlie.io/) MLB data as LibreOffice Calc
worksheet functions, packaged as `MLB.oxt`.

### Added
- Worksheet functions: `MLBTEAMS`, `MLBTEAM`, `MLBPLAYERSEARCH`, `MLBPLAYER`,
  `MLBGAMES`, `MLBGAME`, `MLBSTANDINGS`, `MLBSEASONSTATS`, `MLBSTAT`,
  `MLBLASTERROR`, `MLBCACHECLEAR`.
- Generic field-lookup design: `MLBTEAM`, `MLBPLAYER`, `MLBGAME`,
  `MLBSEASONSTATS`, and `MLBSTAT` take an explicit `field` argument naming
  the exact balldontlie JSON key to return, with dot notation for nested
  fields (e.g. `home_team_data.runs`), instead of a fixed set of named
  scalar functions.
- Non-blocking cell functions: a shared, TTL'd, background-refreshed cache
  (`MlbCache`) so no formula ever blocks on network I/O. Functions return
  `#FETCHING`, `#NO_API_KEY`, `#NOT_FOUND`, `#TIER`, `#RATE_LIMIT`, or `#ERR`
  sentinels instead of throwing; `MLBLASTERROR()` surfaces the last
  failure's detail.
- Four-tier API key resolution: an optional trailing `api_key` argument on
  every data function (typed literally or via a cell reference, e.g.
  `=MLBTEAM(147; "abbreviation"; $B$1)`), then the `balldontlie.api.key`
  system property, then the `BALLDONTLIE_API_KEY` environment variable, then
  `~/.config/libreoffice-mlb/balldontlie.properties` — never hardcoded, sent
  as the raw `Authorization` header value (no `Bearer` prefix). Cache keys
  include a fingerprint of the resolved key, so two different keys never
  share cached data or a cached error/cooldown state.
- Tier gating: `MLBSTANDINGS`, `MLBSEASONSTATS`, and `MLBSTAT` call
  paid-tier balldontlie endpoints (`/standings`, `/season_stats`, `/stats`)
  and return `#TIER` (not `#ERR`) on an HTTP 401/403 response, so the
  add-in degrades gracefully on a free-tier key rather than looking broken.
- Rate-limit handling without a retry storm: HTTP 429 fails a fetch
  immediately with `#RATE_LIMIT` instead of joining the bounded
  exponential-backoff retry loop used for 5xx responses; a global request
  throttle also paces outgoing requests to the free tier's ~5 requests/min
  limit.
- Cursor-based pagination (`meta.next_cursor`) across all list endpoints,
  with `team_ids[]`/`player_ids[]`/`game_ids[]`/`seasons[]`/`dates[]` array
  query params.
- `CompatibilityName` set for every function in `CalcAddIns.xcu`, so
  formulas survive an XLS/XLSX save-as/reopen round trip.
- Build pipeline (`build.sh`: `unoidl-write` → `javamaker` →
  `javac --release 8` → `jar` → `.oxt`) and a headless smoke test
  (`tools/test_mlb.py`, run with no key configured — verifies every
  function registers and returns `#NO_API_KEY`).
- MIT license.

### Notes
- Pure JDK implementation: `HttpURLConnection` + a hand-rolled JSON parser,
  no third-party jars. Compiled to Java 8 bytecode so it runs on the JRE 8
  LibreOffice accepts by default.
- Built following the same key-resolution, cache/sentinel, and
  JSON-parsing patterns as the companion
  [balldontlie NBA add-in](https://github.com/davidjayjackson/java_balldontlie_nba),
  adapted to the MLB endpoints, function names, config directory
  (`libreoffice-mlb`), and field mappings.
- Verified end-to-end with a headless LibreOffice instance, both with no API
  key configured (every function registers under its `CompatibilityName`,
  no `#NAME?`, and correctly resolves to `#NO_API_KEY`) and with a real
  free-tier balldontlie key (`MLBTEAMS`/`MLBTEAM`/`MLBPLAYERSEARCH`/
  `MLBPLAYER`/`MLBGAMES`/`MLBGAME` returned real, cross-consistent data;
  `MLBSTANDINGS`/`MLBSEASONSTATS`/`MLBSTAT` correctly degraded to `#TIER`
  on the free tier's HTTP 401, with `MLBLASTERROR()` reporting the detail).
