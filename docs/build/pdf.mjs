// design-doc.md -> ../design-doc.pdf (kept to the 4-page limit; the load report stays a separate evidence file), via marked + system Chrome (Playwright).
import { createRequire } from "node:module";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { marked } from "marked";
const { chromium } = createRequire(import.meta.url)("../../frontend/playwright/node_modules/playwright-core");

let md = readFileSync("../design-doc.md", "utf8");
const body = marked.parse(md).replace(/src="diagrams\//g, `src="file://${resolve("..")}/diagrams/`);
const html = `<meta charset="utf-8"><style>
 body{font:11px/1.45 -apple-system,Helvetica,Arial,sans-serif;color:#222;max-width:100%}
 h1{font-size:20px} h2{font-size:15px;margin-top:18px} table{border-collapse:collapse;width:100%;margin:8px 0}
 td,th{border:1px solid #ccc;padding:3px 5px;vertical-align:top;font-size:10px} th{background:#f2f2f2}
 img{max-width:100%;max-height:440px} code{font-size:10px}</style>${body}`;
writeFileSync("design-doc.html", html);
const b = await chromium.launch({ channel: "chrome" });
const p = await b.newPage();
await p.goto("file://" + resolve("design-doc.html"));
await p.pdf({ path: "../design-doc.pdf", format: "A4", margin: { top: "14mm", bottom: "14mm", left: "12mm", right: "12mm" }, printBackground: true });
await b.close();
console.log("wrote docs/design-doc.pdf");
