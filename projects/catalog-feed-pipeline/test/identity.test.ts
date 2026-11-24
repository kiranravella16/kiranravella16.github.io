import { describe, expect, it } from "vitest";
import { hasValidCheckDigit, idForGtin, normalizeGtin, uuidV5 } from "../src/feeds/identity.js";

describe("GTIN normalization", () => {
  it("pads UPC-A, EAN-13 and GTIN-14 forms of the same product to one GTIN-14", () => {
    expect(normalizeGtin("036000291452")).toBe("00036000291452");
    expect(normalizeGtin("0036000291452")).toBe("00036000291452");
    expect(normalizeGtin("00036000291452")).toBe("00036000291452");
    expect(normalizeGtin("0 36000 29145 2")).toBe("00036000291452");
  });

  it("rejects bad check digits and wrong lengths", () => {
    expect(normalizeGtin("036000291453")).toBeNull();
    expect(normalizeGtin("12345")).toBeNull();
    expect(normalizeGtin("")).toBeNull();
    expect(hasValidCheckDigit("00036000291452")).toBe(true);
  });

  it("accepts the other sample barcodes", () => {
    expect(normalizeGtin("194253397199")).not.toBeNull();
  });
});

describe("canonical ids", () => {
  it("are RFC 4122 v5 UUIDs and deterministic", () => {
    const id = idForGtin("00036000291452");
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(idForGtin("00036000291452")).toBe(id);
    expect(idForGtin("00194253397199")).not.toBe(id);
  });

  it("matches the RFC 4122 reference vector for the DNS namespace", () => {
    // uuid v5("www.example.com", DNS) — the widely published reference value
    expect(uuidV5("www.example.com", "6ba7b810-9dad-11d1-80b4-00c04fd430c8")).toBe("2ed6657d-e927-568b-95e1-2665a8aea6a2");
  });
});
