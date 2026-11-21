import { type DesignRow, type Interval, num, pct, toneOf } from "../../shared/data/report";
import styles from "../../shared/ui/table.module.css";

const ci = (v: Interval, f: (x: number) => string) => (
  <>
    {f(v[0])}
    <span className={styles.ci}>
      {f(v[1])} to {f(v[2])}
    </span>
  </>
);

/** Every design on the same seeds; 95% intervals resample whole weeks. */
export function DesignTable({ designs }: { designs: Record<string, DesignRow> }) {
  return (
    <div className={styles.wrap}>
      <table className={`${styles.table} num`}>
        <caption>Medians for minutes; impact is the share of customers affected times minutes, weighted by tier. Intervals are 95%.</caption>
        <thead>
          <tr>
            <th scope="col">Design</th>
            <th scope="col">Impact per week</th>
            <th scope="col">Caught by a monitor</th>
            <th scope="col">Minutes to notice</th>
            <th scope="col">Minutes to fix</th>
            <th scope="col">Change failure rate</th>
            <th scope="col">Pages per team-week</th>
            <th scope="col">False pages</th>
            <th scope="col">Repeats per week</th>
          </tr>
        </thead>
        <tbody>
          {Object.entries(designs).map(([key, d]) => (
            <tr key={key} data-tone={toneOf(key)}>
              <th scope="row">{d.label}</th>
              <td>{ci(d.impact_per_week, (x) => num(x))}</td>
              <td>{ci(d.monitor_detected_share, (x) => pct(x))}</td>
              <td>{num(d.mttd_median_min[0])}</td>
              <td>{num(d.mttr_median_min[0])}</td>
              <td>{pct(d.change_failure_rate[0], 1)}</td>
              <td>{num(d.pages_per_team_week[0], 1)}</td>
              <td>{pct(d.false_page_share[0])}</td>
              <td>{num(d.recurrences_per_week[0], 1)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
