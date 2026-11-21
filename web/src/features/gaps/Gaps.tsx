import { type DesignRow, type Gap, type Report, num, words } from "../../shared/data/report";
import table from "../../shared/ui/table.module.css";
import styles from "./gaps.module.css";

const MONITOR: Record<string, string> = {
  ERROR_SPIKE: "SLO burn rate",
  DEPENDENCY: "SLO burn rate",
  LATENCY: "p95 over latency budget",
  SATURATION: "CPU above 90% of limit",
  SILENT_DROP: "business-metric anomaly",
};

/** The first monitors the radar adds, and what the gate and the cost lens cost or save. */
export function Gaps({ gaps, radar, cost }: { gaps: Gap[]; radar: DesignRow; cost: Report["cost"] }) {
  if (gaps.length === 0) return <p className={styles.empty}>No coverage gaps in this fleet: every service and failure mode already has a monitor that sees it.</p>;
  return (
    <div className={styles.layout}>
      <div className={table.wrap}>
        <table className={`${table.table} ${styles.narrow} num`}>
          <caption>From the first seed. Score weighs blast radius by incidents noticed by chance and by how poorly static thresholds see the mode.</caption>
          <thead>
            <tr>
              <th scope="col">Service</th>
              <th scope="col">Failure mode</th>
              <th scope="col">Noticed by chance</th>
              <th scope="col">Score</th>
              <th scope="col">Monitor added</th>
            </tr>
          </thead>
          <tbody>
            {gaps.map((g) => (
              <tr key={`${g.service}-${g.mode}`}>
                <th scope="row">
                  <code>{g.service}</code>
                </th>
                <td>{words(g.mode)}</td>
                <td>{g.caught_by_chance}</td>
                <td>{num(g.score)}</td>
                <td>{MONITOR[g.mode] ?? words(g.fix)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <dl className={styles.tiles}>
        <div>
          <dt>Tests the gate asked for</dt>
          <dd className="num">{num(radar.test_hours_per_week)} h a week</dd>
        </div>
        <div>
          <dt>Bad rollouts stopped in the canary</dt>
          <dd className="num">{num(radar.caught_in_canary)}</dd>
        </div>
        <div>
          <dt>Compute requested above need</dt>
          <dd className="num">€{num(cost.waste_eur_per_month)} a month</dd>
          <p className={styles.note}>of €{num(cost.eur_per_month)}; p95 usage plus 20% headroom</p>
        </div>
      </dl>
    </div>
  );
}
