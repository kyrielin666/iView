import { cp, mkdir, rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const output = fileURLToPath(new URL("../dist", import.meta.url));

await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
for (const file of ["index.html", "styles.css", "app.js"]) {
  await cp(`${root}/${file}`, `${output}/${file}`);
}
console.log("Built frontend into dist/");
