import { AnimatePresence, motion as m } from "framer-motion";
import { useState } from "react";

import { DesignTable } from "../features/designs/DesignTable";
import { Detection } from "../features/detection/Detection";
import { Gaps } from "../features/gaps/Gaps";
import { Tradeoff } from "../features/tradeoff/Tradeoff";
import { type Report, type SetName, minutes, num, pct, useReport } from "../shared/data/report";
import { motion } from "../shared/theme/motion";
import { ErrorState, MissingState, Skeleton } from "../shared/ui/states";
import styles from "./App.module.css";

const SETS: { key: SetName; label: string }[] = [
  { key: "heldout", label: "Held-out seeds" },
  { key: "dev", label: "Development seeds" },
];

const initialSet = (): SetName => (new URLSearchParams(window.location.search).get("set") === "dev" ? "dev" : "heldout");

export function App() {
  const [set, setSet] = useState<SetName>(initialSet);
  const { state, retry } = useReport(set);
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>Failures nobody was watching for</h1>
          <p className={styles.lede}>
            A simulated engineering organisation of 120 services and 12 teams. Each design meets the same bugs, the same alerts and the same customers;
            the world records who noticed each incident first and how long it took to fix.
          </p>
        </div>
        <div className={styles.switch} role="group" aria-label="Seed set">
          {SETS.map((s) => (
            <button key={s.key} type="button" aria-pressed={set === s.key} onClick={() => setSet(s.key)}>
              {s.label}
            </button>
          ))}
        </div>
      </header>
      <AnimatePresence mode="wait">
        <m.main key={`${set}-${state.status}`} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: motion.quick }}>
          {state.status === "loading" && <Skeleton />}
          {state.status === "error" && <ErrorState message={state.message} onRetry={retry} />}
          {state.status === "missing" && <MissingState set={set} onSwitch={set === "heldout" ? () => setSet("dev") : undefined} />}
          {state.status === "ready" && <Results report={state.report} />}
        </m.main>
      </AnimatePresence>
    </div>
  );
}

function Results({ report }: { report: Report }) {
  const d = report.designs;
  const radar = d.radar, today = d.status_quo;
  if (!radar || !today) return <MissingState set={report.set} />;
  const f = report.facts;
  const cut = radar.impact_cut_vs_status_quo ?? [0, 0, 0];
  return (
    <>
      <section className={styles.hero}>
        <p className={styles.headline}>
          Today a customer or an engineer notices {pct(1 - today.monitor_detected_share[0])} of incidents before any monitor does, after a median of{" "}
          {minutes(today.mttd_median_min[0])}. With the radar, monitors catch {pct(radar.monitor_detected_share[0])}, incidents are noticed after{" "}
          {minutes(radar.mttd_median_min[0])} and fixed after {minutes(radar.mttr_median_min[0])}, and customer impact falls by {pct(cut[0])} (95% interval{" "}
          {pct(cut[1])} to {pct(cut[2])}).
        </p>
        <Detection designs={d} />
      </section>
      <section className={styles.section}>
        <h2 className={styles.h2}>More monitors are not the answer</h2>
        <p className={styles.note}>
          A static threshold on every service and mode means {num(d.alert_everything?.pages_per_team_week[0] ?? 0, 1)} pages a team-week, most of them false,
          and still misses the silent failures. The radar spends a budget of monitors where blast radius and past luck say, and pages {num(radar.pages_per_team_week[0], 1)}{" "}
          times a week.
        </p>
        <Tradeoff designs={d} />
      </section>
      <section className={styles.section}>
        <h2 className={styles.h2}>Every design, the same incidents</h2>
        <p className={styles.note}>The ablations remove one part of the radar at a time.</p>
        <DesignTable designs={d} />
      </section>
      <section className={styles.section}>
        <h2 className={styles.h2}>What the radar adds first</h2>
        <Gaps gaps={report.top_gaps_first_seed} radar={radar} cost={report.cost} />
      </section>
      <footer className={`${styles.footer} num`}>
        Seeds {report.seeds.join(", ")}, {num(f.live_deploys)} deploys over 16 weeks, trained on 8 history weeks in which {num(f.history_caught_by_chance)} of{" "}
        {num(f.history_incidents)} incidents were noticed by chance. Config {report.config_sha}. Generated {report.generated_at.slice(0, 10)}.
      </footer>
    </>
  );
}
