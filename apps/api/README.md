# Tracking ingestion contract

The browser tracker sends version 1 batches to `POST /api/public/v1/events` with
`Content-Type: application/json`, `Origin: https://customer.example`, and the
write-only project credential in `X-Ingestion-Key`.

The ingestion key—not any request-body identifier—selects the owning workspace
and project. The `Origin` host is normalized with the same lowercase, IDN, and
trailing-dot policy used for configured allowed domains, then checked against
that key's project.

Requests are limited to **256 KiB before JSON deserialization** and **100 events**.
Each event timestamp may be exactly 30 days old through exactly 5 minutes in the
future, relative to API receipt time. Older or later timestamps are rejected.

The whole batch is one database transaction: validation or persistence failure
writes nothing. A project-scoped transaction lock serializes ingestion so event
duplicates are detected before visitor or session mutation. New events return
`ACCEPTED`; an event ID already stored for the project returns `DUPLICATE` without
changing its original event, visitor, or session data.

The API stores the canonical request hash and final per-event results with every
batch receipt. Retrying the exact batch ID and content returns the originally
stored response. Reusing a batch ID with different content returns `409`.

Status codes are:

- `200` for accepted batches and exact retries;
- `400` for malformed/invalid envelopes, more than 100 events, duplicate IDs in
  one batch, unsupported versions, or out-of-window timestamps;
- `401` for unknown or revoked ingestion keys;
- `403` for malformed or unlisted origins;
- `409` for batch-ID/content or session/visitor conflicts; and
- `413` when the raw request body exceeds 256 KiB.

Clients may retry network failures and ambiguous responses with the same batch
ID and unchanged body. They must generate a new batch ID when changing content.
