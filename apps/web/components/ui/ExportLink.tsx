import type { AnchorHTMLAttributes, ReactNode } from "react";

import styles from "./Button.module.css";

interface ExportLinkProps extends AnchorHTMLAttributes<HTMLAnchorElement> {
  href: string;
  children: ReactNode;
}

/**
 * A plain `<a>` styled like `Button`/`ButtonLink`, deliberately not Next.js's `<Link>` -- #26's CSV
 * export endpoints are not app routes, they're proxied API responses that set `Content-Disposition:
 * attachment`, and `<Link>`'s client-side route interception would fight that real browser download.
 */
export function ExportLink({
  href,
  children,
  className,
  ...rest
}: ExportLinkProps) {
  return (
    <a
      href={href}
      className={[
        styles.button,
        styles.secondary,
        styles.small,
        className ?? "",
      ]
        .filter(Boolean)
        .join(" ")}
      {...rest}
    >
      {children}
    </a>
  );
}
