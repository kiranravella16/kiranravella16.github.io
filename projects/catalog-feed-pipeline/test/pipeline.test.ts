import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, it } from "vitest";
import { InMemoryBus } from "../src/bus/bus.js";
import { normalize } from "../src/feeds/normalizers.js";
import { execute } from "../src/graphql/schema.js";
import { NormalizerWorker, publishFeed, TOPICS } from "../src/pipeline/normalizerWorker.js";
import { InMemoryCatalogStore } from "../src/store/catalogStore.js";

const sample = (r: string) => JSON.parse(readFileSync(new URL(`../samples/${r}.json`, import.meta.url), "utf8"));

let bus: InMemoryBus;
let store: InMemoryCatalogStore;
let worker: NormalizerWorker;

beforeEach(async () => {
  bus = new InMemoryBus();
  store = new InMemoryCatalogStore();
  worker = new NormalizerWorker(bus, store);
  await worker.start();
});

const gql = async (source: string) => {
  const res = await execute({ store, worker }, source);
  expect(res.errors).toBeUndefined();
  return res.data as any;
};

describe("regional normalizers", () => {
  it("converts US imperial units and USD to metric and minor units", () => {
    const r = normalize("US", "s1", sample("us")[0]);
    expect(r.weightKg).toBeCloseTo(1.905, 3);
    expect(r.dimensionsCm).toEqual({ lengthCm: 34.29, widthCm: 22.86, heightCm: 24.13 });
    expect(r.offer).toMatchObject({ priceMinor: 1497, currency: "USD" });
  });

  it("maps Spanish MX fields and normalizes offset timestamps to UTC", () => {
    const r = normalize("MX", "s2", sample("mx")[0]);
    expect(r.titles.es).toContain("Papel Higiénico");
    expect(r.offer).toMatchObject({ priceMinor: 18950, currency: "MXN", updatedAt: "2026-11-21T14:30:00.000Z" });
  });

  it("keeps both CA titles", () => {
    const r = normalize("CA", "s3", sample("ca")[0]);
    expect(r.titles).toEqual({ en: "Ultra Soft Bath Tissue, 12 Rolls", fr: "Papier hygiénique ultra doux, 12 rouleaux" });
  });

  it("rejects records missing required bilingual titles in CA", () => {
    expect(() => normalize("CA", "s3", { ...sample("ca")[0], title_fr: undefined })).toThrow(/Invalid CA/);
  });
});

describe("pipeline", () => {
  beforeEach(async () => {
    await publishFeed(bus, "US", "sup-us", sample("us"));
    await publishFeed(bus, "MX", "prov-mx", sample("mx"));
    await publishFeed(bus, "CA", "sup-ca", sample("ca"));
  });

  it("unifies one product across three regions and suppliers under one canonical id", async () => {
    const data = await gql(`{ itemByGtin(gtin: "0036000291452") {
      id en: title(locale: en) es: title(locale: es) fr: title(locale: fr)
      offers { region sku price { amount currency } } aliases } }`);
    const item = data.itemByGtin;
    expect(item.offers.map((o: any) => o.region).sort()).toEqual(["CA", "MX", "US"]);
    expect(item.aliases).toEqual(["sup-us:US-TP-001", "prov-mx:MX-PH-77", "sup-ca:CA-TP-12"]);
    expect(item.es).toMatch(/^Papel/);
    expect(item.fr).toMatch(/^Papier/);
    expect(item.offers.find((o: any) => o.region === "MX").price).toEqual({ amount: 189.5, currency: "MXN" });
  });

  it("routes invalid rows to the DLQ with field-level issues and keeps going", async () => {
    const dlq = bus.messages(TOPICS.dlq);
    expect(dlq).toHaveLength(2);
    expect(dlq[0].issues[0]).toMatch(/GS1 check digit/);
    expect(dlq[1].issues.join(" ")).toMatch(/title/);
    expect(dlq[1].issues.join(" ")).toMatch(/price_usd/);

    const { feedStats } = await gql(`{ feedStats { processed published rejected catalogSize } }`);
    expect(feedStats).toEqual({ processed: 8, published: 6, rejected: 2, catalogSize: 3 });
  });

  it("is idempotent on replay and ignores out-of-order stale updates", async () => {
    const before = bus.messages(TOPICS.canonical).length;
    await publishFeed(bus, "CA", "sup-ca", sample("ca")); // exact replay
    await publishFeed(bus, "CA", "sup-ca", [{ ...sample("ca")[0], price_cad: 1.0, updated_at: "2026-01-01T00:00:00Z" }]);
    expect(bus.messages(TOPICS.canonical).length).toBe(before);

    const { itemByGtin } = await gql(`{ itemByGtin(gtin: "036000291452") { offer(region: CA) { price { amount } } } }`);
    expect(itemByGtin.offer.price.amount).toBe(17.49);
    const stats = worker.stats.get("CA")!;
    expect(stats.stale).toBe(1);
    expect(stats.unchanged).toBe(2);
  });

  it("merges a SKU-only item into the GTIN item once the supplier sends a barcode", async () => {
    const skuOnly = sample("mx")[1];
    const { items: before } = await gql(`{ items(search: "Café") { id gtin } }`);
    expect(before[0].gtin).toBeNull();

    await publishFeed(bus, "MX", "prov-mx", [{ ...skuOnly, codigo_barras: "7501055300075", actualizado: "2026-11-23T00:00:00Z" }]);

    const { items: after } = await gql(`{ items(search: "Café") { id gtin aliases } }`);
    expect(after).toHaveLength(1);
    expect(after[0].gtin).toBe("07501055300075");
    expect(after[0].id).not.toBe(before[0].id);
    expect(after[0].aliases).toEqual(["prov-mx:MX-CAFE-5"]);
  });

  it("filters by region and brand", async () => {
    const { items } = await gql(`{ items(region: MX, brand: "cottonelle") { brand offer(region: MX) { sku } } }`);
    expect(items).toEqual([{ brand: "Cottonelle", offer: { sku: "MX-PH-77" } }]);
  });
});
