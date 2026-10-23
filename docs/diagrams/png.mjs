// SVG -> PNG (for the PDF) using system Chrome through the Playwright install in frontend/playwright.
import { createRequire } from "node:module";
import { readdirSync } from "node:fs";
import { resolve } from "node:path";
const { chromium } = createRequire(import.meta.url)("../../frontend/playwright/node_modules/playwright-core");
const b = await chromium.launch({ channel: "chrome" });
const p = await b.newPage({ deviceScaleFactor: 2 });
for (const f of readdirSync("out").filter(f => f.endsWith(".svg"))) {
  await p.goto("file://" + resolve("out", f));
  const el = await p.locator("svg").first();
  await el.screenshot({ path: `out/${f.replace(".svg", ".png")}` });
  console.log("png", f);
}
await b.close();
