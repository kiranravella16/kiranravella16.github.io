import { createServer, type IncomingMessage, type Server } from "node:http";
import { InMemoryBus, KafkaBus, type MessageBus } from "./bus/bus.js";
import { REGIONS, type Region } from "./feeds/types.js";
import { createCatalogYoga } from "./graphql/schema.js";
import { NormalizerWorker, publishFeed } from "./pipeline/normalizerWorker.js";
import { InMemoryCatalogStore } from "./store/catalogStore.js";

export interface App {
  server: Server;
  bus: MessageBus;
  worker: NormalizerWorker;
  store: InMemoryCatalogStore;
  stop(): Promise<void>;
}

async function readJson(req: IncomingMessage, limitBytes = 5 * 1024 * 1024): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > limitBytes) throw new Error("payload too large");
    chunks.push(chunk as Buffer);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/**
 * HTTP surface:
 *   POST /ingest/{us|mx|ca}?supplierId=…   body: JSON array of supplier rows → published to the raw topic
 *   POST|GET /graphql                        catalog API (GraphiQL in the browser)
 *   GET  /healthz
 */
export async function buildApp(opts: { kafkaBrokers?: string[] } = {}): Promise<App> {
  const bus: MessageBus = opts.kafkaBrokers?.length ? new KafkaBus(opts.kafkaBrokers) : new InMemoryBus();
  const store = new InMemoryCatalogStore();
  const worker = new NormalizerWorker(bus, store);
  await worker.start();

  const yoga = createCatalogYoga({ store, worker });

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const ingest = url.pathname.match(/^\/ingest\/([a-z]{2})$/);
    if (ingest && req.method === "POST") {
      const region = ingest[1]!.toUpperCase() as Region;
      const supplierId = url.searchParams.get("supplierId");
      if (!REGIONS.includes(region) || !supplierId) {
        res.writeHead(400, { "content-type": "application/json" }).end(JSON.stringify({ error: "unknown region or missing supplierId" }));
        return;
      }
      try {
        const rows = await readJson(req);
        if (!Array.isArray(rows)) throw new Error("body must be a JSON array");
        await publishFeed(bus, region, supplierId, rows);
        res.writeHead(202, { "content-type": "application/json" }).end(JSON.stringify({ accepted: rows.length }));
      } catch (e) {
        res.writeHead(400, { "content-type": "application/json" }).end(JSON.stringify({ error: String(e) }));
      }
      return;
    }
    if (url.pathname === "/healthz") {
      res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ status: "ok", items: store.size() }));
      return;
    }
    return yoga(req, res);
  });

  return {
    server,
    bus,
    worker,
    store,
    async stop() {
      await new Promise<void>((resolve) => server.close(() => resolve()));
      await bus.close();
    },
  };
}
