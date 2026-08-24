# Repository provenance

## Verified source revisions

| Source | Revision | Retained material |
|---|---|---|
| [`navikt/hovmester`](https://github.com/navikt/hovmester) | [`48483bf32c2b6f89c31e7d50e25b5fe6fac45ca2`](https://github.com/navikt/hovmester/commit/48483bf32c2b6f89c31e7d50e25b5fe6fac45ca2) | Repository issue forms |

## Adapted repository forms

The issue forms use Hovmester's repository form set as a structural input, but
the local forms are deliberately smaller and are not synchronized copies.

| Local path | Source at Hovmester `48483bf` | Local handling |
|---|---|---|
| `.github/ISSUE_TEMPLATE/{bug,feature,story,task,epic}.yml` | `dist/issue-templates/{bug,feature,story,task,epic}.yml` | Adapted into layered NAV issue forms: functional title and short plain-language opening first, then acceptance criteria and the technical context or evidence needed by implementers; uses native issue types and Team eSyfo project 157, while native graph state stays out of body checklists |
| `.github/ISSUE_TEMPLATE/config.yml` | `dist/issue-templates/config.yml` | Retains blank issues as an escape hatch without adding external contact links |

## External contract schemas

| Local path | Source | Revision | Local handling |
|---|---|---|---|
| `src/main/graphql/fager/schema.graphqls` | `navikt/arbeidsgiver-notifikasjon-produsent-api/app/src/main/resources/produsent.graphql` | `5c9251d6aaa850e08c559560bc6fed941842d5ea` | Copied unchanged as the Apollo code-generation schema for the fager producer API |

## Import rule

When repository content is copied or substantially adapted, record the
concrete source paths and full source revision, and preserve every applicable
copyright and license notice. Move a recorded revision only in the same change
that adopts it; a pin is never advanced without the diff having been assessed.
The import table above is updated whenever a retained copied or adapted
artifact adopts a newer upstream revision or adds another upstream source path.
