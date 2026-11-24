# Database connection pool exhaustion

tags: database, mysql, postgres, oncall

## Symptoms

Requests time out with `HikariPool-1 - Connection is not available, request timed out after 30000ms`. The metric `hikaricp_connections_pending` stays above zero.

## Diagnosis

1. Compare `hikaricp_connections_active` to the configured maximum pool size.
2. Look for slow queries in the database: in MySQL run `SHOW FULL PROCESSLIST`; in PostgreSQL query `pg_stat_activity` for long-running statements.
3. Check whether a recent deploy added a query without an index; use `EXPLAIN` on the new query.
4. Connection leaks show up as active connections that never return; enable `leakDetectionThreshold` temporarily.

## Mitigation

- Kill the runaway query if it is blocking others, after confirming it is not a migration.
- Add the missing index online, or roll back the release that introduced the query.
- Do not simply raise the pool size: the total connections across all pods must stay below the database `max_connections`.
