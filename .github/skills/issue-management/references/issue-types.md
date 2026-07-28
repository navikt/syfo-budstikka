# Native GitHub issue types

Use `gh api` with the `type` field. Discover available types before writing;
common organisation types in `navikt` are `Bug`, `Epic`, `Feature`, `Story`, and `Task`.

### Create an issue with a type

```bash
gh api repos/navikt/syfo-budstikka/issues \
  -X POST \
  -f title="..." \
  -f body="..." \
  -f type="Task" \
  --jq '.number'
```

### List available issue types (organisation level)

```bash
gh api graphql \
  -H "GraphQL-Features: issue_types" \
  -f query='query {
    organization(login: "navikt") {
      issueTypes {
        nodes {
          id
          name
        }
      }
    }
  }'
```
