# Production release and rollback

tags: release, oncall, process

## Release captain checklist

1. Confirm the change ticket is approved and the rollback plan is written down before the window opens.
2. Announce the deployment start in the release channel with the build version.
3. Deploy to one canary region first and watch error rate, p99 latency and saturation for 15 minutes.
4. Promote to the remaining regions only if canary metrics stay within 5 percent of baseline.

## Rollback criteria

Roll back immediately, without waiting for root cause, if any of these hold for 5 minutes after deploy:
- 5xx error rate above 1 percent
- p99 latency more than 2x the pre-deploy baseline
- a critical alert fires on the changed service

## Rollback steps

- Kubernetes: `kubectl rollout undo deploy/<service>` then `kubectl rollout status deploy/<service>`.
- Database migrations must be backward compatible so the previous version can run against the new schema; never roll back a migration during the incident.
- Post in the release channel when rollback completes and open an incident ticket for the root cause analysis.
