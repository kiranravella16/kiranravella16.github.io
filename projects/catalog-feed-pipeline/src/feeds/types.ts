export const REGIONS = ["US", "MX", "CA"] as const;
export type Region = (typeof REGIONS)[number];

export type Currency = "USD" | "MXN" | "CAD";

export const CURRENCY_BY_REGION: Record<Region, Currency> = { US: "USD", MX: "MXN", CA: "CAD" };

/** Envelope for a raw supplier record as it arrives on `supplier.feeds.raw.<region>`. */
export interface RawFeedMessage {
  region: Region;
  supplierId: string;
  receivedAt: string;
  payload: unknown;
}

export interface Offer {
  region: Region;
  supplierId: string;
  sku: string;
  /** Integer minor units (cents / centavos) to avoid float rounding. */
  priceMinor: number;
  currency: Currency;
  updatedAt: string;
}

export interface Dimensions {
  lengthCm: number;
  widthCm: number;
  heightCm: number;
}

/** A supplier record after region-specific parsing, unit conversion and validation. */
export interface NormalizedRecord {
  gtin14: string | null;
  supplierId: string;
  sku: string;
  brand: string;
  titles: Partial<Record<"en" | "es" | "fr", string>>;
  weightKg: number | null;
  dimensionsCm: Dimensions | null;
  offer: Offer;
}

/** One item across every tenant and region, under one canonical id. */
export interface CanonicalItem {
  id: string;
  gtin14: string | null;
  brand: string;
  titles: Partial<Record<"en" | "es" | "fr", string>>;
  weightKg: number | null;
  dimensionsCm: Dimensions | null;
  offers: Partial<Record<Region, Offer>>;
  /** supplierId:sku pairs that resolve to this item. */
  aliases: string[];
  updatedAt: string;
}
