import { type DesignRow, SHORT, num, toneOf } from "../../shared/data/report";
import styles from "./tradeoff.module.css";

const W = 640, H = 360, PAD = { l: 64, r: 168, t: 16, b: 44 };
const LABEL_GAP = 15;

interface Point {
  key: string;
  label: string;
  px: number;
  py: number;
  ly: number;
  tone: string;
  title: string;
}

/** Pages a team gets a week against the customer impact left, one dot per design. */
export function Tradeoff({ designs }: { designs: Record<string, DesignRow> }) {
  const rows = Object.entries(designs);
  const maxX = Math.ceil((Math.max(...rows.map(([, d]) => d.pages_per_team_week[0])) * 1.08) / 5) * 5;
  const maxY = Math.ceil((Math.max(...rows.map(([, d]) => d.impact_per_week[0])) * 1.08) / 10000) * 10000;
  const sx = (v: number) => PAD.l + (v / maxX) * (W - PAD.l - PAD.r);
  const sy = (v: number) => H - PAD.b - (v / maxY) * (H - PAD.t - PAD.b);
  const pts: Point[] = rows.map(([key, d]) => ({
    key,
    label: SHORT[key] ?? d.label,
    px: sx(d.pages_per_team_week[0]),
    py: sy(d.impact_per_week[0]),
    ly: sy(d.impact_per_week[0]),
    tone: toneOf(key),
    title: `${d.label}: ${num(d.pages_per_team_week[0], 1)} pages per team-week, impact ${num(d.impact_per_week[0])} a week`,
  }));
  // labels sit to the right of their dot; stack them so none overlap, with a leader line when moved
  const byY = [...pts].sort((a, b) => a.py - b.py);
  for (let i = 1; i < byY.length; i++) byY[i]!.ly = Math.max(byY[i]!.ly, byY[i - 1]!.ly + LABEL_GAP);
  const overflow = byY[byY.length - 1]!.ly - (H - PAD.b - 4);
  if (overflow > 0) for (const p of byY) p.ly -= overflow;
  for (let i = byY.length - 2; i >= 0; i--) byY[i]!.ly = Math.min(byY[i]!.ly, byY[i + 1]!.ly - LABEL_GAP);
  // a label goes right of its dot unless another dot sits in its way, then left
  const leftSide = (p: Point) => pts.some((q) => q !== p && q.px > p.px + 4 && q.px < p.px + 170 && Math.abs(q.py - p.ly) < 12);
  const labelX = (p: Point) => (leftSide(p) ? p.px - 14 : Math.max(p.px + 14, ...pts.filter((q) => Math.abs(q.px - p.px) < 12 && Math.abs(q.py - p.ly) < 20).map((q) => q.px + 14)));
  const xTicks = Array.from({ length: maxX / 5 + 1 }, (_, i) => i * 5);
  const yTicks = Array.from({ length: maxY / 10000 + 1 }, (_, i) => i * 10000);
  return (
    <figure className={styles.figure} aria-label="Pages per team-week against customer impact per week, per design">
      <svg viewBox={`0 0 ${W} ${H}`} className={styles.svg} role="img" aria-labelledby="tradeoff-desc">
        <desc id="tradeoff-desc">Lower and further left is better: fewer pages and less impact left on customers.</desc>
        {yTicks.map((t) => (
          <g key={`y${t}`}>
            <line x1={PAD.l} x2={W - PAD.r} y1={sy(t)} y2={sy(t)} className={styles.grid} />
            <text x={PAD.l - 8} y={sy(t)} dy="0.32em" textAnchor="end" className={styles.tick}>
              {t === 0 ? "0" : `${t / 1000}k`}
            </text>
          </g>
        ))}
        {xTicks.map((t) => (
          <text key={`x${t}`} x={sx(t)} y={H - PAD.b + 18} textAnchor="middle" className={styles.tick}>
            {t}
          </text>
        ))}
        <text x={PAD.l + (W - PAD.l - PAD.r) / 2} y={H - 6} textAnchor="middle" className={styles.axisLabel}>
          Pages per team-week
        </text>
        <text transform={`translate(14 ${PAD.t + (H - PAD.t - PAD.b) / 2}) rotate(-90)`} textAnchor="middle" className={styles.axisLabel}>
          Customer impact per week
        </text>
        {[...pts].sort((a, b) => (a.tone === "other" ? 0 : 1) - (b.tone === "other" ? 0 : 1)).map((p) => {
          const lx = labelX(p);
          return (
            <g key={p.key} data-tone={p.tone} className={styles.point} tabIndex={0} aria-label={p.title}>
              <title>{p.title}</title>
              {Math.abs(p.ly - p.py) > 3 && <line x1={p.px + (lx > p.px ? 7 : -7)} y1={p.py} x2={lx + (lx > p.px ? -3 : 3)} y2={p.ly} className={styles.leader} />}
              <circle cx={p.px} cy={p.py} r={14} className={styles.hit} />
              <circle cx={p.px} cy={p.py} r={6} className={styles.dot} />
              <text x={lx} y={p.ly} dy="0.32em" textAnchor={lx > p.px ? "start" : "end"} className={styles.label}>
                {p.label}
              </text>
            </g>
          );
        })}
      </svg>
    </figure>
  );
}
