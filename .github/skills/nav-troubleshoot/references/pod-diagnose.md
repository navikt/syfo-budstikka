# Pod-diagnose — CrashLoopBackOff, ImagePullBackOff, Pending

Diagnostiske trær og kommando-referanse for pod-problemer på NAIS for dette Ktor-backendet (`no.nav.syfo`, Netty/`io.ktor.server.netty.EngineMain`).

## Steg 1: Status

```bash
# Oversikt over pods for appen
kubectl get pods -n {namespace} -l app={app-name} -o wide

# Detaljert pod-info
kubectl describe pod -n {namespace} {pod-name}

# Nais app-status
kubectl get app -n {namespace} {app-name} -o yaml | grep -A 20 status
```

## Steg 2: Logs

```bash
# Siste logs
kubectl logs -n {namespace} -l app={app-name} --tail=100

# Logs fra forrige krasj (viktig ved CrashLoopBackOff)
kubectl logs -n {namespace} {pod-name} --previous --tail=100

# Følg logs i sanntid
kubectl logs -n {namespace} -l app={app-name} -f --tail=10

# Filtrer på feilmeldinger
kubectl logs -n {namespace} -l app={app-name} --tail=500 \
  | grep -i "error\|exception\|fatal"
```

## Steg 3: Events

```bash
# Pod-events (scheduling, pulling, started, failed)
kubectl get events -n {namespace} --sort-by='.lastTimestamp' | grep {app-name}

# Namespace-events (bredere)
kubectl get events -n {namespace} --sort-by='.lastTimestamp' | tail -20
```

## Steg 4: Ressurser

```bash
# Aktuelt ressursforbruk
kubectl top pod -n {namespace} -l app={app-name}

# Ressurs-requests vs limits
kubectl get pod -n {namespace} {pod-name} \
  -o jsonpath='{.spec.containers[0].resources}'
```

## CrashLoopBackOff — vanlige NAV-/Ktor-spesifikke årsaker

| Log-output | Årsak | Løsning |
|-----------|-------|---------|
| `OOMKilled` (exit 137) | For lite minne | Øk `resources.limits.memory` i NAIS-manifest (se /nais-manifest) |
| `java.lang.OutOfMemoryError: Java heap space` | JVM-heap for liten | JVM tar heap fra container-minnet; øk memory-limit (heap typisk ~75 % av limit) |
| `Connection refused: localhost:5432` | Cloud SQL proxy-sidecar ikke klar | Sjekk `gcp.sqlInstances` i manifest; se database-diagnose.md |
| `AZURE_APP_CLIENT_ID not set` / NPE i auth-oppsett ved startup | Manglende env-var fra NAIS | Sett `azure.application.enabled: true` i manifest |
| `TOKEN_X_CLIENT_ID not set` | TokenX ikke aktivert | Sett `tokenx.enabled: true` |
| `KAFKA_BROKERS not set` | Kafka ikke konfigurert | Sett `kafka.pool: nav-dev/nav-prod` i manifest |
| `application.conf` / HOCON-feil ved oppstart | Ktor-config refererer env-var som ikke finnes | Verifiser `${?ENV_VAR}`-oppslag mot env NAIS faktisk injiserer |
| `Address already in use` / readiness fails | App lytter på feil port | `spec.port` må matche `ktor.deployment.port` |
| `No such file or directory` | Feil Dockerfile COPY | Verifiser at `build/libs`/distribusjons-artefakt kopieres riktig |

## ImagePullBackOff

```bash
kubectl describe pod -n {namespace} {pod-name} | grep -A 2 Image
```

Vanlige årsaker:
- Feil image-tag (build mislyktes eller GitHub Actions-runnet har ikke pusht ferdig)
- GAR-autentisering feilet (workload identity, service account)
- Image finnes ikke i Google Artifact Registry

## Pending (pod starter aldri)

```bash
kubectl describe pod -n {namespace} {pod-name} | grep -A 5 Conditions
kubectl describe pod -n {namespace} {pod-name} | grep -A 10 Events
```

Vanlige årsaker:
- Ikke nok ressurser i klusteret (sjekk `FailedScheduling`)
- PersistentVolumeClaim ikke bundet
- Node-selektor matcher ikke

## Diagnostisk tre

```
Pod feiler
├── Status = Pending?
│   └── Se "Pending"-seksjon (scheduling, kvoter, PVC)
├── Status = ImagePullBackOff / ErrImagePull?
│   └── Se "ImagePullBackOff"-seksjon (tag, GAR, workload identity)
├── Status = CrashLoopBackOff?
│   ├── Siste exit code = 137 (OOMKilled)? → øk memory limit
│   ├── Log viser "... not set" (env-var)? → manifest mangler feature-flag
│   │   (azure/tokenx/idporten/kafka/gcp.sqlInstances — se /nais-manifest)
│   ├── Log viser HOCON-/`application.conf`-feil? → manglende env-oppslag i Ktor-config
│   ├── Log viser "Connection refused :5432"? → se database-diagnose.md
│   ├── Log viser auth-relatert feil ved startup? → se auth-diagnose.md
│   └── Ukjent? → `kubectl logs --previous` og søk i logs
└── Status = Running men readiness fails?
    ├── Readiness-endepunkt svarer ikke → sjekk at Ktor-ruten (f.eks. `/internal/isready`) matcher `readiness.path`
    ├── App lytter på annen port enn `spec.port`
    └── App tar lang tid å starte (Flyway-migrering ved oppstart) → øk `readiness.initialDelay`
```

## Når dette peker på annet

- DB-relatert oppstartsfeil → [database-diagnose.md](./database-diagnose.md)
- Auth-relatert oppstartsfeil → [auth-diagnose.md](./auth-diagnose.md)
- Fikse-disiplin (repro, regresjonstest) → `/diagnosing-bugs`
