# Pod diagnosis — CrashLoopBackOff, ImagePullBackOff, Pending

Diagnostic trees and command reference for pod problems on NAIS for this repository's Ktor backend (Netty/`io.ktor.server.netty.EngineMain`).

## Step 1: Status

```bash
# Overview of the pods for the app
kubectl get pods -n {namespace} -l app={app-name} -o wide

# Detailed pod info
kubectl describe pod -n {namespace} {pod-name}

# Nais app status
kubectl get app -n {namespace} {app-name} -o yaml | grep -A 20 status
```

## Step 2: Logs

```bash
# Latest logs
kubectl logs -n {namespace} -l app={app-name} --tail=100

# Logs from the previous crash (important on CrashLoopBackOff)
kubectl logs -n {namespace} {pod-name} --previous --tail=100

# Follow logs in real time
kubectl logs -n {namespace} -l app={app-name} -f --tail=10

# Filter on error messages
kubectl logs -n {namespace} -l app={app-name} --tail=500 \
  | grep -i "error\|exception\|fatal"
```

## Step 3: Events

```bash
# Pod events (scheduling, pulling, started, failed)
kubectl get events -n {namespace} --sort-by='.lastTimestamp' | grep {app-name}

# Namespace events (broader)
kubectl get events -n {namespace} --sort-by='.lastTimestamp' | tail -20
```

## Step 4: Resources

```bash
# Current resource usage
kubectl top pod -n {namespace} -l app={app-name}

# Resource requests vs. limits
kubectl get pod -n {namespace} {pod-name} \
  -o jsonpath='{.spec.containers[0].resources}'
```

## CrashLoopBackOff — common NAV/Ktor-specific causes

| Log output | Cause | Fix |
|-----------|-------|---------|
| `OOMKilled` (exit 137) | Too little memory | Increase `resources.limits.memory` in the NAIS manifest (see /nais-manifest) |
| `java.lang.OutOfMemoryError: Java heap space` | JVM heap too small | The JVM takes its heap from the container memory; increase the memory limit (heap is typically ~75 % of the limit) |
| `Connection refused: localhost:5432` | Cloud SQL proxy sidecar not ready | Check `gcp.sqlInstances` in the manifest; see database-diagnose.md |
| `AZURE_APP_CLIENT_ID not set` / NPE in the auth setup at startup | Missing env var from NAIS | Set `azure.application.enabled: true` in the manifest |
| `TOKEN_X_CLIENT_ID not set` | TokenX not enabled | Set `tokenx.enabled: true` |
| `KAFKA_BROKERS not set` | Kafka not configured | Set `kafka.pool: nav-dev/nav-prod` in the manifest |
| `application.conf` / HOCON error at startup | The Ktor config references an env var that does not exist | Verify the `${?ENV_VAR}` lookups against the env vars NAIS actually injects |
| `Address already in use` / readiness fails | The app listens on the wrong port | `spec.port` must match `ktor.deployment.port` |
| `No such file or directory` | Wrong Dockerfile COPY | Verify that `build/libs`/the distribution artifact is copied correctly |

## ImagePullBackOff

```bash
kubectl describe pod -n {namespace} {pod-name} | grep -A 2 Image
```

Common causes:
- Wrong image tag (the build failed, or the GitHub Actions run has not finished pushing)
- GAR authentication failed (workload identity, service account)
- The image does not exist in Google Artifact Registry

## Pending (the pod never starts)

```bash
kubectl describe pod -n {namespace} {pod-name} | grep -A 5 Conditions
kubectl describe pod -n {namespace} {pod-name} | grep -A 10 Events
```

Common causes:
- Not enough resources in the cluster (check `FailedScheduling`)
- PersistentVolumeClaim not bound
- Node selector does not match

## Diagnostic tree

```
Pod fails
├── Status = Pending?
│   └── See the "Pending" section (scheduling, quotas, PVC)
├── Status = ImagePullBackOff / ErrImagePull?
│   └── See the "ImagePullBackOff" section (tag, GAR, workload identity)
├── Status = CrashLoopBackOff?
│   ├── Last exit code = 137 (OOMKilled)? → increase the memory limit
│   ├── Log shows "... not set" (env var)? → the manifest is missing a feature flag
│   │   (azure/tokenx/idporten/kafka/gcp.sqlInstances — see /nais-manifest)
│   ├── Log shows a HOCON/`application.conf` error? → missing env lookup in the Ktor config
│   ├── Log shows "Connection refused :5432"? → see database-diagnose.md
│   ├── Log shows an auth-related error at startup? → see auth-diagnose.md
│   └── Unknown? → `kubectl logs --previous` and search the logs
└── Status = Running but readiness fails?
    ├── The readiness endpoint does not respond → check that the Ktor route (e.g. `/internal/health/is_ready`) matches `readiness.path`
    ├── The app listens on a different port than `spec.port`
    └── The app takes a long time to start (Flyway migration at startup) → increase `readiness.initialDelay`
```

## When this points elsewhere

- DB-related startup failure → [database-diagnose.md](./database-diagnose.md)
- Auth-related startup failure → [auth-diagnose.md](./auth-diagnose.md)
- Fix discipline (repro, regression test) → `/diagnosing-bugs`
