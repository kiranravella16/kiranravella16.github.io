/**
 * End-to-end demo on the in-memory bus: publishes the three regional sample feeds, then runs GraphQL queries.
 *   npm run demo
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { InMemoryBus } from "./bus/bus.js";
import { execute } from "./graphql/schema.js";
import { NormalizerWorker, publishFeed, TOPICS } from "./pipeline/normalizerWorker.js";
import { InMemoryCatalogStore } from "./store/catalogStore.js";
import type { Region } from "./feeds/types.js";

const sample = (r: string) =>
  JSON.parse(readFileSync(fileURLToPath(new URL(`../samples/${r}.json`, import.meta.url)), "utf8")) as unknown[];

const bus = new InMemoryBus();
const store = new InMemoryCatalogStore();
const worker = new NormalizerWorker(bus, store);
await worker.start();

for (const [region, supplier] of [["US", "sup-us-17"], ["MX", "prov-mx-04"], ["CA", "sup-ca-09"]] as [Region, string][]) {
  await publishFeed(bus, region, supplier, sample(region.toLowerCase()));
}

const run = async (query: string) =>
  console.log(JSON.stringify((await execute({ store, worker }, query)).data, null, 2));

console.log("\n# One product, three regions, one id (UPC-A in US, EAN-13 in MX, GTIN-14 in CA)");
await run(`{ itemByGtin(gtin: "036000291452") {
  id gtin brand en: title(locale: en) es: title(locale: es) fr: title(locale: fr)
  offers { region sku price { amount currency } } aliases } }`);

console.log("\n# Pipeline stats");
await run(`{ feedStats { processed published rejected catalogSize byRegion { region processed rejected } } }`);

console.log("\n# Dead-letter queue");
console.log(JSON.stringify(bus.messages(TOPICS.dlq).map((d) => ({ reason: d.reason, issues: d.issues })), null, 2));
