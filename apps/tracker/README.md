# Browser tracker

The tracker captures deterministic acquisition evidence without browser
fingerprinting. Callers must supply the project's session inactivity timeout
because the product-wide default is not yet documented. The tracker currently
uses first-party `localStorage` when it is available; callers can provide a
different `Storage` implementation for consent-controlled use. The configured
storage mode and retention policy remain deferred architecture decisions.

```ts
import { createTracker } from "@mrr-origin/tracker";

const tracker = createTracker({
  publicKey: "project_public_key",
  sessionTimeoutMs: configuredSessionTimeout,
});

tracker.page();
tracker.track("trial_started", { plan: "starter" });

// A transport can periodically batch and send these idempotent envelopes.
const events = tracker.drain();
```

The first event in every session carries a touchpoint. The persisted
`firstTouch` is never replaced when a later session starts. URLs are limited to
HTTP(S), credentials and fragments are removed, and only the five supported UTM
parameters (`source`, `medium`, `campaign`, `term`, and `content`) are extracted.

Storage reads, parsing, and writes are best-effort. If browser storage is
missing, denied, or corrupted, capture continues with in-memory identity for the
life of that tracker instance.

No bundle-size budget is currently documented. Measure the minified artifact
and its gzip-compressed size after every production build until a budget is
approved.
