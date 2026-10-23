const { test, expect } = require("@playwright/test");
const fs = require("fs");
const OUT = "../../results/playwright";
fs.mkdirSync(OUT, { recursive: true });

// Evidence: every API call the page makes (method, url, status) is written to results/playwright/<name>-calls.json
function recordCalls(page, name) {
  const calls = [];
  page.on("response", r => {
    if (r.url().includes(":8080")) calls.push({ method: r.request().method(), url: r.url(), status: r.status() });
  });
  page.on("requestfailed", r => {
    if (r.url().includes(":8080")) calls.push({ method: r.method(), url: r.url(), failed: r.failure().errorText });
  });
  return () => fs.writeFileSync(`${OUT}/${name}-calls.json`, JSON.stringify(calls, null, 2));
}

test("publish -> badge + summary appear, calls recorded", async ({ page }) => {
  const save = recordCalls(page, "happy");
  await page.goto("/index.html");
  await expect(page.locator("#badge")).toBeHidden();
  await page.screenshot({ path: `${OUT}/1-before-publish.png` });

  await page.click("#publish");
  await expect(page.locator("#badge")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId("summary")).toContainText("Template update available: tpl-audit@v");
  await page.screenshot({ path: `${OUT}/2-badge-and-summary.png` });

  await page.click("#decline");
  await expect(page.locator("#badge")).toBeHidden({ timeout: 5_000 });
  await page.screenshot({ path: `${OUT}/3-after-decline.png` });

  save();
  const calls = JSON.parse(fs.readFileSync(`${OUT}/happy-calls.json`));
  expect(calls.some(c => c.method === "POST" && c.url.endsWith("/admin/publishes") && c.status === 202)).toBe(true);
  expect(calls.some(c => c.url.includes("/engagements/e1/status") && c.status === 200)).toBe(true);
});

test("backend down -> harness shows the error, calls recorded", async ({ page }) => {
  const save = recordCalls(page, "backend-down");
  await page.goto("/index.html?api=http://localhost:9"); // nothing listens on :9
  await expect(page.getByTestId("error")).toContainText("API error", { timeout: 5_000 });
  await page.screenshot({ path: `${OUT}/4-error-backend-down.png` });
  save();
});

test("API returns 500 -> harness shows the error", async ({ page }) => {
  await page.route("**/engagements/e1/status", r =>
    r.fulfill({ status: 500, contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: '{"error":"boom"}' }));
  await page.goto("/index.html");
  await expect(page.getByTestId("error")).toContainText("boom", { timeout: 5_000 });
  await page.screenshot({ path: `${OUT}/5-error-api-500.png` });
});
