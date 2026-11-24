# Kafka consumer lag

tags: kafka, streaming, oncall

## Symptoms

The alert `KafkaConsumerLagHigh` fires when `kafka_consumergroup_lag` for a group stays above 50000 messages for 10 minutes. Downstream dashboards show stale data and the feed-onboarding SLO burns quickly.

## Diagnosis

1. Check whether lag is growing on every partition or only a few. Run `kafka-consumer-groups.sh --bootstrap-server $BROKERS --describe --group catalog-normalizer`.
2. If only a few partitions lag, look for a hot key: one supplier sending a huge feed lands on a single partition.
3. If all partitions lag, check consumer pod CPU and the processing time histogram `normalizer_process_seconds`.
4. Look for rebalance storms in the logs. Repeated "Revoking previously assigned partitions" means consumers are timing out.

## Mitigation

- Scale the consumer deployment up to the partition count: `kubectl scale deploy/catalog-feed-pipeline --replicas=6`. Replicas beyond the number of partitions stay idle.
- If rebalances are looping, raise `max.poll.interval.ms` or reduce `max.poll.records` so each poll finishes in time.
- For a hot key, pause that supplier's ingest and replay later; do not change the partitioner during an incident.
- Never reset offsets to latest on a production group without an incident commander's approval, because records will be skipped.

## Escalation

Page the streaming platform on-call if broker-side metrics (under-replicated partitions, request latency) are also degraded.
