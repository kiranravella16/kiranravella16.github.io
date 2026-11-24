import type { MessageBus } from "../bus/bus.js";
import { FeedValidationError, normalize } from "../feeds/normalizers.js";
import { REGIONS, type RawFeedMessage, type Region } from "../feeds/types.js";
import type { CatalogStore } from "../store/catalogStore.js";

export const TOPICS = {
  raw: (r: Region) => `supplier.feeds.raw.${r.toLowerCase()}`,
  canonical: "catalog.items.canonical.v1",
  dlq: "supplier.feeds.dlq",
} as const;

export interface RegionStats {
  region: Region;
  processed: number;
  published: number;
  rejected: number;
  stale: number;
  unchanged: number;
}

export interface DlqRecord {
  reason: string;
  issues: string[];
  original: RawFeedMessage | string;
  failedAt: string;
}

/**
 * Consumes every regional raw-feed topic, normalizes and validates each record, resolves its canonical id,
 * merges it into the catalog, and emits a canonical change event, but only when something actually changed.
 * Bad records go to a dead-letter topic with the validation issues so supplier onboarding can fix them,
 * and the partition keeps moving.
 */
export class NormalizerWorker {
  readonly stats = new Map<Region, RegionStats>(
    REGIONS.map((r) => [r, { region: r, processed: 0, published: 0, rejected: 0, stale: 0, unchanged: 0 }]),
  );

  constructor(
    private readonly bus: MessageBus,
    private readonly store: CatalogStore,
  ) {}

  async start(groupId = "catalog-normalizer") {
    await this.bus.subscribe(REGIONS.map(TOPICS.raw), groupId, async (msg) => this.handle(msg.value));
  }

  async handle(rawValue: string) {
    let raw: RawFeedMessage;
    try {
      raw = JSON.parse(rawValue) as RawFeedMessage;
    } catch {
      await this.deadLetter("unparseable-json", [], rawValue);
      return;
    }
    const stats = this.stats.get(raw.region);
    if (!stats) {
      await this.deadLetter("unknown-region", [`region: ${String(raw.region)}`], raw);
      return;
    }
    stats.processed++;
    try {
      const record = normalize(raw.region, raw.supplierId, raw.payload);
      const { item, outcome } = this.store.upsert(record);
      if (outcome === "stale") stats.stale++;
      else if (outcome === "unchanged") stats.unchanged++;
      else {
        await this.bus.publish(TOPICS.canonical, item.id, { type: `item.${outcome}`, region: raw.region, item });
        stats.published++;
      }
    } catch (e) {
      stats.rejected++;
      if (e instanceof FeedValidationError) await this.deadLetter("validation", e.issues, raw);
      else await this.deadLetter("processing-error", [String(e)], raw);
    }
  }

  totals() {
    const all = [...this.stats.values()];
    const sum = (k: keyof Omit<RegionStats, "region">) => all.reduce((n, s) => n + s[k], 0);
    return {
      processed: sum("processed"),
      published: sum("published"),
      rejected: sum("rejected"),
      stale: sum("stale"),
      unchanged: sum("unchanged"),
      byRegion: all,
    };
  }

  private async deadLetter(reason: string, issues: string[], original: RawFeedMessage | string) {
    const rec: DlqRecord = { reason, issues, original, failedAt: new Date().toISOString() };
    const key = typeof original === "string" ? "unknown" : `${original.supplierId}`;
    await this.bus.publish(TOPICS.dlq, key, rec);
  }
}

/** Producer-side helper used by the ingest endpoint and demo: wraps supplier rows into raw feed messages. */
export async function publishFeed(bus: MessageBus, region: Region, supplierId: string, rows: unknown[]) {
  for (const payload of rows) {
    const sku = (payload as { sku?: string })?.sku ?? "unknown";
    const msg: RawFeedMessage = { region, supplierId, receivedAt: new Date().toISOString(), payload };
    await bus.publish(TOPICS.raw(region), `${supplierId}:${sku}`, msg);
  }
}
