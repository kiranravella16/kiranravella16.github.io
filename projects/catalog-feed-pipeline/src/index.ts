import { buildApp } from "./app.js";

const port = Number(process.env.PORT ?? 4000);
const brokers = process.env.KAFKA_BROKERS?.split(",").filter(Boolean);

const app = await buildApp({ kafkaBrokers: brokers });
app.server.listen(port, () => {
  console.log(`catalog-feed-pipeline listening on :${port} (bus: ${brokers?.length ? `kafka ${brokers.join(",")}` : "in-memory"})`);
  console.log(`  GraphQL → http://localhost:${port}/graphql`);
});

for (const sig of ["SIGINT", "SIGTERM"] as const) {
  process.on(sig, async () => {
    await app.stop();
    process.exit(0);
  });
}
