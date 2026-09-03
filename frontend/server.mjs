import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL(".", import.meta.url));
const parityFile = fileURLToPath(new URL("../docs/migration/feature-parity.md", import.meta.url));
const port = Number(process.env.PORT || 3000);
const backend = process.env.IVIEW_BACKEND_URL || "http://localhost:8080";

const contentTypes = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
};

function sendJson(response, status, value) {
  response.writeHead(status, { "Content-Type": contentTypes[".json"] });
  response.end(JSON.stringify(value));
}

async function paritySummary() {
  const markdown = await readFile(parityFile, "utf8");
  const rows = markdown
    .split(/\r?\n/)
    .filter((line) => /^\|.+\|\s*(NOT_STARTED|IN_PROGRESS|VERIFIED)\s*\|/.test(line))
    .map((line) => {
      const cells = line.split("|").slice(1, -1).map((cell) => cell.trim());
      return { capability: cells[0], reference: cells[1], status: cells[2], evidence: cells[3] };
    });
  const counts = { total: rows.length, NOT_STARTED: 0, IN_PROGRESS: 0, VERIFIED: 0 };
  rows.forEach((row) => { counts[row.status] += 1; });
  return { counts, rows };
}

async function proxyBackend(request, response) {
  try {
    const body = request.method === "GET" || request.method === "HEAD" ? undefined : await readRequestBody(request);
    const upstream = await fetch(new URL(request.url, backend), {
      method: request.method,
      headers: {
        accept: request.headers.accept || "application/json",
        ...(request.headers["content-type"] ? { "content-type": request.headers["content-type"] } : {}),
      },
      body,
    });
    response.writeHead(upstream.status, {
      "Content-Type": upstream.headers.get("content-type") || contentTypes[".json"],
    });
    response.end(Buffer.from(await upstream.arrayBuffer()));
  } catch {
    sendJson(response, 503, { status: "DOWN", message: "iView 后端未启动" });
  }
}

function readRequestBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => resolve(chunks.length ? Buffer.concat(chunks) : undefined));
    request.on("error", reject);
  });
}

const server = createServer(async (request, response) => {
  if (request.url === "/meta/parity") {
    try {
      sendJson(response, 200, await paritySummary());
    } catch (error) {
      sendJson(response, 500, { message: error.message });
    }
    return;
  }
  if (request.url.startsWith("/api/") || request.url.startsWith("/actuator/")) {
    await proxyBackend(request, response);
    return;
  }

  const requestedPath = request.url === "/" ? "index.html" : request.url.split("?")[0].slice(1);
  const safePath = normalize(requestedPath).replace(/^(\.\.[/\\])+/, "");
  try {
    const body = await readFile(join(root, safePath));
    response.writeHead(200, { "Content-Type": contentTypes[extname(safePath)] || "application/octet-stream" });
    response.end(body);
  } catch {
    response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Not found");
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`iView frontend: http://localhost:${port}`);
});
