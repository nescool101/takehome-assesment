// Needs the backend on :8080 (compose up / Main). Serves the static harness on :5173.
module.exports = {
  testDir: ".",
  testMatch: "*.spec.js",
  reporter: [["list"], ["html", { outputFolder: "../../results/playwright/report", open: "never" }]],
  outputDir: "../../results/playwright/traces",
  use: { channel: "chrome", baseURL: "http://localhost:5173", trace: "on" },
  webServer: { command: "python3 -m http.server 5173 -d ..", url: "http://localhost:5173/index.html", reuseExistingServer: true },
};
