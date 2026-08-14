import styles from "./Stepper.module.css";

export type StepState = "complete" | "current" | "upcoming";

export interface StepDefinition {
  key: string;
  label: string;
}

interface StepperProps {
  steps: StepDefinition[];
  currentKey: string;
  completedKeys: ReadonlySet<string>;
}

function stateFor(
  key: string,
  currentKey: string,
  completedKeys: ReadonlySet<string>,
): StepState {
  if (completedKeys.has(key)) return "complete";
  if (key === currentKey) return "current";
  return "upcoming";
}

const STATE_LABEL: Record<StepState, string> = {
  complete: "Completed",
  current: "Current step",
  upcoming: "Not started yet",
};

export function Stepper({ steps, currentKey, completedKeys }: StepperProps) {
  return (
    <ol className={styles.list} aria-label="Setup progress">
      {steps.map((step, index) => {
        const state = stateFor(step.key, currentKey, completedKeys);
        return (
          <li
            key={step.key}
            className={`${styles.item} ${state === "complete" ? styles.complete : ""} ${state === "current" ? styles.current : ""}`}
            aria-current={state === "current" ? "step" : undefined}
          >
            <span className={styles.marker} aria-hidden="true">
              {state === "complete" ? "✓" : index + 1}
            </span>
            <span className={styles.label}>
              {step.label}
              <span
                style={{
                  position: "absolute",
                  width: 1,
                  height: 1,
                  overflow: "hidden",
                  clip: "rect(0 0 0 0)",
                }}
              >
                {" "}
                — {STATE_LABEL[state]}
              </span>
            </span>
          </li>
        );
      })}
    </ol>
  );
}
