# NAIS authentication configuration

These are manifest excerpts and auto-injected environment variables. Always
cross-check the application's actual NAIS manifest.

## Azure AD / Entra ID (internal Nav services, M2M)

```yaml
azure:
  application:
    enabled: true
    tenant: nav.no
accessPolicy:
  inbound:
    rules:
      - application: kallende-app
        namespace: team-kallende
  outbound:
    rules:
      - application: nedstroms-app
        namespace: team-nedstroms
```

Auto-injected environment: `AZURE_APP_CLIENT_ID`, `AZURE_APP_CLIENT_SECRET`,
`AZURE_APP_WELL_KNOWN_URL`, `AZURE_OPENID_CONFIG_ISSUER`,
`AZURE_OPENID_CONFIG_JWKS_URI`, `AZURE_APP_PRE_AUTHORIZED_APPS`.

## TokenX (service-to-service with user context, on-behalf-of)

```yaml
tokenx:
  enabled: true
accessPolicy:
  inbound:
    rules:
      - application: kallende-app
        namespace: team-kallende
```

Auto-injected environment: `TOKEN_X_WELL_KNOWN_URL`, `TOKEN_X_CLIENT_ID`,
`TOKEN_X_PRIVATE_JWK`, `TOKEN_X_ISSUER`, `TOKEN_X_JWKS_URI`.

## Maskinporten (external organisations)

```yaml
maskinporten:
  enabled: true
  scopes:
    consumes:
      - name: "nav:example/scope"
```
