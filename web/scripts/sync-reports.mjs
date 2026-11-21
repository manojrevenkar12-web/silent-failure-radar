// Copies the generated reports into public/ so the dashboard shows exactly what the run wrote.
import { copyFileSync, existsSync, mkdirSync } from "node:fs";

const src = new URL("../../reports/", import.meta.url);
const dest = new URL("../public/reports/", import.meta.url);
mkdirSync(dest, { recursive: true });
for (const name of ["dev.json", "heldout.json"]) {
  if (existsSync(new URL(name, src))) copyFileSync(new URL(name, src), new URL(name, dest));
}
