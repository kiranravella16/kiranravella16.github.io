# GKE pod in CrashLoopBackOff

tags: kubernetes, gke, oncall

## Symptoms

`kubectl get pods` shows `CrashLoopBackOff` and the restart count keeps climbing. Readiness probes fail and the service loses capacity.

## Diagnosis

1. Read the previous container's logs: `kubectl logs <pod> --previous`.
2. `kubectl describe pod <pod>` and check Last State. Exit code 137 with reason OOMKilled means the memory limit is too low; exit code 1 is usually an application startup error.
3. Startup errors right after a deploy usually mean a missing secret or config value. Check that the referenced Secret exists in the namespace.
4. For Workload Identity errors (403 calling Google APIs), confirm the Kubernetes service account annotation `iam.gke.io/gcp-service-account` matches the bound Google service account.

## Mitigation

- OOMKilled: raise the memory limit or lower `-XX:MaxRAMPercentage` for JVM services; then redeploy.
- Bad release: roll back with `kubectl rollout undo deploy/<service>` and follow the release rollback runbook.
- Missing secret: recreate it from the secrets manager (Akeyless) and restart the deployment.
