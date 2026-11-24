import { z } from "zod";
import { normalizeGtin } from "./identity.js";
import { CURRENCY_BY_REGION, type NormalizedRecord, type Region } from "./types.js";

const LB_TO_KG = 0.45359237;
const IN_TO_CM = 2.54;

const round = (n: number, dp = 2) => Math.round(n * 10 ** dp) / 10 ** dp;
const toMinor = (amount: number) => Math.round(amount * 100);
const positive = z.coerce.number().positive();
const isoDate = z.string().refine((s) => !Number.isNaN(Date.parse(s)), "invalid timestamp");

/** US suppliers: English, USD, imperial units, UPC-A. */
const UsFeed = z.object({
  sku: z.string().min(1),
  upc: z.string().optional(),
  title: z.string().min(1),
  brand: z.string().min(1),
  price_usd: positive,
  weight_lb: positive.optional(),
  dims_in: z.object({ l: positive, w: positive, h: positive }).optional(),
  updated_at: isoDate,
});

/** Mexico suppliers: Spanish field names, MXN, metric, EAN-13. */
const MxFeed = z.object({
  sku: z.string().min(1),
  codigo_barras: z.string().optional(),
  nombre: z.string().min(1),
  marca: z.string().min(1),
  precio_mxn: positive,
  peso_kg: positive.optional(),
  dimensiones_cm: z.object({ largo: positive, ancho: positive, alto: positive }).optional(),
  actualizado: isoDate,
});

/** Canada suppliers: bilingual titles (required by labelling rules), CAD, metric, GTIN. */
const CaFeed = z.object({
  sku: z.string().min(1),
  gtin: z.string().optional(),
  title_en: z.string().min(1),
  title_fr: z.string().min(1),
  brand: z.string().min(1),
  price_cad: positive,
  weight_kg: positive.optional(),
  dims_cm: z.object({ l: positive, w: positive, h: positive }).optional(),
  updated_at: isoDate,
});

export class FeedValidationError extends Error {
  constructor(
    message: string,
    readonly issues: string[],
  ) {
    super(message);
    this.name = "FeedValidationError";
  }
}

function parse<T>(schema: z.ZodType<T>, payload: unknown, region: Region): T {
  const result = schema.safeParse(payload);
  if (!result.success) {
    const issues = result.error.issues.map((i) => `${i.path.join(".") || "(root)"}: ${i.message}`);
    throw new FeedValidationError(`Invalid ${region} feed record`, issues);
  }
  return result.data;
}

function requireValidGtin(raw: string | undefined, region: Region): string | null {
  if (raw === undefined || raw === "") return null;
  const gtin = normalizeGtin(raw);
  if (!gtin) throw new FeedValidationError(`Invalid ${region} feed record`, [`gtin: '${raw}' fails GS1 check digit`]);
  return gtin;
}

export function normalize(region: Region, supplierId: string, payload: unknown): NormalizedRecord {
  const currency = CURRENCY_BY_REGION[region];
  switch (region) {
    case "US": {
      const r = parse(UsFeed, payload, region);
      return {
        gtin14: requireValidGtin(r.upc, region),
        supplierId,
        sku: r.sku,
        brand: r.brand.trim(),
        titles: { en: r.title.trim() },
        weightKg: r.weight_lb ? round(r.weight_lb * LB_TO_KG, 3) : null,
        dimensionsCm: r.dims_in
          ? { lengthCm: round(r.dims_in.l * IN_TO_CM), widthCm: round(r.dims_in.w * IN_TO_CM), heightCm: round(r.dims_in.h * IN_TO_CM) }
          : null,
        offer: { region, supplierId, sku: r.sku, priceMinor: toMinor(r.price_usd), currency, updatedAt: new Date(r.updated_at).toISOString() },
      };
    }
    case "MX": {
      const r = parse(MxFeed, payload, region);
      return {
        gtin14: requireValidGtin(r.codigo_barras, region),
        supplierId,
        sku: r.sku,
        brand: r.marca.trim(),
        titles: { es: r.nombre.trim() },
        weightKg: r.peso_kg ?? null,
        dimensionsCm: r.dimensiones_cm
          ? { lengthCm: r.dimensiones_cm.largo, widthCm: r.dimensiones_cm.ancho, heightCm: r.dimensiones_cm.alto }
          : null,
        offer: { region, supplierId, sku: r.sku, priceMinor: toMinor(r.precio_mxn), currency, updatedAt: new Date(r.actualizado).toISOString() },
      };
    }
    case "CA": {
      const r = parse(CaFeed, payload, region);
      return {
        gtin14: requireValidGtin(r.gtin, region),
        supplierId,
        sku: r.sku,
        brand: r.brand.trim(),
        titles: { en: r.title_en.trim(), fr: r.title_fr.trim() },
        weightKg: r.weight_kg ?? null,
        dimensionsCm: r.dims_cm ? { lengthCm: r.dims_cm.l, widthCm: r.dims_cm.w, heightCm: r.dims_cm.h } : null,
        offer: { region, supplierId, sku: r.sku, priceMinor: toMinor(r.price_cad), currency, updatedAt: new Date(r.updated_at).toISOString() },
      };
    }
  }
}
