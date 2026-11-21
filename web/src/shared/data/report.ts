import { useCallback, useEffect, useState } from "react";

export type SetName = "heldout" | "dev";
export type Interval = [number, number, number];

export interface DesignRow {
  label: string;
  impact_per_week: Interval;
  impact_cut_vs_status_quo?: Interval;
  change_failure_rate: Interval;
  monitor_detected_share: Interval;
  mttd_median_min: Interval;
  mttr_median_min: Interval;
  pages_per_team_week: Interval;
  false_page_share: Interval;
  recurrences_per_week: Interval;
  incidents_per_week: Interval;
  test_hours_per_week: number;
  caught_in_canary: number;
  incidents: number;
}

export interface Gap {
  service: string;
  mode: string;
  caught_by_chance: number;
  score: number;
  fix: string;
}

export interface Report {
  set: SetName;
  seeds: number[];
  config_sha: string;
  generated_at: string;
  facts: { services: number; teams: number; existing_monitors: number; history_incidents: number; history_caught_by_chance: number; live_deploys: number; gaps: number };
  designs: Record<string, DesignRow>;
  cost: { eur_per_month: number; waste_eur_per_month: number; saved_if_teams_act_eur_per_month: number };
  top_gaps_first_seed: Gap[];
}

export type LoadState = { status: "loading" } | { status: "error"; message: string } | { status: "missing" } | { status: "ready"; report: Report };

/** Loads reports/<set>.json as written by `./gradlew :eval:evaluate` or `:eval:heldout`. */
export function useReport(set: SetName): { state: LoadState; retry: () => void } {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    setState({ status: "loading" });
    fetch(`./reports/${set}.json`)
      .then(async (res) => {
        if (!live) return;
        if (res.status === 404) return setState({ status: "missing" });
        if (!res.ok) throw new Error(`The report did not load (HTTP ${res.status}).`);
        const report = (await res.json()) as Report;
        if (live) setState({ status: "ready", report });
      })
      .catch((err: unknown) => live && setState({ status: "error", message: err instanceof Error ? err.message : String(err) }));
    return () => {
      live = false;
    };
  }, [set, attempt]);
  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { state, retry };
}

export type Tone = "radar" | "today" | "other";
export const toneOf = (key: string): Tone => (key === "radar" ? "radar" : key === "status_quo" ? "today" : "other");

/** Short names for the charts; the table shows each design's full label. */
export const SHORT: Record<string, string> = {
  status_quo: "Today",
  alert_everything: "Alert on everything",
  radar: "This radar",
  no_gate: "No deploy gate",
  unranked: "Monitors at random",
  static_only: "Static thresholds only",
  no_tracking: "No action tracking",
  no_brief: "No incident brief",
};

export const pct = (x: number, d = 0) => `${(x * 100).toFixed(d)}%`;
export const num = (x: number, d = 0) => new Intl.NumberFormat("en-GB", { maximumFractionDigits: d, minimumFractionDigits: d }).format(x);
export const words = (s: string) => s.toLowerCase().replace(/_/g, " ");

/** Minutes as the on-call engineer would say them. */
export function minutes(m: number): string {
  if (m < 90) return `${Math.round(m)} min`;
  if (m < 48 * 60) return `${num(m / 60, m < 600 ? 1 : 0)} h`;
  return `${num(m / 1440, 1)} days`;
}
