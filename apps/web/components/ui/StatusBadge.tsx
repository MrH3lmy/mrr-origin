import styles from "./StatusBadge.module.css";

export type StatusTone = "positive" | "warning" | "danger" | "info" | "neutral";

interface StatusBadgeProps {
  tone: StatusTone;
  children: string;
}

/**
 * Every tone pairs a distinct icon shape with color, so status is never conveyed by color alone
 * (DESIGN_SYSTEM.md "Color must never be the only carrier of meaning").
 */
export function ToneIcon({
  tone,
  size = 12,
}: {
  tone: StatusTone;
  size?: number;
}) {
  switch (tone) {
    case "positive":
      return (
        <svg
          className={styles.icon}
          width={size}
          height={size}
          viewBox="0 0 16 16"
          aria-hidden="true"
        >
          <path
            d="M3 8.5 6.2 12 13 4"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      );
    case "warning":
      return (
        <svg
          className={styles.icon}
          width={size}
          height={size}
          viewBox="0 0 16 16"
          aria-hidden="true"
        >
          <path
            d="M8 1.5 15 14.5H1z"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinejoin="round"
          />
          <path
            d="M8 6.4v3.4"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
          />
          <circle cx="8" cy="12.1" r="0.9" fill="currentColor" />
        </svg>
      );
    case "danger":
      return (
        <svg
          className={styles.icon}
          width={size}
          height={size}
          viewBox="0 0 16 16"
          aria-hidden="true"
        >
          <path
            d="M4 4l8 8M12 4l-8 8"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
          />
        </svg>
      );
    case "info":
      return (
        <svg
          className={styles.icon}
          width={size}
          height={size}
          viewBox="0 0 16 16"
          aria-hidden="true"
        >
          <circle
            cx="8"
            cy="8"
            r="6.5"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
          />
          <path
            d="M8 7.2v4"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
          />
          <circle cx="8" cy="4.9" r="0.9" fill="currentColor" />
        </svg>
      );
    default:
      return (
        <svg
          className={styles.icon}
          width={size}
          height={size}
          viewBox="0 0 16 16"
          aria-hidden="true"
        >
          <circle cx="8" cy="8" r="3.4" fill="currentColor" />
        </svg>
      );
  }
}

export function StatusBadge({ tone, children }: StatusBadgeProps) {
  return (
    <span className={`${styles.badge} ${styles[tone]}`}>
      <ToneIcon tone={tone} />
      {children}
    </span>
  );
}
