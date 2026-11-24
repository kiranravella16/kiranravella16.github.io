import { Kafka, logLevel, type Consumer, type Producer } from "kafkajs";

export interface BusMessage {
  topic: string;
  key: string;
  value: string;
}

export type Handler = (msg: BusMessage) => Promise<void>;

/** Thin messaging abstraction so the pipeline runs on Kafka in production and in-process in tests / demos. */
export interface MessageBus {
  publish(topic: string, key: string, value: unknown): Promise<void>;
  subscribe(topics: string[], groupId: string, handler: Handler): Promise<void>;
  close(): Promise<void>;
}

/** Synchronous in-process bus. Delivery order per topic is preserved. */
export class InMemoryBus implements MessageBus {
  private readonly subs: { topics: string[]; handler: Handler }[] = [];
  readonly log: BusMessage[] = [];

  async publish(topic: string, key: string, value: unknown) {
    const msg = { topic, key, value: JSON.stringify(value) };
    this.log.push(msg);
    for (const s of this.subs) {
      if (s.topics.includes(topic)) await s.handler(msg);
    }
  }

  async subscribe(topics: string[], _groupId: string, handler: Handler) {
    this.subs.push({ topics, handler });
  }

  async close() {}

  messages(topic: string) {
    return this.log.filter((m) => m.topic === topic).map((m) => JSON.parse(m.value));
  }
}

/**
 * Kafka-backed bus. The producer is idempotent and records are keyed (feeds by supplier:sku, canonical events by
 * item id), so every update for one item stays ordered within its partition.
 */
export class KafkaBus implements MessageBus {
  private readonly kafka: Kafka;
  private producer?: Producer;
  private readonly consumers: Consumer[] = [];

  constructor(brokers: string[], clientId = "catalog-feed-pipeline") {
    this.kafka = new Kafka({ clientId, brokers, logLevel: logLevel.WARN });
  }

  private async getProducer() {
    if (!this.producer) {
      this.producer = this.kafka.producer({ idempotent: true, maxInFlightRequests: 1 });
      await this.producer.connect();
    }
    return this.producer;
  }

  async publish(topic: string, key: string, value: unknown) {
    const producer = await this.getProducer();
    await producer.send({ topic, acks: -1, messages: [{ key, value: JSON.stringify(value) }] });
  }

  async subscribe(topics: string[], groupId: string, handler: Handler) {
    const consumer = this.kafka.consumer({ groupId });
    await consumer.connect();
    for (const topic of topics) await consumer.subscribe({ topic, fromBeginning: true });
    await consumer.run({
      // commit only after the handler succeeds ⇒ at-least-once; the store's merge is idempotent
      eachMessage: async ({ topic, message }) => {
        await handler({ topic, key: message.key?.toString() ?? "", value: message.value?.toString() ?? "" });
      },
    });
    this.consumers.push(consumer);
  }

  async close() {
    await Promise.all(this.consumers.map((c) => c.disconnect()));
    await this.producer?.disconnect();
  }
}
