import { motion as m, useReducedMotion } from "framer-motion";

import { type DesignRow, SHORT, minutes, toneOf } from "../../shared/data/report";
import { motion } from "../../shared/theme/motion";
import styles from "./detection.module.css";

const TICKS = [10, 30, 60, 180, 600, 1440, 4320];
const TICK_LABEL: Record<number, string> = { 10: "10 min", 30: "30 min", 60: "1 h", 180: "3 h", 600: "10 h", 1440: "1 day", 4320: "3 days" };

/** Median minutes from the start of an incident to someone noticing it, and to it being fixed, on a log scale. */
export function Detection({ designs }: { designs: Record<string, DesignRow> }) {
  const reduce = useReducedMotion();
  const rows = Object.entries(designs).sort((a, b) => b[1].mttr_median_min[0] - a[1].mttr_median_min[0]);
  const hi = Math.max(...rows.map(([, d]) => d.mttr_median_min[0]));
  const lo = Math.min(...rows.map(([, d]) => d.mttd_median_min[0]));
  const ticks = TICKS.filter((t, i) => t <= hi * 1.05 || TICKS[i - 1]! < hi);
  const min = Math.min(10, lo), max = ticks[ticks.length - 1]!;
  const x = (v: number) => ((Math.log(Math.max(v, min)) - Math.log(min)) / (Math.log(max) - Math.log(min))) * 100;
  return (
    <figure className={styles.figure} aria-label="Median minutes until an incident is noticed and until it is fixed, per design">
      <div className={styles.legend} aria-hidden="true">
        <span className={styles.key}>
          <i className={styles.dot} /> noticed
        </span>
        <span className={styles.key}>
          <i className={styles.end} /> fixed
        </span>
        <span className={styles.scale}>median, log scale</span>
      </div>
      <div className={styles.grid}>
        <div className={styles.axis} aria-hidden="true">
          <span />
          <div className={styles.axisTrack}>
            {ticks.map((t) => (
              <span key={t} className={styles.tick} style={{ left: `${x(t)}%` }}>
                {TICK_LABEL[t]}
              </span>
            ))}
          </div>
          <span />
        </div>
        {rows.map(([key, d], i) => {
          const a = x(d.mttd_median_min[0]), b = x(d.mttr_median_min[0]);
          return (
            <div key={key} className={styles.row} data-tone={toneOf(key)} tabIndex={0}
              aria-label={`${d.label}: noticed after ${minutes(d.mttd_median_min[0])}, fixed after ${minutes(d.mttr_median_min[0])}`}>
              <p className={styles.name}>{SHORT[key] ?? d.label}</p>
              <div className={styles.track}>
                {ticks.map((t) => (
                  <span key={t} className={styles.gridline} style={{ left: `${x(t)}%` }} />
                ))}
                <m.span
                  className={styles.span}
                  style={{ left: `${a}%`, width: `${b - a}%` }}
                  initial={reduce ? false : { scaleX: 0 }}
                  animate={{ scaleX: 1 }}
                  transition={{ duration: motion.base, delay: 0.15 + i * motion.stagger, ease: motion.ease }}
                />
                <span className={styles.notice} style={{ left: `${a}%` }} />
                <m.span
                  className={styles.fixed}
                  style={{ left: `${b}%` }}
                  initial={reduce ? false : { opacity: 0 }}
                  animate={{ opacity: 1 }}
                  transition={{ duration: motion.quick, delay: 0.15 + i * motion.stagger + motion.base * 0.8 }}
                />
              </div>
              <p className={`${styles.values} num`}>
                {minutes(d.mttd_median_min[0])} <span className={styles.sep}>to</span> {minutes(d.mttr_median_min[0])}
              </p>
            </div>
          );
        })}
      </div>
    </figure>
  );
}
