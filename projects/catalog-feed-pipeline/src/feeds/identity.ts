import { createHash } from "node:crypto";

/**
 * Normalizes UPC-A (12), EAN-8, EAN-13 or GTIN-14 to a zero-padded GTIN-14 after validating the GS1 check digit.
 * The same product sold as UPC "036000291452" in the US and EAN "0036000291452" in Mexico ends up with one GTIN-14.
 */
export function normalizeGtin(input: string | null | undefined): string | null {
  if (!input) return null;
  const digits = String(input).replace(/\D/g, "");
  if (![8, 12, 13, 14].includes(digits.length)) return null;
  const gtin14 = digits.padStart(14, "0");
  return hasValidCheckDigit(gtin14) ? gtin14 : null;
}

export function hasValidCheckDigit(gtin14: string): boolean {
  const body = gtin14.slice(0, 13);
  let sum = 0;
  for (let i = 0; i < body.length; i++) {
    // GS1: weights alternate 3,1,3,1… from the left for a 14-digit key
    sum += Number(body[i]) * (i % 2 === 0 ? 3 : 1);
  }
  const check = (10 - (sum % 10)) % 10;
  return check === Number(gtin14[13]);
}

/** Namespace for catalog ids (a fixed random UUID). */
const CATALOG_NAMESPACE = "6f1c2e8a-3b4d-4c5e-9f60-7a8b9c0d1e2f";

/** RFC 4122 version-5 (SHA-1, name-based) UUID: the same input always gives the same id, on any node. */
export function uuidV5(name: string, namespace: string = CATALOG_NAMESPACE): string {
  const ns = Buffer.from(namespace.replace(/-/g, ""), "hex");
  const hash = createHash("sha1").update(ns).update(name, "utf8").digest();
  const b = hash.subarray(0, 16);
  b[6] = (b[6]! & 0x0f) | 0x50; // version 5
  b[8] = (b[8]! & 0x3f) | 0x80; // RFC 4122 variant
  const hex = b.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export const aliasKey = (supplierId: string, sku: string) => `${supplierId}:${sku}`;

export const idForGtin = (gtin14: string) => uuidV5(`gtin:${gtin14}`);
export const idForAlias = (supplierId: string, sku: string) => uuidV5(`sku:${aliasKey(supplierId, sku)}`);
