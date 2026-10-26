// Needs the backend on :8080 (compose up / Main). Serves the static harness on :5173 (PW_PORT to change).
const PORT = process.env.PW_PORT || 5173;
module.exports = {
  testDir: ".",
  testMatch: "*.spec.js",
  reporter: [["list"], ["html", { outputFolder: "../../results/playwright/report", open: "never" }]],
  outputDir: "../../results/playwright/traces",
  use: { channel: "chrome", baseURL: `http://localhost:${PORT}`, trace: "on" },
  webServer: { command: `python3 -m http.server ${PORT} -d ..`, url: `http://localhost:${PORT}/index.html`, reuseExistingServer: true },
};
