# Trusted policy root rotation

The trusted Copilot policy runs repository-base scripts against pull-request
and merge-group content as data. Normal pull requests must not change the
policy root, because allowing the validator to approve its own replacement
would enable a two-change trust-chain bypass.

The candidate step uses `actions/checkout` v7's explicit
`allow-unsafe-pr-checkout` opt-in because `pull_request_target` otherwise
rejects a fork checkout. This is safe only while the candidate remains
immutable, credential-free data and every executed validator comes from the
trusted base. Treat a CodeQL alert on that deliberate checkout as an individual
review decision: verify the exact head and these invariants before dismissing
that alert with a concrete false-positive rationale. Never suppress the query
globally.

GitHub rulesets support named bypass actors and a **For pull requests only**
mode, which retains a pull-request and audit trail. Ruleset bypass does not
replace independent review. See GitHub's documentation for
[repository rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets),
[PR-only bypass](https://docs.github.com/en/enterprise-cloud@latest/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository#granting-bypass-permissions-for-your-branch-or-tag-ruleset),
and [ruleset insights](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/managing-rulesets-for-a-repository#viewing-insights-for-rulesets).

## Protected inventory

The policy root contains every workflow under `.github/workflows/**`, including
workflow additions, deletions, and renames, plus these files:

- `.github/hooks/**` (repository-owned auto-executing hooks are forbidden)
- `scripts/validate-skills.py`
- `scripts/validate-agent-models.sh`
- `scripts/review-artifact.py`
- `scripts/scan-staged-sensitive-data.sh`
- `docs/agents/trusted-policy-root.md`

Any net change to one of these paths is expected to fail `Trusted Copilot
policy`. Do not work around that failure in an ordinary pull request.

## Exact digest coverage

Protected-path rejection is the primary control: the trusted base workflow
compares every path above before it validates candidate content. In addition,
`scripts/validate-skills.py` has exact SHA-256 allowlists for every workflow
file and for `scripts/review-artifact.py`. It rejects an added workflow without
an allowlist entry.

Calculate digests only after the final reviewable bytes are in place, update
all matching allowlists in the same rotation, and remove superseded digests.
Record every protected file's before/after digest in the rotation evidence.

## One-time bootstrap

`pull_request_target` runs only when its workflow file already exists on the
default branch. The first installation of `copilot-policy.yaml` therefore cannot
protect or report on its own introduction PR. Bootstrap it once as follows:

1. Pause unrelated merges and create a dedicated branch from the exact current
   `main` SHA.
2. Keep the PR limited to the protected-root files required to install the
   workflow, trusted validators, digest allowlists, and this runbook. Do not
   combine application, domain-documentation, dependency, or other content
   changes with bootstrap.
   The reusable CI workflow may skip only the four Copilot-content checks on
   the exact recorded bootstrap base SHA. The validator must require that
   literal condition and no other conditional gate. Once bootstrap advances
   `main`, the exception is inert and must never be updated or reused.
3. Have the independent reviewer inspect the complete diff and exact head SHA,
   reproduce all applicable gates below, and record every protected file's
   SHA-256.
4. Do not configure a missing `Trusted Copilot policy` check as required before
   the workflow exists. If the ruleset already requires that absent check, use
   only the audited **For pull requests only** bypass described below to merge
   the exact reviewed head. Otherwise merge under the existing protected-branch
   rules.
5. Immediately require `Trusted Copilot policy` alongside `Merge gate`, run the
   positive and negative draft-PR tests below, and inspect the check source and
   ruleset evidence. Resume unrelated merges only after both tests behave as
   expected.

After this one-time bootstrap, every later protected-root change is a rotation
and must use the established-root procedure below.

## Known external deployment blockers

The trusted policy is a merge boundary; it cannot stop a candidate workflow from
starting before another check rejects the PR. This matters because, at the
reviewed Nais deploy revision, the GitHub token validator checks signature,
issuer, and audience, while deployment authorization uses only the repository
claim and team:

- [GitHub token validation](https://github.com/nais/deploy/blob/fc3eae3edebabdf67f7592102ae16176d5ba3b5f/pkg/grpc/interceptor/auth/githubvalidator.go#L31-L52)
- [repository/team authorization](https://github.com/nais/deploy/blob/fc3eae3edebabdf67f7592102ae16176d5ba3b5f/pkg/grpc/interceptor/auth/server.go#L68-L111)

It does not establish a trusted `main` ref, event, or workflow identity. A
same-repository branch or PR workflow that can obtain OIDC can therefore
authenticate as this repository before a merge gate fails. Never grant
the `id-token` permission with `write` access to PR, merge-group, or feature-ref
jobs. Job `if` conditions
and GitHub environment rules are useful defense in depth, but they are not a
substitute for claim enforcement by the Nais verifier. Before adding or
expanding OIDC capability, require upstream evidence that Nais restricts tokens
to `refs/heads/main`, the intended event, and an exact trusted workflow identity.
The current repository/team-only verifier does not satisfy that precondition.

The CI Nais CLI executable is checksum-pinned and receives no OIDC, but
`nais validate` still loads its JSON schema from a publisher-hosted URL at
runtime ([source](https://github.com/nais/cli/blob/a212e8b547726b674c572a23302e507dfd3cc33a/internal/validate/validate.go#L13-L30)).
Treat that schema as an external availability and validation-input dependency;
the executable checksum does not pin the schema bytes.

The direct `nais/deploy` action ref is pinned, but its reviewed Dockerfile still
inherits a mutable `deploy-action:latest` image:
[actions/deploy/Dockerfile](https://github.com/nais/deploy/blob/fc3eae3edebabdf67f7592102ae16176d5ba3b5f/actions/deploy/Dockerfile).
Consequently the runtime bytes receiving OIDC are not immutable. Keep this as an
explicit external supply-chain blocker until the upstream action pins its base
image by digest, or replace it with a checksum/digest-pinned deployment client.
Do not describe the direct deployment path as fully immutable in the meantime.

## Preconditions

Before preparing a rotation:

1. Name one repository administrator as rotation owner and a different
   code-owning team member as independent reviewer.
2. Record the reason, rollback commit, exact `main` base SHA, and intended
   protected paths in the pull request. Keep unrelated changes out.
3. Confirm the `main` ruleset is active and normally requires both `Merge gate`
   and `Trusted Copilot policy`. Save its pre-rotation configuration.
4. Confirm `copilot-policy.yaml` never executes candidate scripts, actions,
   hooks, or application code and grants candidate content no secrets, OIDC, or
   write permission. For every other changed workflow, explicitly inventory its
   candidate-controlled inputs and effective job permissions.
5. For any new or expanded OIDC capability, obtain the Nais claim-enforcement
   evidence described above. Do not add OIDC to another trigger or job while the
   external repository-only trust gap remains unresolved.

## Prepare and review

Create a branch from current `main`. Calculate and record the exact SHA-256 of
every changed protected workflow and update each matching allowlist in
`scripts/validate-skills.py` in the same rotation. Do not leave old digests
accepted after the rotation.

For each changed remote `uses:` dependency, resolve and record the reviewed
40-character commit SHA. Inspect composite steps, Dockerfiles, downloaded
executables, and container base references too: a pinned action ref is not an
immutable execution path when a transitive image tag or unverified download
remains mutable.

Run these gates from a clean checkout:

```bash
python3 scripts/validate-skills.py
python3 scripts/validate-skills.py --self-test
python3 scripts/validate-implementation-brief.py --self-test
python3 scripts/review-artifact.py --self-test
bash scripts/validate-agent-models.sh .github/agents
bash scripts/validate-agent-models.sh --self-test
bash scripts/scan-staged-sensitive-data.sh --self-test
bash scripts/scan-staged-sensitive-data.sh --diff <base-sha> <head-sha>
actionlint .github/workflows/*.yml .github/workflows/*.yaml
shasum -a 256 .github/workflows/*.yml .github/workflows/*.yaml \
  scripts/review-artifact.py
shellcheck scripts/*.sh
./gradlew build --no-daemon
git diff --check <base-sha>...<head-sha>
```

The independent reviewer must inspect the exact base-to-head diff, reproduce
the gates, verify that the changed-path set is a subset of the protected
inventory above, and approve the exact head SHA. Any subsequent push invalidates
that approval and requires review again.

## Merge an established root with the narrow bypass

This section applies to rotations after the one-time bootstrap has been
verified.

1. Keep the ruleset active. Do not remove required checks or disable the entire
   ruleset.
2. A repository or organization rules administrator adds only the named
   rotation owner (or a narrowly scoped trusted team) to the ruleset bypass
   list with **For pull requests only**. Do not grant an always-allow or direct-
   push bypass.
3. The rotation owner bypass-merges only the independently approved pull
   request at the recorded head SHA. `Trusted Copilot policy` is expected to be
   red because it correctly detected a protected-root change.
4. Immediately remove the temporary bypass actor. Confirm the ruleset is still
   active and still requires `Merge gate` and `Trusted Copilot policy`.
5. Inspect ruleset insights and retain the bypass event with the review
   evidence.

If a PR-only bypass cannot be configured, stop and involve the repository or
organization rules administrator. Do not improvise by disabling the workflow,
weakening the gate, or pushing directly to `main`.

## Post-rotation verification

From the merged `main` SHA, rerun the local gates above. Then verify both sides
of the boundary:

- An ordinary draft PR with no protected-root change makes `Merge gate` and
  `Trusted Copilot policy` succeed.
- A disposable draft PR that changes one harmless comment in a protected file
  makes `Trusted Copilot policy` fail. Close it without merge.
- The next real merge-queue entry must show both required checks before it is
  allowed to merge.

Record the merged SHA, ruleset before/after evidence, check URLs, reviewer,
rotation owner, and test PRs. If verification fails, stop normal merges and use
the same reviewed PR-only rotation procedure to restore the recorded rollback
commit. Never fix a failed rotation by leaving a broader bypass in place.
