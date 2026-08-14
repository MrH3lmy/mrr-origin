import { redirect } from "next/navigation";

import { getSession } from "@/lib/auth/session";

import styles from "./sign-in.module.css";

export const metadata = {
  title: "Sign in — MRROrigin",
};

interface SignInPageProps {
  searchParams: Promise<{ error?: string; redirectTo?: string }>;
}

export default async function SignInPage({ searchParams }: SignInPageProps) {
  const session = await getSession();
  if (session) {
    redirect("/app");
  }

  const { error, redirectTo } = await searchParams;

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <div className={styles.brand}>
          <span className={styles.mark} aria-hidden="true">
            M
          </span>
          MRROrigin
        </div>
        <h1 className={styles.title}>Local development sign-in</h1>
        <p className={styles.description}>
          MRROrigin authenticates with a signed OAuth 2.0 access token from your
          configured OIDC provider. Obtain one the same way{" "}
          <code>docs/local-authentication.md</code> describes: a token issued by{" "}
          <code>OIDC_ISSUER_URI</code>, with audience <code>OIDC_AUDIENCE</code>{" "}
          and a stable <code>sub</code> claim, then paste it below.
        </p>

        {error === "missing_token" ? (
          <p className={styles.error} role="alert">
            Enter an access token before continuing.
          </p>
        ) : null}
        {error === "session_expired" ? (
          <p className={styles.error} role="alert">
            Your session expired or was rejected by the API. Sign in again.
          </p>
        ) : null}

        <form method="POST" action="/auth/session">
          <div className={styles.field}>
            <label className={styles.label} htmlFor="accessToken">
              Access token
            </label>
            <textarea
              id="accessToken"
              name="accessToken"
              className={styles.textarea}
              placeholder="eyJhbGciOi..."
              required
              autoComplete="off"
              spellCheck={false}
            />
          </div>
          {redirectTo ? (
            <input type="hidden" name="redirectTo" value={redirectTo} />
          ) : null}
          <button type="submit" className={styles.submit}>
            Continue
          </button>
        </form>

        <p className={styles.note}>
          Production sign-in (redirect-based OIDC login) depends on the
          identity-provider and session/BFF topology decision, which is
          intentionally deferred in <code>ARCHITECTURE.md</code>. This screen is
          a local-development stand-in, not the final product sign-in
          experience.
        </p>
      </div>
    </main>
  );
}
