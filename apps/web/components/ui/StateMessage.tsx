import type { ReactNode } from "react";

import styles from "./StateMessage.module.css";

interface StateMessageProps {
  title: string;
  description?: string;
  children?: ReactNode;
  role?: "status" | "alert";
}

function StateMessage({
  title,
  description,
  children,
  role = "status",
}: StateMessageProps) {
  return (
    <div className={styles.state} role={role}>
      <p className={styles.title}>{title}</p>
      {description ? <p className={styles.description}>{description}</p> : null}
      {children ? <div className={styles.actions}>{children}</div> : null}
    </div>
  );
}

export function EmptyState(props: StateMessageProps) {
  return <StateMessage role="status" {...props} />;
}

export function ErrorState(props: StateMessageProps) {
  return <StateMessage role="alert" {...props} />;
}
