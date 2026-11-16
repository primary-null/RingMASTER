# Recorded service answers

Answers recorded on 2026-11-16 by RingMASTER's own engine (ServiceLinks over ServiceWire) from disposable instances made
for the purpose and removed afterwards:

- `pihole-v6/`: Pi-hole Core v6.6, Web v6.6.1, FTL v6.9 (official Docker image 2026.11.0), with synthetic DNS traffic only:
  names under `example.test` (three of them on the deny list) and a few public names. `probe.json` is the unauthenticated
  `GET /api/auth` answer (HTTP 401).
- `pihole-v5/`: Pi-hole v5.18.3 (Docker image 2024.07.0): `probe.json` is `GET /admin/api.php?versions=`;
  `auth-status.txt` is the status of `GET /api/auth` there.
- `homeassistant-rest/`: Home Assistant 2026.11.1 with the `demo` integration, read with a long-lived access token.
  `probe-unauthenticated.txt` is the status and body of `GET /api/` without a token.

Scrubbed before committing: client and upstream addresses replaced by documentation addresses (192.0.2.0/24,
198.51.100.0/24) and `example.invalid` names; Home Assistant camera and media tokens, access tokens, user ids, context ids
and coordinates replaced by zeros or `REDACTED`. Shapes, counts and timestamps are as recorded.

`golden/` holds the text rendering of each template's state for these answers (`ServiceTemplateGoldenTest`), plain and with
identities hidden (showcase).
