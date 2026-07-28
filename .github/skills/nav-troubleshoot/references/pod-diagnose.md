# Pod diagnosis — CrashLoopBackOff, ImagePullBackOff, Pending

Diagnosis trees and command reference for NAIS pod failures in this Ktor backend
(`no.nav.budstikka`, Netty/`io.ktor.server.netty.EngineMain`).

## Step 1: Status

```bash
# Overview of pods for the app
kubectl get pods -n {namespace} -l app={app-name} -o wide

# Detailed pod information
kubectl describe pod -n {namespace} {pod-name}

# Nais app status
kubectl get app -n {namespace} {app-name} -o yaml | grep -A 20 status
```

## Step 2: Logs

```bash
# Latest logs
kubectl logs -n {namespace} -l app={app-name} --tail=100

# Logs from the previous crash (important for CrashLoopBackOff)
kubectl logs -n {namespace} {pod-name} --previous --tail=100

# Follow logs in real time
kubectl logs -n {namespace} -l app={app-name} -f --tail=10

# Filter for error messages
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
# Current resource consumption
kubectl top pod -n {namespace} -l app={app-name}

# Resource requests vs limits
kubectl get pod -n {namespace} {pod-name} \
  -o jsonpath='{.spec.containers[0].resources}'
```

## CrashLoopBackOff — common NAV/Ktor-specific causes

| Log output | Cause | Resolution |
|-----------|-------|---------|
| `OOMKilled` (exit 137) | Too little memory | Increase `resources.limits.memory` in the NAIS manifest (see /nais-manifest) |
| `java.lang.OutOfMemoryError: Java heap space` | JVM heap too small | The JVM takes heap from container memory; increase the memory limit (heap typically ~75% of the limit) |
| `Connection refused: localhost:5432` | Cloud SQL proxy sidecar not ready | Check `gcp.sqlInstances` in the manifest; see database-diagnose.md |
| `AZURE_APP_CLIENT_ID not set` / NPE in auth setup at startup | Missing env var from NAIS | Set `azure.application.enabled: true` in the manifest |
| `TOKEN_X_CLIENT_ID not set` | TokenX not enabled | Set `tokenx.enabled: true` |
| `KAFKA_BROKERS not set` | Kafka not configured | Set `kafka.pool: nav-dev/nav-prod` in the manifest |
| `application.conf` / HOCON error at startup | Ktor config references an env var that does not exist | Verify `${?ENV_VAR}` lookups against the env vars that NAIS actually injects |
| `Address already in use` / readiness fails | App listens on the wrong port | `spec.port` must match `ktor.deployment.port` |
| `No such file or directory` | Incorrect Dockerfile COPY | Verify that the `build/libs`/distribution artifact is copied correctly |

## ImagePullBackOff

```bash
kubectl describe pod -n {namespace} {pod-name} | grep -A 2 Image
```

Common causes:
- Incorrect image tag (the build failed or the GitHub Actions run has not finished pushing)
- GAR authentication failed (workload identity, service account)
- The image does not exist in Google Artifact Registry

## Pending (the pod never starts)

```bash
kubectl describe pod -n {namespace} {pod-name} | grep -A 5 Conditions
kubectl describe pod -n {namespace} {pod-name} | grep -A 10 Events
```

Common causes:
- Insufficient resources in the cluster (check `FailedScheduling`)
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
│   ├── Latest exit code = 137 (OOMKilled)? → increase memory limit
│   ├── Log shows "... not set" (env var)? → manifest lacks a feature flag
│   │   (azure/tokenx/idporten/kafka/gcp.sqlInstances — see /nais-manifest)
│   ├── Log shows HOCON/`application.conf` error? → missing env lookup in Ktor config
│   ├── Log shows "Connection refused :5432"? → see database-diagnose.md
│   ├── Log shows an auth-related error at startup? → see auth-diagnose.md
│   └── Unknown? → use `kubectl logs --previous` and search the logs
└── Status = Running but readiness fails?
    ├── Readiness endpoint does not respond → check that `/internal/health/is_ready` matches `readiness.path`
    ├── App listens on a port other than `spec.port`
    └── App takes a long time to start (Flyway migration at startup) → increase `readiness.initialDelay`
```

## When this points elsewhere

- DB-related startup error → [database-diagnose.md](./database-diagnose.md)
- Auth-related startup error → [auth-diagnose.md](./auth-diagnose.md)
- Fix discipline (reproduction, regression test) → `/diagnosing-bugs`
