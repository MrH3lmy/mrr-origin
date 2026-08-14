import type { ReactNode } from "react";

import styles from "./Panel.module.css";

interface PanelProps {
  title?: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  as?: "section" | "div";
  "aria-labelledby"?: string;
}

export function Panel({
  title,
  subtitle,
  actions,
  children,
  className,
  as = "section",
  ...rest
}: PanelProps) {
  const Tag = as;
  return (
    <Tag className={`${styles.panel} ${className ?? ""}`} {...rest}>
      {title ? (
        <div className={styles.header}>
          <div>
            <h3 className={styles.title}>{title}</h3>
            {subtitle ? <p className={styles.subtitle}>{subtitle}</p> : null}
          </div>
          {actions}
        </div>
      ) : null}
      {children}
    </Tag>
  );
}
