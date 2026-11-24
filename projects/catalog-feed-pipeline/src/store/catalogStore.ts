import { aliasKey, idForAlias, idForGtin } from "../feeds/identity.js";
import type { CanonicalItem, NormalizedRecord, Region } from "../feeds/types.js";

export type UpsertOutcome = "created" | "updated" | "stale" | "unchanged";

export interface UpsertResult {
  item: CanonicalItem;
  outcome: UpsertOutcome;
}

export interface ItemFilter {
  region?: Region;
  brand?: string;
  search?: string;
  first?: number;
  offset?: number;
}

export interface CatalogStore {
  upsert(record: NormalizedRecord): UpsertResult;
  get(id: string): CanonicalItem | undefined;
  byGtin(gtin14: string): CanonicalItem | undefined;
  list(filter?: ItemFilter): CanonicalItem[];
  size(): number;
}

/**
 * Identity resolution + merge.
 *
 * Id resolution order: (1) GTIN-14 → deterministic UUIDv5, the same for every tenant and region;
 * (2) a known supplier:sku alias; (3) a deterministic id from supplier:sku. A later record that brings a GTIN for
 * a SKU-only item is merged into the GTIN item and the alias is re-pointed.
 *
 * Offers are merged per region with last-writer-wins on the supplier's updatedAt, so replays and out-of-order
 * Kafka deliveries are idempotent.
 */
export class InMemoryCatalogStore implements CatalogStore {
  private readonly items = new Map<string, CanonicalItem>();
  private readonly aliases = new Map<string, string>();

  upsert(rec: NormalizedRecord): UpsertResult {
    const alias = aliasKey(rec.supplierId, rec.sku);
    const id = rec.gtin14 ? idForGtin(rec.gtin14) : (this.aliases.get(alias) ?? idForAlias(rec.supplierId, rec.sku));

    // a SKU-only item that now has a GTIN: fold it into the GTIN-keyed item
    const previousId = this.aliases.get(alias);
    if (previousId && previousId !== id) {
      this.mergeInto(previousId, id);
    }

    const existing = this.items.get(id);
    if (!existing) {
      const item: CanonicalItem = {
        id,
        gtin14: rec.gtin14,
        brand: rec.brand,
        titles: { ...rec.titles },
        weightKg: rec.weightKg,
        dimensionsCm: rec.dimensionsCm,
        offers: { [rec.offer.region]: rec.offer },
        aliases: [alias],
        updatedAt: rec.offer.updatedAt,
      };
      this.items.set(id, item);
      this.aliases.set(alias, id);
      return { item, outcome: "created" };
    }

    const current = existing.offers[rec.offer.region];
    if (current && Date.parse(current.updatedAt) > Date.parse(rec.offer.updatedAt)) {
      return { item: existing, outcome: "stale" };
    }

    const before = JSON.stringify(existing);
    existing.gtin14 ??= rec.gtin14;
    existing.titles = { ...existing.titles, ...rec.titles };
    existing.weightKg ??= rec.weightKg;
    existing.dimensionsCm ??= rec.dimensionsCm;
    existing.offers[rec.offer.region] = rec.offer;
    if (!existing.aliases.includes(alias)) existing.aliases.push(alias);
    if (Date.parse(rec.offer.updatedAt) > Date.parse(existing.updatedAt)) existing.updatedAt = rec.offer.updatedAt;
    this.aliases.set(alias, id);

    return { item: existing, outcome: JSON.stringify(existing) === before ? "unchanged" : "updated" };
  }

  get(id: string) {
    return this.items.get(id);
  }

  byGtin(gtin14: string) {
    return this.items.get(idForGtin(gtin14));
  }

  list(filter: ItemFilter = {}): CanonicalItem[] {
    const q = filter.search?.toLowerCase();
    const result = [...this.items.values()].filter(
      (i) =>
        (!filter.region || i.offers[filter.region]) &&
        (!filter.brand || i.brand.toLowerCase() === filter.brand.toLowerCase()) &&
        (!q || Object.values(i.titles).some((t) => t?.toLowerCase().includes(q))),
    );
    result.sort((a, b) => a.id.localeCompare(b.id));
    const offset = filter.offset ?? 0;
    return result.slice(offset, offset + (filter.first ?? 20));
  }

  size() {
    return this.items.size;
  }

  private mergeInto(fromId: string, toId: string) {
    const from = this.items.get(fromId);
    if (!from) return;
    this.items.delete(fromId);
    const to = this.items.get(toId);
    if (!to) {
      this.items.set(toId, { ...from, id: toId });
    } else {
      to.titles = { ...from.titles, ...to.titles };
      to.weightKg ??= from.weightKg;
      to.dimensionsCm ??= from.dimensionsCm;
      for (const [region, offer] of Object.entries(from.offers) as [Region, NonNullable<CanonicalItem["offers"][Region]>][]) {
        const existing = to.offers[region];
        if (!existing || Date.parse(existing.updatedAt) < Date.parse(offer.updatedAt)) to.offers[region] = offer;
      }
      to.aliases = [...new Set([...to.aliases, ...from.aliases])];
    }
    for (const a of from.aliases) this.aliases.set(a, toId);
  }
}
