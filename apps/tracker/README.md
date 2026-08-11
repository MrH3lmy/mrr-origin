# Browser tracker

The tracker captures deterministic acquisition evidence without browser
fingerprinting. Sessions expire after 30 minutes of inactivity by default;
callers may provide a positive safe-integer `sessionTimeoutMs` override. The
tracker currently uses first-party `localStorage` when it is available; callers
can provide a different `Storage` implementation. Consent, storage-mode, and
retention policy remain deferred architecture decisions.

```ts
import { createTracker } from "@mrr-origin/tracker";

const tracker = createTracker({
  publicKey: "project_public_key",
});

tracker.page();
tracker.track("trial_started", { plan: "starter" });
tracker.identify("application-user-42");

// A transport can periodically batch and send these idempotent envelopes.
const events = tracker.drain();
```

`identify` accepts the SaaS application's stable user ID; it does not require or
derive an email address. Repeating the call is safe: the API project-scopes the
identity and links every explicitly identified browser visitor deterministically.
Use an opaque application identifier rather than personal data.

The first event in every session carries a touchpoint. The persisted
`firstTouch` is never replaced when a later session starts. URLs are limited to
HTTP(S), credentials and fragments are removed, and only the five supported UTM
parameters (`source`, `medium`, `campaign`, `term`, and `content`) are extracted.

Storage reads, parsing, and writes are best-effort. If browser storage is
missing, denied, or corrupted, capture continues with in-memory identity for the
life of that tracker instance.

The production tracker bundle must remain at or below **5 KB gzip**. Measure the
minified artifact and its gzip-compressed size after every production build.
