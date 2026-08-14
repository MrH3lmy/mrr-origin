/**
 * Restricts a post-auth redirect target to a same-origin application path. Rejects protocol-relative
 * URLs (`//evil.example`), backslash variants (`/\evil.example`, browsers treat `\` like `/`), and
 * any other value that would resolve off-origin -- an unrestricted `startsWith("/")` check is not
 * enough, since `new URL("//evil.example", request.url)` resolves to `https://evil.example`.
 */
export function isSafeRedirectPath(
  candidate: string,
  requestUrl: string,
): boolean {
  if (
    !candidate.startsWith("/") ||
    candidate.startsWith("//") ||
    candidate.startsWith("/\\")
  ) {
    return false;
  }
  try {
    const resolved = new URL(candidate, requestUrl);
    return resolved.origin === new URL(requestUrl).origin;
  } catch {
    return false;
  }
}
