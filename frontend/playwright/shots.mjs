// One-off: capture presentation screenshots of the live observability views into results/observability/.
// Run: node shots.mjs  (backend + grafana + jaeger must be up; publish a load batch just before)
import { chromium } from "playwright-core";
import fs from "node:fs";

const out = "../../results/observability";
fs.mkdirSync(out, { recursive: true });

const browser = await chromium.launch({ channel: "chrome", headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
const shot = async (url, file, wait) => {
  await page.goto(url, { waitUntil: "domcontentloaded", timeout: 30000 }).catch(e => console.error(url, e.message));
  await page.waitForTimeout(wait);
  await page.screenshot({ path: `${out}/${file}` });
  console.log("saved", file);
};

await shot("http://localhost:5173/dashboard.html", "live-dashboard.png", 4000);
await shot("http://localhost:3000/d/fanout-load?kiosk", "grafana.png", 8000);
await shot("http://localhost:16686/search?service=fanout-worker&limit=20", "jaeger-traces.png", 6000);
await browser.close();
