import type { ReactNode } from "react";

import styles from "./Alert.module.css";
import { ToneIcon, type StatusTone } from "./StatusBadge";

interface AlertProps {
  tone: StatusTone;
  title: string;
  detail?: string;
  children?: ReactNode;
  role?: "status" | "alert";
}

const TONE_ROLE: Record<StatusTone, "status" | "alert"> = {
  positive: "status",
  info: "status",
  neutral: "status",
  warning: "status",
  danger: "alert",
};

export function Alert({ tone, title, detail, children, role }: AlertProps) {
  return (
    <div
      className={`${styles.alert} ${styles[tone]}`}
      role={role ?? TONE_ROLE[tone]}
    >
      <span className={styles.icon} aria-hidden="true">
        <ToneIcon tone={tone} size={18} />
      </span>
      <div className={styles.body}>
        <p className={styles.title}>{title}</p>
        {detail ? <p className={styles.detail}>{detail}</p> : null}
        {children ? <div className={styles.actions}>{children}</div> : null}
      </div>
    </div>
  );
}
