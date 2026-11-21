import styles from "./states.module.css";

export function Skeleton() {
  return (
    <div className={styles.skeleton} role="status" aria-live="polite">
      <span className="visually-hidden">Loading the report</span>
      <span className={styles.bar} data-size="hero" />
      <span className={styles.bar} />
      <span className={styles.bar} data-size="short" />
      <span className={styles.bar} data-size="block" />
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className={styles.panel} role="alert">
      <p className={styles.title}>The report could not be loaded</p>
      <p className={styles.detail}>{message}</p>
      <button type="button" className={styles.button} onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}

export function MissingState({ set, onSwitch }: { set: string; onSwitch?: () => void }) {
  return (
    <div className={styles.panel}>
      <p className={styles.title}>No {set === "heldout" ? "held-out" : "development"} report yet</p>
      <p className={styles.detail}>
        Run <code>./gradlew :eval:{set === "heldout" ? "heldout" : "evaluate"}</code>, then reload. Held-out seeds run once.
      </p>
      {onSwitch && (
        <button type="button" className={styles.button} onClick={onSwitch}>
          Show the development seeds
        </button>
      )}
    </div>
  );
}
