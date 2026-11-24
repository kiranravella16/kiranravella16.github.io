import { createSchema, createYoga } from "graphql-yoga";
import { normalizeGtin } from "../feeds/identity.js";
import type { CanonicalItem, Offer, Region } from "../feeds/types.js";
import type { NormalizerWorker } from "../pipeline/normalizerWorker.js";
import type { CatalogStore } from "../store/catalogStore.js";

export interface Context {
  store: CatalogStore;
  worker: NormalizerWorker;
}

const typeDefs = /* GraphQL */ `
  enum Region { US MX CA }
  enum Locale { en es fr }

  type Money {
    amount: Float!
    minorUnits: Int!
    currency: String!
  }

  type Offer {
    region: Region!
    supplierId: String!
    sku: String!
    price: Money!
    updatedAt: String!
  }

  type Dimensions { lengthCm: Float! widthCm: Float! heightCm: Float! }

  type Item {
    "Canonical id, identical across tenants and regions"
    id: ID!
    gtin: String
    brand: String!
    "Title in the requested locale, falling back to en → es → fr"
    title(locale: Locale = en): String
    weightKg: Float
    dimensionsCm: Dimensions
    offers: [Offer!]!
    offer(region: Region!): Offer
    aliases: [String!]!
    updatedAt: String!
  }

  type RegionStats { region: Region! processed: Int! published: Int! rejected: Int! stale: Int! unchanged: Int! }
  type FeedStats { processed: Int! published: Int! rejected: Int! stale: Int! unchanged: Int! byRegion: [RegionStats!]! catalogSize: Int! }

  type Query {
    item(id: ID!): Item
    "Accepts UPC-A, EAN-13 or GTIN-14"
    itemByGtin(gtin: String!): Item
    items(region: Region, brand: String, search: String, first: Int = 20, offset: Int = 0): [Item!]!
    feedStats: FeedStats!
  }
`;

const money = (o: Offer) => ({ amount: o.priceMinor / 100, minorUnits: o.priceMinor, currency: o.currency });

export const schema = createSchema<Context>({
  typeDefs,
  resolvers: {
    Query: {
      item: (_: unknown, { id }: { id: string }, ctx: Context) => ctx.store.get(id) ?? null,
      itemByGtin: (_: unknown, { gtin }: { gtin: string }, ctx: Context) => {
        const g = normalizeGtin(gtin);
        return g ? (ctx.store.byGtin(g) ?? null) : null;
      },
      items: (
        _: unknown,
        args: { region?: Region; brand?: string; search?: string; first: number; offset: number },
        ctx: Context,
      ) => ctx.store.list({ ...args, first: Math.min(args.first, 100) }),
      feedStats: (_: unknown, __: unknown, ctx: Context) => ({ ...ctx.worker.totals(), catalogSize: ctx.store.size() }),
    },
    Item: {
      gtin: (i: CanonicalItem) => i.gtin14,
      title: (i: CanonicalItem, { locale }: { locale: "en" | "es" | "fr" }) =>
        i.titles[locale] ?? i.titles.en ?? i.titles.es ?? i.titles.fr ?? null,
      offers: (i: CanonicalItem) => Object.values(i.offers),
      offer: (i: CanonicalItem, { region }: { region: Region }) => i.offers[region] ?? null,
    },
    Offer: { price: money },
  },
});

export const createCatalogYoga = (ctx: Context) =>
  createYoga<Context>({ schema, context: () => ctx, graphqlEndpoint: "/graphql", logging: false });

/** Executes a query in-process through Yoga (used by tests and the demo). */
export async function execute(ctx: Context, query: string, yoga = createCatalogYoga(ctx)) {
  const res = await yoga.fetch("http://localhost/graphql", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ query }),
  });
  return (await res.json()) as { data?: any; errors?: { message: string }[] };
}
