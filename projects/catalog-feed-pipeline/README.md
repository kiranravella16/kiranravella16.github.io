# Catalog Feed Pipeline

An **event-driven, multi-region supplier feed ingestion pipeline** for the **US, Mexico and Canada**. It normalizes three different supplier formats into one canonical catalog, gives each product a **single item ID across every tenant and region**, and serves the result through a **GraphQL API**. Built with TypeScript, Node.js, Kafka (kafkajs), GraphQL Yoga, and Zod, and deployable to **GKE with Terraform**.

## The problem

Each region's suppliers send different data for the same product:

| | 🇺🇸 US | 🇲🇽 MX | 🇨🇦 CA |
|---|---|---|---|
| Barcode | UPC-A `036000291452` | EAN-13 `0036000291452` | GTIN-14 `00036000291452` |
| Fields | `title`, `price_usd` | `nombre`, `precio_mxn` | `title_en` + `title_fr` (required), `price_cad` |
| Units | lb, inches | kg, cm | kg, cm |
| Timestamps | UTC | `-06:00` offsets | UTC |

Without identity resolution, each region would get its own item ID for the same toilet paper. This pipeline gives it **one** ID.

## Architecture

```
 supplier uploads                                   ┌──────────────────────────────┐
 POST /ingest/us ─► supplier.feeds.raw.us  ─┐       │      NormalizerWorker        │
 POST /ingest/mx ─► supplier.feeds.raw.mx  ─┼─────► │ zod validate → unit/currency │──► catalog.items.canonical.v1
 POST /ingest/ca ─► supplier.feeds.raw.ca  ─┘       │ convert → GTIN-14 → UUIDv5   │      (only when something changed)
                     (key = supplier:sku)           │ → merge (LWW per region)     │
                                                    └──────┬───────────────┬───────┘
                                                           │               └──► supplier.feeds.dlq
                                                           ▼                     (field-level issues)
                                                     CatalogStore ◄──── GraphQL  /graphql
```

### Key design decisions

- **Deterministic canonical IDs.** GS1-validated barcodes are padded to GTIN-14, then hashed into an RFC 4122 **UUIDv5**. Every node, tenant and region computes the same ID with no central sequence and no coordination. (The implementation is checked against the RFC reference vector in the tests.)
- **Late-arriving GTINs.** A SKU-only item that later gets a barcode is **merged** into the GTIN item, and its supplier:sku alias is re-pointed.
- **Idempotent and order-safe.** Offers merge per region with last-writer-wins on the supplier's `updatedAt`. Replays are `unchanged`, older records are `stale`, and neither emits an event, so Kafka's at-least-once delivery is safe.
- **Keyed partitioning.** Raw records are keyed by `supplier:sku` and canonical events by item ID, so each item's updates stay ordered.
- **Dead-letter queue.** Bad rows (failed check digit, negative price, missing French title in Canada) go to the DLQ with path-level Zod issues, and the partition keeps moving.
- **Money as integers.** Prices are stored in minor units (cents or centavos).
- **Swappable bus.** `MessageBus` has a Kafka implementation (idempotent producer, `acks=all`) and an in-memory one for tests and demos.

## Run it

```bash
npm install
npm test              # 14 tests
npm run demo          # pipeline + GraphQL queries on the sample feeds, in-memory
npm run dev           # HTTP server on :4000 with the in-memory bus

docker compose up --build   # Kafka + Kafka UI (:8090) + service (:4000)
```

```bash
curl -X POST "localhost:4000/ingest/us?supplierId=sup-us-17"  -H 'content-type: application/json' --data @samples/us.json
curl -X POST "localhost:4000/ingest/mx?supplierId=prov-mx-04" -H 'content-type: application/json' --data @samples/mx.json
curl -X POST "localhost:4000/ingest/ca?supplierId=sup-ca-09"  -H 'content-type: application/json' --data @samples/ca.json
```

Then open `http://localhost:4000/graphql`:

```graphql
{
  itemByGtin(gtin: "036000291452") {
    id
    en: title(locale: en)
    es: title(locale: es)
    fr: title(locale: fr)
    offers { region sku price { amount currency } }
    aliases
  }
  feedStats { processed published rejected stale catalogSize }
}
```

## Deploy to GKE

`terraform/` provisions a VPC-native **private GKE cluster** (autoscaling node pool, shielded nodes, Workload Identity), **Artifact Registry**, and least-privilege service accounts.

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars    # set project_id
terraform init && terraform apply
$(terraform output -raw get_credentials)
kubectl apply -f ../k8s/deployment.yaml
```

## Tests

- `identity.test.ts`: GTIN normalization and check digits, UUIDv5 determinism and the RFC reference vector
- `pipeline.test.ts`: unit and currency conversion, timezone normalization, Canada's bilingual rule, one ID across three regions, DLQ routing, idempotent replay, stale updates, merging a SKU-only item into a GTIN item, GraphQL filters

## Next steps for production

- Replace `InMemoryCatalogStore` with PostgreSQL / Cloud Spanner, using the same interface. This is required before running more than one replica, since each replica only sees its own partitions.
- Register Avro or Protobuf schemas for the canonical topic (Schema Registry).
- Export the `feedStats` counters to Prometheus / Grafana.
