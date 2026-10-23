// Renders docs/diagrams/*.mmd to out/*.svg with beautiful-mermaid (npm i in this folder first), then out/*.png via system Chrome.
import { renderMermaidSVG } from "beautiful-mermaid";
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";

mkdirSync("out", { recursive: true });
for (const f of readdirSync(".").filter(f => f.endsWith(".mmd"))) {
  const name = f.replace(".mmd", "");
  writeFileSync(`out/${name}.svg`, renderMermaidSVG(readFileSync(f, "utf8")));
  console.log("rendered", name);
}
