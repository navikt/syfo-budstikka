#!/usr/bin/env python3
"""Deterministic content lint for .github/{skills,agents,instructions}.

Applies the Hovmester scripts/test_content.py rules (description contract,
200-line cap, reference links, identity-number placeholders, and action
pinning) plus repository rules: official Copilot frontmatter fields only, no
loose Markdown in a skill root, lowercase reference filenames, and English
discovery descriptions. It has no third-party Python dependencies; Git is the
only repository command it invokes.
Exit != 0 on a violation. CI and /write-a-skill run this validator.
"""

import hashlib
import json
import os
import re
import subprocess
import sys

CLI_ARGS = sys.argv[1:]
SCRIPT_ROOT = os.path.dirname(os.path.abspath(__file__))
default_repository_root = os.path.abspath(os.path.join(SCRIPT_ROOT, ".."))
if len(CLI_ARGS) >= 2 and CLI_ARGS[0] == "--repository-root":
    REPOSITORY_ROOT = os.path.abspath(CLI_ARGS[1])
    CLI_ARGS = CLI_ARGS[2:]
    if not os.path.isdir(REPOSITORY_ROOT):
        print(
            f"ERROR: repository root does not exist: {REPOSITORY_ROOT}",
            file=sys.stderr,
        )
        sys.exit(2)
else:
    REPOSITORY_ROOT = default_repository_root
GITHUB = os.path.join(REPOSITORY_ROOT, ".github")
ROOT_AGENTS = os.path.join(REPOSITORY_ROOT, "AGENTS.md")
SKILLS = os.path.join(GITHUB, "skills")
AGENTS = os.path.join(GITHUB, "agents")
INSTRUCTIONS = os.path.join(GITHUB, "instructions")
REPOSITORY_HOOKS = os.path.join(GITHUB, "hooks")
COPILOT_INSTRUCTIONS = os.path.join(GITHUB, "copilot-instructions.md")
COPILOT_SETTINGS = os.path.join(GITHUB, "copilot", "settings.json")
CI_WORKFLOW = os.path.join(GITHUB, "workflows", "ci.yaml")
CI_REUSABLE_WORKFLOW = os.path.join(GITHUB, "workflows", "ci-reusable.yml")
TRUSTED_POLICY_WORKFLOW = os.path.join(
    GITHUB, "workflows", "copilot-policy.yaml"
)
REVIEW_ARTIFACT_HELPER = os.path.join(
    REPOSITORY_ROOT, "scripts", "review-artifact.py"
)
WORKFLOW_DIRECTORY = os.path.join(GITHUB, "workflows")
# These workflows are security boundaries, so the trusted base validator
# accepts their exact reviewed bytes rather than trying to interpret arbitrary
# YAML with regular expressions. copilot-policy.yaml also rejects normal PRs
# that touch this policy root. Change a workflow and its digest only through an
# explicitly reviewed repository-administrator root rotation.
EXPECTED_CI_WORKFLOW_SHA256 = {
    "43c25ad3b07e730de7fb1bae5057b892abb3e952d88461b946dee431bb91d37a"
}
EXPECTED_CI_REUSABLE_WORKFLOW_SHA256 = {
    "86a0a79d572a54ec73f7f0a2156b42978a2c572cc09fac1247e12d7389218593"
}
EXPECTED_TRUSTED_POLICY_WORKFLOW_SHA256 = {
    "8e780e12b70d2df0a9fefa45ae4a8be92512213a40810e98f2d450e57ccc0f8c"
}
EXPECTED_REVIEW_ARTIFACT_HELPER_SHA256 = {
    "7d2f2cb68de70b538989436253472cefedff96e64b8436be532361ce99a448f2"
}
EXPECTED_WORKFLOW_SHA256: dict[str, set[str]] = {
    ".github/workflows/ci-reusable.yml": EXPECTED_CI_REUSABLE_WORKFLOW_SHA256,
    ".github/workflows/ci.yaml": EXPECTED_CI_WORKFLOW_SHA256,
    ".github/workflows/copilot-policy.yaml": EXPECTED_TRUSTED_POLICY_WORKFLOW_SHA256,
    ".github/workflows/dependabot-automerge.yml": {
        "35fd8b198844859c653b557c947f4cfe782d4f9daac004184f4441b74e1a1e3c"
    },
    ".github/workflows/deploy-nais.yaml": {
        "2cadf3707ca4045e9ad4706e2b9dadd339d21fcb4e02fe4bffc727cfbb521018"
    },
    ".github/workflows/deploy-topic.yaml": {
        "8a7ea61ed54e30faf755aa1f4f8eac196eb181c869212d3853ece1ef8ad393b8"
    },
    ".github/workflows/deploy.yaml": {
        "d412b5f2f27c46d878e3cb833fab74f785e0e2955df8b66b909b11b5c535a147"
    },
}
COPILOT_LOCAL_SETTINGS = ".github/copilot/settings.local.json"
GITIGNORE = os.path.join(REPOSITORY_ROOT, ".gitignore")
LANGUAGE_POLICY = os.path.join(REPOSITORY_ROOT, "docs", "agents", "language-policy.md")
CONTEXT_INDEX = os.path.join(REPOSITORY_ROOT, "docs", "context.md")
DECISION_REGISTER = os.path.join(REPOSITORY_ROOT, "docs", "decisions.md")
LEGACY_CONTEXT_HISTORY = os.path.join(
    REPOSITORY_ROOT, "docs", "context-history.md"
)

violations: list[str] = []
warnings: list[str] = []

SKILL_FRONTMATTER_FIELDS = {
    "name",
    "description",
    "license",
    "argument-hint",
    "allowed-tools",
    "user-invocable",
    "disable-model-invocation",
}
AGENT_FRONTMATTER_FIELDS = {
    "name",
    "description",
    "model",
    "user-invocable",
    "disable-model-invocation",
    "tools",
}
INSTRUCTION_FRONTMATTER_FIELDS = {"description", "applyTo"}

# Copilot CLI exposes manual skills to the user while its current model prompt
# catalog omits them. Server-side flags may change that split, so budget every
# project description as a conservative upper bound and report the
# automatically invokable subset separately. The 7,000-character ceiling also
# preserves about 1,000 characters of measured whole-catalog headroom when the
# current personal skills and per-entry overhead are present; exact runtime
# injection remains outside repository control.
PROJECT_DESCRIPTION_BUDGET = 7_000
SKILL_BODY_SOFT_BUDGET = 8_000
SKILL_BODY_HARD_BUDGET = 12_000

# AGENTS.md, copilot-instructions.md and instructions with an all-files
# `applyTo` pattern are ambient in ordinary CLI work. Keep this separate from
# path-targeted guidance.
AMBIENT_INSTRUCTION_BUDGET = 6_500

# The short orientation index is the main progressive-disclosure boundary.
# Detailed decisions and topic material must remain behind explicit links.
CONTEXT_INDEX_BUDGET = 4_000

# The independent Opus inspector has an absolute, model-visible context
# boundary. The artifact helper enforces the same limit before any inspector
# call, so neither a prompt edit nor a larger diff can bypass it.
INSPECTOR_PATCH_BYTE_BUDGET = 120_000
INSPECTOR_PATCH_BUDGET_DIRECTIVE = re.compile(
    r"<!--\s*max-inspector-patch-bytes:\s*([0-9]+)\s*-->"
)
INSPECTOR_ARTIFACT_CONTRACT_DIRECTIVE = (
    "<!-- inspector-artifact-contract: baseline-to-current-worktree-v4 -->"
)
OIDC_WRITE_PERMISSION = "id-" + "to" + "ken: write"

# This comment is deliberately the only machine-readable intended-use
# declaration. Copilot CLI has no per-agent skill ACL: the check catches prompt
# drift, but ordinary model-invokable skills remain discoverable at runtime.
MODEL_INVOCATION_DIRECTIVE = re.compile(
    r"^\s*<!--\s*model-invokes-skill:\s*/([a-z0-9][a-z0-9-]*)\s*-->\s*$"
)
LITERAL_SKILL_MENTION = re.compile(
    r"(?<![A-Za-z0-9_./-])/([a-z0-9][a-z0-9-]*)"
    r"(?![A-Za-z0-9_./-])"
)
EXPECTED_AGENT_SKILL_ROUTES = {
    "barista.agent.md": frozenset({"pull-request"}),
    "grillmester.agent.md": frozenset({"bounded-research", "grilling"}),
    "grill-inspektor.agent.md": frozenset(),
    "kokk.agent.md": frozenset({"tdd"}),
}

# These workflows are deliberately user-selected rather than inferred from a
# prompt. Keeping the required set here makes a deletion or accidental
# model-invocation flip a deterministic CI failure.
REQUIRED_MANUAL_SKILLS = frozenset(
    {
        "domain-modeling",
        "grill-with-docs",
        "nav-architecture-review",
        "wayfinder",
        "write-a-skill",
    }
)
GRILLMESTER_ONLY_MANUAL_SKILLS = frozenset(
    {
        "domain-modeling",
        "grill-with-docs",
        "nav-architecture-review",
        "wayfinder",
    }
)
MANUAL_ROLE_GUARD = (
    "Run this manual workflow only inside an active Grillmester session."
)
FORBIDDEN_PROJECT_SKILLS = {
    "grill-me": "use /grilling instead",
    "code-review": "use the Grill-inspektor review workflow instead",
    "codebase-design": "use the approved design and domain workflow instead",
    "upstream-watch": "upstream watching is not a repository skill",
    "writing-great-skills": "use the canonical /write-a-skill name",
    "create-a-skill": "use the canonical /write-a-skill name",
}

# These names are built-in Copilot CLI slash commands, not overridable project
# workflow routes. Project skills use explicit names so their model, quota, and
# Nav-specific contracts cannot silently resolve to the built-ins.
RESERVED_COPILOT_CLI_SKILL_NAMES = frozenset({"research", "security-review"})
EXPECTED_COPILOT_SETTINGS = {
    "model": "gpt-5.6-terra",
    "contextTier": "default",
    "includeCoAuthoredBy": True,
    "disabledSkills": ["grill-me", "to-issues", "to-prd"],
}

# Discovery descriptions are always machine-facing and must be English. Keep
# this deliberately narrow: bodies can contain canonical Norwegian domain terms
# or quoted product copy, but these common control words signal a regressed
# frontmatter translation.
NORWEGIAN_DESCRIPTION_RE = re.compile(
    r"[æøåÆØÅ]|\b(?:brukes|gjelder|kalles|når|opprette|endre|skrive|"
    r"kjøre|vurdere|feilsøke|arbeid|kontekst|mangler|ferdig)\b",
    re.IGNORECASE,
)

# Agents do not share a global long-lived work surface. A brief is the only
# task-scoped handoff, so these old .grill artifacts must not return to model
# context.
FORBIDDEN_GLOBAL_STATE_PATHS = (
    ".grill/STATE.md",
    ".grill/PLAN.md",
    ".grill/VERIFICATION.md",
    ".grill/REVIEW.md",
)
FORBIDDEN_GLOBAL_STATE_RE = re.compile(
    "|".join(re.escape(path) for path in FORBIDDEN_GLOBAL_STATE_PATHS)
)

if os.path.exists(os.path.join(REPOSITORY_ROOT, ".grill")):
    violations.append(
        ".grill/: global task state is forbidden — use an explicit "
        "IMPLEMENTATION_BRIEF v1"
    )

REQUIRED_POLICY_FILES = (
    ".githooks/pre-commit",
    ".github/copilot/settings.json",
    ".github/workflows/ci-reusable.yml",
    ".github/workflows/copilot-policy.yaml",
    "scripts/agent-smoke.sh",
    "scripts/scan-staged-sensitive-data.sh",
    "scripts/review-artifact.py",
    "scripts/validate-agent-models.sh",
    "scripts/validate-implementation-brief.py",
    "scripts/validate-skills.py",
)
for required_policy_file in REQUIRED_POLICY_FILES:
    if not os.path.isfile(os.path.join(REPOSITORY_ROOT, required_policy_file)):
        violations.append(
            f"{required_policy_file}: required policy file is missing"
        )

# Project hooks auto-execute repository-provided commands in a developer's CLI
# session. This portable pilot deliberately forbids them; any organization-level
# hook must be installed and governed outside the candidate worktree.
if os.path.isdir(REPOSITORY_HOOKS):
    repository_hook_files = [
        os.path.relpath(os.path.join(root, filename), REPOSITORY_ROOT)
        for root, _directories, filenames in os.walk(REPOSITORY_HOOKS)
        for filename in filenames
    ]
    if repository_hook_files:
        violations.append(
            ".github/hooks/: repository-owned auto-executing hooks are forbidden "
            "(found " + ", ".join(sorted(repository_hook_files)) + ")"
        )

# Active Bnn references use a non-ambient decision register. Duplicate ids make
# code/ADR pointers ambiguous; the former archive path would misclassify the
# same live material as historical.
if os.path.exists(LEGACY_CONTEXT_HISTORY):
    violations.append(
        "docs/context-history.md: use the active docs/decisions.md register"
    )
if not os.path.isfile(DECISION_REGISTER):
    violations.append("docs/decisions.md: active Bnn decision register is missing")
else:
    with open(DECISION_REGISTER, encoding="utf-8") as f:
        decision_ids = [
            int(match.group(1))
            for line in f
            if (match := re.match(r"^- B(\d+):", line))
        ]
    duplicate_decisions = sorted(
        decision_id
        for decision_id in set(decision_ids)
        if decision_ids.count(decision_id) > 1
    )
    if duplicate_decisions:
        violations.append(
            "docs/decisions.md: duplicate decision ids "
            + ", ".join(f"B{decision_id}" for decision_id in duplicate_decisions)
        )
    if decision_ids:
        missing_decisions = sorted(
            set(range(1, max(decision_ids) + 1)) - set(decision_ids)
        )
        if missing_decisions:
            violations.append(
                "docs/decisions.md: missing decision ids "
                + ", ".join(f"B{decision_id}" for decision_id in missing_decisions)
            )
context_index_total = 0
def context_index_policy_errors(content: str) -> list[str]:
    """Return navigation and size violations for the default context index."""
    errors = []
    if "decisions.md" not in content:
        errors.append(
            "docs/context.md: must route concrete Bnn lookups to decisions.md"
        )
    if len(content) > CONTEXT_INDEX_BUDGET:
        errors.append(
            "docs/context.md is "
            f"{len(content)} characters (progressive-disclosure guardrail: "
            f"{CONTEXT_INDEX_BUDGET})"
        )
    return errors


if os.path.isfile(CONTEXT_INDEX):
    with open(CONTEXT_INDEX, encoding="utf-8") as f:
        context_index = f.read()
    context_index_total = len(context_index)
    violations.extend(context_index_policy_errors(context_index))


def canonical_frontmatter_text(content: str, allowed_fields: set[str], *,
                               bare_fields: set[str],
                               boolean_fields: set[str],
                               list_fields: set[str]) -> tuple[dict, bool, list[str]]:
    """Parse the deliberately small, auditable YAML subset used in this repo.

    This is not a general YAML parser. Frontmatter may use only canonical root
    scalars and two-space block lists. That fail-closed policy prevents YAML
    anchors, aliases, merge keys, flow collections, duplicate fields, and
    nested mappings from resolving a hidden field after this validator has
    inspected a different flat view. JSON-compatible double-quoted strings are
    decoded so escaped glob characters cannot evade `applyTo` classification.
    """
    lines = content.splitlines()
    if not lines or lines[0] != "---":
        return {}, False, ["frontmatter must start with an exact --- delimiter"]
    try:
        end = lines.index("---", 1)
    except ValueError:
        return {}, False, ["frontmatter must end with an exact --- delimiter"]

    fm: dict = {}
    errors: list[str] = []
    active_list: str | None = None
    for line in lines[1:end]:
        if active_list is not None:
            raw_item_match = re.fullmatch(r"  - (.+)", line)
            if raw_item_match:
                raw_item = raw_item_match.group(1)
                if active_list == "tools":
                    if re.fullmatch(r"[A-Za-z][A-Za-z-]*", raw_item):
                        fm[active_list].append(raw_item)
                    else:
                        errors.append(
                            "tools must use unquoted canonical tool identifiers"
                        )
                    continue
                if active_list == "allowed-tools":
                    try:
                        decoded_item = json.loads(raw_item)
                    except json.JSONDecodeError:
                        errors.append(
                            "allowed-tools list items must be double-quoted "
                            "JSON strings"
                        )
                    else:
                        if not isinstance(decoded_item, str) or not decoded_item:
                            errors.append(
                                "allowed-tools list items must decode to "
                                "non-empty strings"
                            )
                        else:
                            fm[active_list].append(decoded_item)
                    continue
        active_list = None

        list_match = re.fullmatch(r"([A-Za-z][A-Za-z-]*):", line)
        if list_match:
            key = list_match.group(1)
            if key in fm:
                errors.append(f"duplicate frontmatter field {key}")
            elif key not in allowed_fields:
                errors.append(f"unknown frontmatter field {key}")
            elif key not in list_fields:
                errors.append(f"frontmatter field {key} must be a canonical scalar")
            else:
                fm[key] = []
                active_list = key
            continue

        scalar_match = re.fullmatch(r"([A-Za-z][A-Za-z-]*): (.+)", line)
        if not scalar_match:
            errors.append(
                "frontmatter must use canonical root fields and two-space list items"
            )
            continue
        key, raw_value = scalar_match.groups()
        if key in fm:
            errors.append(f"duplicate frontmatter field {key}")
            continue
        if key not in allowed_fields:
            errors.append(f"unknown frontmatter field {key}")
            continue
        if key in list_fields and key != "allowed-tools":
            errors.append(f"{key} must use a canonical block list")
            continue
        if key in boolean_fields:
            if raw_value not in {"true", "false"}:
                errors.append(f"{key} must be the unquoted boolean true or false")
            else:
                fm[key] = raw_value
            continue
        if key in bare_fields:
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/:-]*", raw_value):
                errors.append(f"{key} must be a canonical bare scalar")
            else:
                fm[key] = raw_value
            continue
        if not (raw_value.startswith('"') and raw_value.endswith('"')):
            errors.append(f"{key} must be a canonical double-quoted JSON string")
            continue
        try:
            decoded = json.loads(raw_value)
        except json.JSONDecodeError:
            errors.append(f"{key} has invalid JSON-compatible string escaping")
            continue
        if not isinstance(decoded, str):
            errors.append(f"{key} must decode to a string")
            continue
        if key == "allowed-tools" and not decoded:
            errors.append("allowed-tools must decode to a non-empty string")
            continue
        fm[key] = decoded
    return fm, True, errors


def canonical_frontmatter(path: str, allowed_fields: set[str], *,
                          bare_fields: set[str], boolean_fields: set[str],
                          list_fields: set[str]) -> tuple[dict, bool, list[str]]:
    with open(path, encoding="utf-8") as f:
        return canonical_frontmatter_text(
            f.read(),
            allowed_fields,
            bare_fields=bare_fields,
            boolean_fields=boolean_fields,
            list_fields=list_fields,
        )


def iter_md(root):
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            if fn.endswith(".md"):
                yield os.path.join(dirpath, fn)


def frontmatter_true(value):
    """Return true only for the YAML scalar `true`; unknown values stay disabled."""
    return value.casefold() == "true"


def normalized_apply_to(value: str) -> str:
    """Normalize a decoded canonical `applyTo` scalar."""
    return value.strip()


def split_glob_alternatives(value: str) -> list[str]:
    """Split comma alternatives without splitting inside brace globs."""
    alternatives: list[str] = []
    current: list[str] = []
    depth = 0
    for character in value:
        if character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth < 0:
                raise ValueError("unmatched closing brace")
        if character == "," and depth == 0:
            alternatives.append("".join(current))
            current = []
        else:
            current.append(character)
    if depth:
        raise ValueError("unmatched opening brace")
    alternatives.append("".join(current))
    return alternatives


def expand_brace_glob(pattern: str, *, limit: int = 128) -> list[str]:
    """Expand the small brace-alternative subset used by documented globs."""
    start = pattern.find("{")
    if start < 0:
        return [pattern]
    depth = 0
    end = -1
    for index in range(start, len(pattern)):
        if pattern[index] == "{":
            depth += 1
        elif pattern[index] == "}":
            depth -= 1
            if depth == 0:
                end = index
                break
    if end < 0:
        raise ValueError("unmatched opening brace")

    prefix, suffix = pattern[:start], pattern[end + 1 :]
    expanded: list[str] = []
    for option in split_glob_alternatives(pattern[start + 1 : end]):
        for candidate in expand_brace_glob(prefix + option + suffix, limit=limit):
            expanded.append(candidate)
            if len(expanded) > limit:
                raise ValueError("too many brace expansions")
    return expanded


def is_ambient_apply_to(value: str) -> bool:
    """Return whether an instruction applies to all ordinary repository files."""
    try:
        alternatives = split_glob_alternatives(normalized_apply_to(value))
        for pattern in alternatives:
            for expanded in expand_brace_glob(pattern.strip()):
                if not expanded:
                    continue
                segments = [segment for segment in expanded.split("/") if segment]
                if segments and all(segment in {"*", "**"} for segment in segments):
                    return True
    except ValueError:
        # Unknown/malformed scope must not become a budget bypass.
        return True
    return False


def require_english_description(owner: str, description: str) -> None:
    """Reject obvious Norwegian control prose in machine-facing discovery text."""
    if NORWEGIAN_DESCRIPTION_RE.search(description):
        violations.append(f"{owner}: description must be English")


def model_invocation_directives(path):
    """Return explicit routing declarations outside fenced Markdown examples."""
    in_fence = False
    with open(path, encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if not in_fence and (match := MODEL_INVOCATION_DIRECTIVE.match(line)):
                yield line_no, match.group(1)


def agent_body_skill_route_errors(
    agent_file: str,
    content: str,
) -> list[str]:
    """Reject literal body routes that the agent has not declared."""
    lines = content.splitlines()
    try:
        body_start = lines.index("---", 1) + 1
    except ValueError:
        return []

    expected = EXPECTED_AGENT_SKILL_ROUTES.get(agent_file, frozenset())
    errors: list[str] = []
    for line_no, line in enumerate(lines[body_start:], body_start + 1):
        if MODEL_INVOCATION_DIRECTIVE.match(line):
            continue
        for match in LITERAL_SKILL_MENTION.finditer(line):
            skill_name = match.group(1)
            if skill_name not in expected:
                errors.append(
                    f"agents/{agent_file}:{line_no}: literal /{skill_name} "
                    "is not a declared route for this agent"
                )
    return errors


def agent_route_policy_errors(
    routes_by_agent: dict[str, list[str]],
) -> list[str]:
    """Return drift from the declared custom-agent-to-skill intent."""
    errors: list[str] = []
    for agent_file, expected in EXPECTED_AGENT_SKILL_ROUTES.items():
        if agent_file not in routes_by_agent:
            errors.append(
                f"agents/{agent_file}: required route-policy owner is missing"
            )
            continue
        actual_routes = routes_by_agent.get(agent_file, [])
        actual = set(actual_routes)
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        duplicates = sorted(
            route for route in actual if actual_routes.count(route) > 1
        )
        if missing:
            errors.append(
                f"agents/{agent_file}: missing model-invokes-skill route(s): "
                + ", ".join(f"/{route}" for route in missing)
            )
        if unexpected:
            errors.append(
                f"agents/{agent_file}: unexpected model-invokes-skill route(s): "
                + ", ".join(f"/{route}" for route in unexpected)
            )
        if duplicates:
            errors.append(
                f"agents/{agent_file}: duplicate model-invokes-skill route(s): "
                + ", ".join(f"/{route}" for route in duplicates)
            )
    for agent_file, routes in sorted(routes_by_agent.items()):
        if agent_file not in EXPECTED_AGENT_SKILL_ROUTES and routes:
            errors.append(
                f"agents/{agent_file}: no model-invokes-skill routes are "
                "allowed for an unregistered custom agent"
            )
    return errors


def route_owner_policy_error(relative_path: str, skill_name: str) -> str | None:
    """Reject routing declarations outside the four registered agent files."""
    permitted_owners = {
        f"agents/{agent_file}" for agent_file in EXPECTED_AGENT_SKILL_ROUTES
    }
    if relative_path in permitted_owners:
        return None
    return (
        f"{relative_path}: model-invokes-skill /{skill_name} is forbidden "
        "outside a registered custom agent"
    )


def reserved_skill_name_error(skill_name: str) -> str | None:
    """Reject project skill names owned by built-in Copilot CLI commands."""
    if skill_name not in RESERVED_COPILOT_CLI_SKILL_NAMES:
        return None
    return f"{skill_name}: conflicts with a built-in Copilot CLI slash command"


def inspector_patch_budget_error(content: str) -> str | None:
    """Require exactly one canonical Opus inspector patch budget declaration."""
    declared = INSPECTOR_PATCH_BUDGET_DIRECTIVE.findall(content)
    expected = str(INSPECTOR_PATCH_BYTE_BUDGET)
    if declared != [expected]:
        return (
            "must declare exactly one "
            f"max-inspector-patch-bytes: {expected} directive"
        )
    return None


def exact_contract_directive_error(
    content: str,
    directive: str,
) -> str | None:
    """Require one exact model-visible artifact contract version."""
    if content.count(directive) != 1:
        return f"must declare exactly one {directive}"
    return None


def workflow_job_block(content: str, job_name: str) -> str | None:
    """Extract one canonical two-space job block without parsing arbitrary YAML."""
    matches = list(
        re.finditer(
            rf"(?ms)^  {re.escape(job_name)}:\n"
            rf"(.*?)(?=^  [A-Za-z0-9][A-Za-z0-9_-]*:\n|\Z)",
            content,
        )
    )
    if len(matches) != 1:
        return None
    return matches[0].group(0)


def exact_workflow_digest_error(
    content: str,
    relative_path: str,
    expected_digests: set[str],
) -> str | None:
    """Reject every unreviewed byte change to a security-boundary workflow."""
    actual = hashlib.sha256(content.encode("utf-8")).hexdigest()
    if actual not in expected_digests:
        return (
            f"{relative_path}: unreviewed security-boundary workflow digest "
            f"{actual}; follow docs/agents/trusted-policy-root.md"
        )
    return None


def ci_workflow_policy_errors(
    ci_content: str,
    reusable_content: str,
) -> list[str]:
    """Protect the required CI result from same-tree skip/bypass drift."""
    errors: list[str] = []
    for content, relative_path, expected_digests in (
        (
            ci_content,
            ".github/workflows/ci.yaml",
            EXPECTED_CI_WORKFLOW_SHA256,
        ),
        (
            reusable_content,
            ".github/workflows/ci-reusable.yml",
            EXPECTED_CI_REUSABLE_WORKFLOW_SHA256,
        ),
    ):
        if digest_error := exact_workflow_digest_error(
            content,
            relative_path,
            expected_digests,
        ):
            errors.append(digest_error)
    ci_job = workflow_job_block(ci_content, "ci")
    gate_job = workflow_job_block(ci_content, "merge-gate")
    build_job = workflow_job_block(reusable_content, "build")

    if ci_job is None:
        errors.append("ci.yaml: canonical jobs.ci block is missing or duplicated")
    else:
        if re.search(r"(?m)^    if:", ci_job):
            errors.append("ci.yaml: jobs.ci must not have a job-level if condition")
        if "continue-on-error:" in ci_job:
            errors.append("ci.yaml: jobs.ci must not allow failure")
        if "uses: ./.github/workflows/ci-reusable.yml" not in ci_job:
            errors.append("ci.yaml: jobs.ci must call ci-reusable.yml")

    if gate_job is None:
        errors.append(
            "ci.yaml: canonical jobs.merge-gate block is missing or duplicated"
        )
    else:
        required_gate_fragments = (
            "name: Merge gate",
            "needs:\n      - ci",
            "if: ${{ always() }}",
            'case "${{ needs.ci.result }}" in',
            "success)",
            "cancelled)",
            "skipped)",
            "*)",
        )
        for fragment in required_gate_fragments:
            if fragment not in gate_job:
                errors.append(
                    f"ci.yaml: merge-gate is missing required fragment {fragment!r}"
                )
        if "continue-on-error:" in gate_job:
            errors.append("ci.yaml: merge-gate must not allow failure")
        if "exit 0" in gate_job:
            errors.append("ci.yaml: merge-gate must not short-circuit success")
        gate_if_lines = re.findall(r"(?m)^\s+if:\s*(.+)$", gate_job)
        if gate_if_lines != ["${{ always() }}"]:
            errors.append(
                "ci.yaml: merge-gate must have only the canonical always() condition"
            )
        for branch in ("cancelled", "skipped", "*"):
            branch_match = re.search(
                rf"(?ms)^\s{{12}}{re.escape(branch)}\)\n"
                rf"(.*?)(?=^\s{{14}};;)",
                gate_job,
            )
            if branch_match is None or "exit 1" not in branch_match.group(1):
                errors.append(
                    f"ci.yaml: merge-gate {branch} result must exit 1"
                )

    if build_job is None:
        errors.append(
            "ci-reusable.yml: canonical jobs.build block is missing or duplicated"
        )
    else:
        if re.search(r"(?m)^    if:", build_job):
            errors.append(
                "ci-reusable.yml: jobs.build must not have a job-level if condition"
            )
        if "continue-on-error:" in build_job or "exit 0" in build_job:
            errors.append("ci-reusable.yml: jobs.build must not allow failure")
        required_build_commands = (
            "run: bash scripts/validate-agent-models.sh .github/agents",
            "run: bash scripts/validate-agent-models.sh --self-test",
            "run: python3 scripts/validate-skills.py",
            "run: python3 scripts/validate-skills.py --self-test",
            "run: python3 scripts/validate-implementation-brief.py --self-test",
            "run: python3 scripts/review-artifact.py --self-test",
            "run: bash scripts/scan-staged-sensitive-data.sh --self-test",
            'bash scripts/scan-staged-sensitive-data.sh --diff "$base" '
            '"${GITHUB_SHA:-HEAD}"',
            "run: ./gradlew build --no-daemon",
        )
        for command in required_build_commands:
            if command not in build_job:
                errors.append(
                    "ci-reusable.yml: jobs.build is missing required command "
                    f"{command!r}"
                )
        bootstrap_condition = (
            "${{ github.event.pull_request.base.sha != "
            "'8b7373681755ac4f64c29c0ddb059e78e5ec0b95' && "
            "github.event.merge_group.base_sha != "
            "'8b7373681755ac4f64c29c0ddb059e78e5ec0b95' }}"
        )
        step_if_lines = re.findall(r"(?m)^        if:\s*(.+)$", build_job)
        if step_if_lines != [bootstrap_condition] * 4:
            errors.append(
                "ci-reusable.yml: only the four exact one-time bootstrap "
                "conditions may guard required Copilot-content steps"
            )
    return errors


def trusted_policy_workflow_errors(content: str) -> list[str]:
    """Keep the required trusted check available on PRs and merge queues."""
    errors: list[str] = []
    if digest_error := exact_workflow_digest_error(
        content,
        ".github/workflows/copilot-policy.yaml",
        EXPECTED_TRUSTED_POLICY_WORKFLOW_SHA256,
    ):
        errors.append(digest_error)
    required_fragments = (
        "  pull_request_target:",
        "  merge_group:",
        "      - checks_requested",
        "permissions:\n  contents: read",
        "github.event_name == 'pull_request_target' && "
        "github.event.pull_request.base.sha || github.event.merge_group.base_sha",
        "github.event_name == 'pull_request_target' && "
        "github.event.pull_request.head.sha || github.event.merge_group.head_sha",
        "github.event_name == 'pull_request_target' && "
        "github.event.pull_request.head.repo.full_name || github.repository",
        "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
        "allow-unsafe-pr-checkout: true",
        '"scripts/review-artifact.py"',
        '":(top,glob).github/hooks/**"',
        'python3 "$POLICY_ROOT/scripts/validate-skills.py"',
        'bash "$POLICY_ROOT/scripts/validate-agent-models.sh"',
        'bash "$POLICY_ROOT/scripts/scan-staged-sensitive-data.sh"',
        'GIT_ALTERNATE_OBJECT_DIRECTORIES="$POLICY_ROOT/.git/objects"',
        "Trusted policy root changes require the administrator runbook in "
        "docs/agents/trusted-policy-root.md",
    )
    for fragment in required_fragments:
        if fragment not in content:
            errors.append(
                "copilot-policy.yaml: missing trusted-policy fragment "
                f"{fragment!r}"
            )
    checkout_auth_setting = "persist-" + "creden" + "tials: false"
    if content.count(checkout_auth_setting) != 2:
        errors.append(
            "copilot-policy.yaml: both checkouts must disable credential persistence"
        )
    if content.count("allow-unsafe-pr-checkout: true") != 1:
        errors.append(
            "copilot-policy.yaml: only the immutable candidate-as-data "
            "checkout may opt into unsafe PR checkout"
        )
    forbidden_candidate_execution = (
        'bash "$CANDIDATE_ROOT/',
        'python3 "$CANDIDATE_ROOT/',
        "working-directory: candidate",
        "working-directory: ${{ github.workspace }}/candidate",
    )
    for fragment in forbidden_candidate_execution:
        if fragment in content:
            errors.append(
                "copilot-policy.yaml: candidate code execution is forbidden "
                f"({fragment!r})"
            )
    return errors


def deployment_workflow_policy_errors(
    contents_by_relative_path: dict[str, str],
) -> list[str]:
    """Protect unprivileged validation and push-only deployment boundaries."""
    errors: list[str] = []
    for relative_path, content in sorted(contents_by_relative_path.items()):
        for action_match in re.finditer(
            r"(?m)^\s*(?:-\s+)?uses:\s+([^#\s]+)",
            content,
        ):
            target = action_match.group(1)
            if target.startswith("./"):
                continue
            _action, separator, revision = target.rpartition("@")
            if not separator or not re.fullmatch(r"[0-9a-f]{40}", revision):
                errors.append(
                    f"{relative_path}: remote uses target must be pinned to "
                    f"one 40-character commit SHA: {target!r}"
                )
        if (
            ("pull_request:" in content or "merge_group:" in content)
            and OIDC_WRITE_PERMISSION in content
        ):
            errors.append(
                f"{relative_path}: pull-request or merge-group workflows "
                "must not receive OIDC"
            )

    reusable = contents_by_relative_path.get(
        ".github/workflows/ci-reusable.yml",
        "",
    )
    for forbidden in (
        OIDC_WRITE_PERMISSION,
        "nais/deploy/actions/deploy@",
        "DRY_RUN:",
    ):
        if forbidden in reusable:
            errors.append(
                "ci-reusable.yml: unprivileged CI contains forbidden "
                f"deployment fragment {forbidden!r}"
            )
    for required in (
        "NAIS_CLI_VERSION: 5.44.4",
        (
            "NAIS_CLI_SHA256: "
            "fe51c24e8f54231e929c83f54cd4eef569d629565a32ed8f9ac6905296b59b3d"
        ),
        "nais validate --var kafka-pool=nav-dev nais/topics/kafka-dev.yaml",
        "nais validate --var kafka-pool=nav-prod nais/topics/kafka-prod.yaml",
    ):
        if required not in reusable:
            errors.append(
                "ci-reusable.yml: credential-free manifest validation is "
                f"missing {required!r}"
            )

    for relative_path in (
        ".github/workflows/deploy-topic.yaml",
        ".github/workflows/deploy.yaml",
    ):
        content = contents_by_relative_path.get(relative_path, "")
        for forbidden_trigger in (
            "  workflow_dispatch:",
            "  pull_request:",
            "  merge_group:",
        ):
            if forbidden_trigger in content:
                errors.append(
                    f"{relative_path}: privileged deployment must not expose "
                    f"{forbidden_trigger.strip()}"
                )
        for required in (
            "  push:",
            "      - main",
            "github.event_name == 'push' && github.ref == 'refs/heads/main'",
        ):
            if required not in content:
                errors.append(
                    f"{relative_path}: privileged deployment is missing "
                    f"main-push guard {required!r}"
                )

    reusable_deploy = contents_by_relative_path.get(
        ".github/workflows/deploy-nais.yaml",
        "",
    )
    if "  workflow_dispatch:" in reusable_deploy:
        errors.append(
            "deploy-nais.yaml: reusable privileged workflow must not be dispatchable"
        )
    if (
        "github.event_name == 'push' && github.ref == 'refs/heads/main'"
        not in reusable_deploy
    ):
        errors.append(
            "deploy-nais.yaml: reusable deployment lacks its main-push job guard"
        )
    return errors


def parse_exact_copilot_settings(content: str) -> tuple[dict | None, str | None]:
    """Parse settings as strict JSON and enforce the repository model policy."""
    def reject_duplicate_keys(pairs):
        parsed = {}
        for key, value in pairs:
            if key in parsed:
                raise ValueError(f"duplicate key {key!r}")
            parsed[key] = value
        return parsed

    try:
        parsed = json.loads(content, object_pairs_hook=reject_duplicate_keys)
    except (json.JSONDecodeError, ValueError) as error:
        return None, f"must be strict JSON without duplicate keys ({error})"
    if not isinstance(parsed, dict):
        return None, "must contain one JSON object"
    if parsed != EXPECTED_COPILOT_SETTINGS:
        return parsed, (
            "must contain exactly model=gpt-5.6-terra and "
            "contextTier=default with includeCoAuthoredBy=true and "
            "disabledSkills=[grill-me,to-issues,to-prd]"
        )
    return parsed, None


# ---------- Copilot CLI defaults ----------
if not os.path.isfile(COPILOT_SETTINGS):
    violations.append(
        ".github/copilot/settings.json: trusted-repository CLI defaults are missing"
    )
else:
    with open(COPILOT_SETTINGS, encoding="utf-8") as f:
        _copilot_settings, settings_error = parse_exact_copilot_settings(f.read())
    if settings_error:
        violations.append(f".github/copilot/settings.json: {settings_error}")
if not os.path.isfile(GITIGNORE):
    violations.append(".gitignore: repository ignore policy is missing")
else:
    with open(GITIGNORE, encoding="utf-8") as f:
        ignored_paths = {
            line.strip()
            for line in f
            if line.strip() and not line.lstrip().startswith("#")
        }
    if COPILOT_LOCAL_SETTINGS not in ignored_paths:
        violations.append(
            f".gitignore: must ignore {COPILOT_LOCAL_SETTINGS} because local "
            "settings override repository model and cost policy"
        )
try:
    local_settings_ignore = subprocess.run(
        [
            "git",
            "-C",
            REPOSITORY_ROOT,
            "check-ignore",
            "--no-index",
            "--quiet",
            COPILOT_LOCAL_SETTINGS,
        ],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
except OSError as error:
    violations.append(
        f".gitignore: unable to verify the effective local-settings rule ({error})"
    )
else:
    if local_settings_ignore.returncode != 0:
        violations.append(
            f".gitignore: {COPILOT_LOCAL_SETTINGS} is not effectively ignored; "
            "remove any later negation rule"
        )


# ---------- Trusted workflow gates ----------
workflow_contents: dict[str, str] = {}
actual_workflow_paths = {
    os.path.relpath(os.path.join(WORKFLOW_DIRECTORY, filename), REPOSITORY_ROOT)
    .replace(os.sep, "/")
    for filename in os.listdir(WORKFLOW_DIRECTORY)
    if filename.endswith((".yml", ".yaml"))
}
expected_workflow_paths = set(EXPECTED_WORKFLOW_SHA256)
for missing_workflow in sorted(expected_workflow_paths - actual_workflow_paths):
    violations.append(f"{missing_workflow}: required reviewed workflow is missing")
for unexpected_workflow in sorted(actual_workflow_paths - expected_workflow_paths):
    violations.append(
        f"{unexpected_workflow}: workflow has no exact reviewed digest"
    )
for relative_workflow in sorted(actual_workflow_paths & expected_workflow_paths):
    workflow_path = os.path.join(
        REPOSITORY_ROOT,
        *relative_workflow.split("/"),
    )
    if os.path.islink(workflow_path) or not os.path.isfile(workflow_path):
        violations.append(
            f"{relative_workflow}: workflow must be a regular non-symlink file"
        )
        continue
    with open(workflow_path, encoding="utf-8") as workflow_file:
        workflow_content = workflow_file.read()
    workflow_contents[workflow_path] = workflow_content
    if digest_error := exact_workflow_digest_error(
        workflow_content,
        relative_workflow,
        EXPECTED_WORKFLOW_SHA256[relative_workflow],
    ):
        violations.append(digest_error)

if CI_WORKFLOW in workflow_contents and CI_REUSABLE_WORKFLOW in workflow_contents:
    violations.extend(
        ci_workflow_policy_errors(
            workflow_contents[CI_WORKFLOW],
            workflow_contents[CI_REUSABLE_WORKFLOW],
        )
    )
if TRUSTED_POLICY_WORKFLOW in workflow_contents:
    violations.extend(
        trusted_policy_workflow_errors(
            workflow_contents[TRUSTED_POLICY_WORKFLOW]
        )
    )
violations.extend(
    deployment_workflow_policy_errors(
        {
            os.path.relpath(path, REPOSITORY_ROOT).replace(os.sep, "/"): content
            for path, content in workflow_contents.items()
        }
    )
)
if os.path.isfile(REVIEW_ARTIFACT_HELPER):
    with open(REVIEW_ARTIFACT_HELPER, encoding="utf-8") as helper_file:
        helper_digest_error = exact_workflow_digest_error(
            helper_file.read(),
            "scripts/review-artifact.py",
            EXPECTED_REVIEW_ARTIFACT_HELPER_SHA256,
        )
    if helper_digest_error:
        violations.append(helper_digest_error)


# ---------- Skills ----------
names = []
skill_metadata = {}
project_descriptions = []
callable_descriptions = []
manual_skills = []
for skill in sorted(os.listdir(SKILLS)):
    skill_dir = os.path.join(SKILLS, skill)
    if not os.path.isdir(skill_dir):
        continue
    skill_md = os.path.join(skill_dir, "SKILL.md")
    if not os.path.isfile(skill_md):
        violations.append(f"{skill}: missing SKILL.md")
        continue

    fm, ok, frontmatter_errors = canonical_frontmatter(
        skill_md,
        SKILL_FRONTMATTER_FIELDS,
        bare_fields={"name", "license"},
        boolean_fields={"user-invocable", "disable-model-invocation"},
        list_fields={"allowed-tools"},
    )
    if not ok:
        violations.append(f"{skill}: frontmatter is missing or unclosed")
        continue
    if frontmatter_errors:
        violations.extend(f"{skill}: {error}" for error in frontmatter_errors)
        continue

    with open(skill_md, encoding="utf-8") as f:
        skill_content = f.read()
    if fm.get("name") != skill:
        violations.append(f"{skill}: name ({fm.get('name')}) != directory name")
    if reserved_error := reserved_skill_name_error(skill):
        violations.append(reserved_error)
    names.append(fm.get("name"))

    desc = fm.get("description", "")
    require_english_description(skill, desc)
    project_descriptions.append((skill, len(desc)))
    model_invokable = not frontmatter_true(fm.get("disable-model-invocation", ""))
    skill_metadata[skill] = {
        "disabled": not model_invokable,
        "path": skill_md,
    }
    if model_invokable:
        callable_descriptions.append((skill, len(desc)))
    else:
        manual_skills.append(skill)
    if desc.lower().startswith(("used when", "used for")):
        violations.append(
            f"{skill}: front-load a leading word or action instead of passive "
            "'Used when/for' discovery text"
        )
    if model_invokable:
        if "Use when" not in desc:
            violations.append(
                f"{skill}: model-invoked description must include a natural "
                "'Use when ...' trigger clause"
            )
        if not (60 <= len(desc) <= 360):
            violations.append(
                f"{skill}: model-invoked description is {len(desc)} characters "
                "(required: 60–360)"
            )
    else:
        if "Use when" in desc:
            violations.append(
                f"{skill}: manual description must be a short human-facing "
                "summary, not model trigger text"
            )
        if f"/{skill}" in desc:
            violations.append(
                f"{skill}: manual description must not repeat its picker name "
                f"/{skill}"
            )
        if not (40 <= len(desc) <= 200):
            violations.append(
                f"{skill}: manual description is {len(desc)} characters "
                "(required: 40–200)"
            )

    body = skill_content
    body_bytes = len(body.encode("utf-8"))
    if body_bytes > SKILL_BODY_HARD_BUDGET:
        violations.append(
            f"{skill}: SKILL.md is {body_bytes} bytes (hard cap: "
            f"{SKILL_BODY_HARD_BUDGET}) — move detail to references/"
        )
    elif body_bytes > SKILL_BODY_SOFT_BUDGET:
        warnings.append(
            f"{skill}: SKILL.md is {body_bytes} bytes (soft warning from "
            f"{SKILL_BODY_SOFT_BUDGET}) — consider progressive disclosure"
        )
    lines = body.count("\n") + 1
    if lines > 200:
        violations.append(f"{skill}: SKILL.md is {lines} lines (cap: 200) — split into references/")
    elif lines >= 180:
        warnings.append(f"{skill}: SKILL.md is {lines} lines (soft warning from 180)")

    # references/ links must resolve to lowercase filenames. (?<!/) excludes
    # cross-skill paths; the skill owning that file validates it.
    for ref in re.findall(r"(?<!/)references/([A-Za-z0-9_.\-]+\.md)", body):
        if ref != ref.lower():
            violations.append(f"{skill}: references/{ref} — filename must be lowercase")
        if not os.path.isfile(os.path.join(skill_dir, "references", ref)):
            violations.append(f"{skill}: broken link to references/{ref}")

    # Markdown other than SKILL.md belongs under references/.
    for fn in os.listdir(skill_dir):
        if fn.endswith(".md") and fn != "SKILL.md":
            violations.append(f"{skill}: loose {fn} in skill root — move it to references/")

    # Every relative Markdown link must resolve from the file containing it.
    # Pure placeholders in templates are exempt.
    for md_path in iter_md(skill_dir):
        with open(md_path, encoding="utf-8") as f:
            md_body = f.read()
        for raw_target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", md_body):
            target = raw_target.strip().strip("<>")
            target = target.split("#", 1)[0]
            if (
                not target
                or "://" in target
                or target.startswith(("mailto:", "#"))
                or target.lower() in {"lenke", "link", "url"}
            ):
                continue
            target_path = os.path.normpath(os.path.join(os.path.dirname(md_path), target))
            if not os.path.exists(target_path):
                rel_md = os.path.relpath(md_path, GITHUB)
                violations.append(f"{rel_md}: broken local link to {raw_target}")

dupes = {n for n in names if names.count(n) > 1}
if dupes:
    violations.append(f"duplicate skill names: {sorted(dupes)}")

for inspector_contract in (
    os.path.join(AGENTS, "grillmester.agent.md"),
    os.path.join(AGENTS, "grill-inspektor.agent.md"),
):
    with open(inspector_contract, encoding="utf-8") as f:
        if budget_error := inspector_patch_budget_error(f.read()):
            relative_contract = os.path.relpath(
                inspector_contract,
                REPOSITORY_ROOT,
            )
            violations.append(f"{relative_contract}: {budget_error}")
        f.seek(0)
        if contract_error := exact_contract_directive_error(
            f.read(),
            INSPECTOR_ARTIFACT_CONTRACT_DIRECTIVE,
        ):
            relative_contract = os.path.relpath(
                inspector_contract,
                REPOSITORY_ROOT,
            )
            violations.append(f"{relative_contract}: {contract_error}")

review_artifact_callers = {
    os.path.join(AGENTS, "barista.agent.md"): (
        "REVIEW_REQUEST v1",
        "REVIEW_EVIDENCE v1",
        "INSPECTOR_REVIEW v1",
        "scripts/review-artifact.py validate-contracts",
        "scripts/review-artifact.py capture",
        "scripts/review-artifact.py validate-result",
        "scripts/review-artifact.py verify",
        "--brief <request-id>=<request-path>",
        "--implementation-result <evidence-id>=<evidence-path>",
        "model: claude-opus-5",
        "context_tier: default",
        "Scope is derived only from the bound request",
        "Each contract is at most 6,000 bytes",
    ),
    os.path.join(AGENTS, "grillmester.agent.md"): (
        "INSPECTOR_REVIEW v1",
        "scripts/review-artifact.py validate-contracts",
        "scripts/review-artifact.py capture",
        "scripts/review-artifact.py validate-result",
        "scripts/review-artifact.py verify",
        "--brief <brief-id>=<brief-path>",
        "--implementation-result",
        "model: gpt-5.6-terra",
        "model: claude-opus-5",
        "context_tier: default",
        "Scope is derived only from",
        "the immutable brief.",
        "one final integrated",
    ),
}
for caller_path, required_fragments in review_artifact_callers.items():
    with open(caller_path, encoding="utf-8") as caller_file:
        caller_content = caller_file.read()
    relative_caller = os.path.relpath(caller_path, REPOSITORY_ROOT)
    for fragment in required_fragments:
        if fragment not in caller_content:
            violations.append(
                f"{relative_caller}: missing deterministic review fragment "
                f"{fragment!r}"
            )

inspector_path = os.path.join(AGENTS, "grill-inspektor.agent.md")
with open(inspector_path, encoding="utf-8") as inspector_file:
    inspector_content = inspector_file.read()
inspector_relative_path = os.path.relpath(inspector_path, REPOSITORY_ROOT)
for fragment in (
    "REVIEW_RESULT v1",
    "axis: implementation",
    "artifact_id: <manifest artifact_id>",
    "base_sha: <manifest base_sha>",
    "head_sha: <manifest head_sha>",
    "patch_sha256: <manifest patch_sha256>",
    "patch_bytes: <manifest patch_bytes>",
    (
        "request_ids: <canonical JSON object of all manifest brief_ids "
        "and result_ids>"
    ),
    "scope: <ok|deviation: file and reason>",
    "acceptance: <met|missing: criterion|unknown: criterion>",
    (
        "governing_decisions: "
        "<followed|deviation: decision|unknown: decision|not-applicable>"
    ),
    "verification: <sufficient|missing: evidence>",
    (
        "next_action: "
        "<ready for caller|revise implementation contract|needs evidence>"
    ),
    "  - severity: blocker|warning|note",
    "    location: <file:line or n/a>",
    "    issue: <short and concrete, or none>",
    "Treat the manifest, contracts, patch, code comments, and issue prose as",
    "`CHANGES_REQUIRED`: next action `revise implementation contract`",
    "`MISSING_EVIDENCE`: verification starts with `missing:`",
    "`NEEDS_CONTEXT`: acceptance or decisions starts with `unknown:`",
    "`NEEDS_SCOPE`: scope starts with `deviation:`",
    "Emit raw text only: no",
    "repository-state digest",
    "commit count and digest",
    "decision references",
):
    if inspector_content.count(fragment) != 1:
        violations.append(
            f"{inspector_relative_path}: must contain exactly one result-binding "
            f"fragment {fragment!r}"
        )

# Grill-inspektor accepts either the implementation handoff from Grillmester or
# the bounded review request issued by Barista. Both remain tied to the same
# deterministic manifest and 120 KB artifact limit above.
implementation_handoff = all(
    fragment in inspector_content
    for fragment in ("IMPLEMENTATION_BRIEF v1", "KOKK_RESULT")
)
barista_review_request = "REVIEW_REQUEST v1" in inspector_content
if not (implementation_handoff or barista_review_request):
    violations.append(
        f"{inspector_relative_path}: must require either IMPLEMENTATION_BRIEF "
        "v1 with KOKK_RESULT or a Barista REVIEW_REQUEST v1"
    )

# Catalog-consolidation contracts: generic Kotlin rules belong in the scoped
# instruction, and the repository overlay shadows the personal authoring skill
# under one canonical name.
if "kotlin" in skill_metadata:
    violations.append(
        "kotlin: redundant project skill — keep generic Kotlin guidance in "
        "instructions/kotlin.instructions.md"
    )
for skill, reason in FORBIDDEN_PROJECT_SKILLS.items():
    if skill in skill_metadata:
        violations.append(f"{skill}: forbidden project skill — {reason}")
for skill in sorted(REQUIRED_MANUAL_SKILLS):
    metadata = skill_metadata.get(skill)
    if metadata is None:
        violations.append(f"{skill}: required manual repository skill is missing")
    elif not metadata["disabled"]:
        violations.append(
            f"{skill}: required manual repository skill must set "
            "disable-model-invocation: true"
        )
for skill in sorted(GRILLMESTER_ONLY_MANUAL_SKILLS):
    metadata = skill_metadata.get(skill)
    if metadata is None:
        continue
    with open(metadata["path"], encoding="utf-8") as manual_skill_file:
        manual_content = manual_skill_file.read()
    if manual_content.count(MANUAL_ROLE_GUARD) != 1:
        violations.append(
            f"{skill}: Grillmester-only manual skill must contain exactly "
            "one canonical role guard"
        )
    if (
        "copilot --agent grillmester --model claude-opus-5 --context default"
        not in manual_content
    ):
        violations.append(
            f"{skill}: manual role guard must name the exact Grillmester "
            "startup command"
        )

for skill, metadata in sorted(skill_metadata.items()):
    if metadata["disabled"]:
        continue
    skill_root = os.path.dirname(metadata["path"])
    for path in iter_md(skill_root):
        relative_path = os.path.relpath(path, GITHUB)
        with open(path, encoding="utf-8") as skill_file:
            for line_no, line in enumerate(skill_file, 1):
                for match in LITERAL_SKILL_MENTION.finditer(line):
                    mentioned = match.group(1)
                    if mentioned in REQUIRED_MANUAL_SKILLS:
                        violations.append(
                            f"{relative_path}:{line_no}: model-invokable skill "
                            f"must recommend {mentioned} in prose and wait; "
                            "literal manual invocation is forbidden"
                        )

project_description_total = sum(length for _, length in project_descriptions)
callable_description_total = sum(length for _, length in callable_descriptions)
if project_description_total > PROJECT_DESCRIPTION_BUDGET:
    violations.append(
        "combined descriptions for all project skills are "
        f"{project_description_total} characters (context guardrail: "
        f"{PROJECT_DESCRIPTION_BUDGET}; personal skills are additional)"
    )

# Explicit routing directives own the declared route set. Literal `/skill`
# mentions in an agent body must agree with that declaration; other
# model-visible material may still use slash-prefixed examples or documentation.
model_context_paths = [
    path
    for root in (SKILLS, AGENTS, INSTRUCTIONS)
    for path in iter_md(root)
]
if os.path.isfile(COPILOT_INSTRUCTIONS):
    model_context_paths.append(COPILOT_INSTRUCTIONS)
agent_skill_routes: dict[str, list[str]] = {
    filename: []
    for filename in os.listdir(AGENTS)
    if filename.endswith(".agent.md")
}
for path in model_context_paths:
    for line_no, skill_name in model_invocation_directives(path):
        rel = os.path.relpath(path, GITHUB).replace(os.sep, "/")
        if owner_error := route_owner_policy_error(rel, skill_name):
            violations.append(f"{owner_error} (line {line_no})")
        if os.path.dirname(path) == AGENTS:
            agent_skill_routes.setdefault(os.path.basename(path), []).append(
                skill_name
            )
        metadata = skill_metadata.get(skill_name)
        if metadata is None:
            violations.append(
                f"{rel}:{line_no}: model-invokes-skill /{skill_name} does not exist"
            )
        elif metadata["disabled"]:
            violations.append(
                f"{rel}:{line_no}: model-invokes-skill /{skill_name} has "
                "disable-model-invocation: true"
            )
violations.extend(agent_route_policy_errors(agent_skill_routes))
for agent_file in sorted(agent_skill_routes):
    agent_path = os.path.join(AGENTS, agent_file)
    with open(agent_path, encoding="utf-8") as f:
        violations.extend(
            agent_body_skill_route_errors(
                agent_file,
                f.read(),
            )
        )

# Do not exempt fenced examples: a historical global-state reference in a
# model-visible skill is still a regression-prone instruction source. Keeping
# this strict also means the forbidden names have a single policy owner.
for path in model_context_paths:
    rel = os.path.relpath(path, GITHUB)
    with open(path, encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            if match := FORBIDDEN_GLOBAL_STATE_RE.search(line):
                violations.append(
                    f"{rel}:{line_no}: forbidden global task state "
                    f"{match.group(0)} — use IMPLEMENTATION_BRIEF v1"
                )

# ---------- Instructions ----------
ambient_instruction_paths = [
    path for path in (ROOT_AGENTS, COPILOT_INSTRUCTIONS) if os.path.isfile(path)
]
for fn in sorted(os.listdir(INSTRUCTIONS)):
    if not fn.endswith(".instructions.md"):
        continue
    instruction_path = os.path.join(INSTRUCTIONS, fn)
    fm, ok, frontmatter_errors = canonical_frontmatter(
        instruction_path,
        INSTRUCTION_FRONTMATTER_FIELDS,
        bare_fields=set(),
        boolean_fields=set(),
        list_fields=set(),
    )
    if not ok:
        violations.append(f"instructions/{fn}: missing frontmatter")
        continue
    if frontmatter_errors:
        violations.extend(
            f"instructions/{fn}: {error}" for error in frontmatter_errors
        )
        continue
    for field in ("applyTo", "description"):
        if not fm.get(field):
            violations.append(f"instructions/{fn}: missing {field}")
    require_english_description(f"instructions/{fn}", fm.get("description", ""))
    if is_ambient_apply_to(fm.get("applyTo", "")):
        ambient_instruction_paths.append(instruction_path)

ambient_instruction_total = 0
for path in ambient_instruction_paths:
    with open(path, encoding="utf-8") as f:
        ambient_instruction_total += len(f.read())
if ambient_instruction_total > AMBIENT_INSTRUCTION_BUDGET:
    violations.append(
        "ambient instruction context is "
        f"{ambient_instruction_total} characters (guardrail: "
        f"{AMBIENT_INSTRUCTION_BUDGET})"
    )

# ---------- Agents ----------
for fn in sorted(os.listdir(AGENTS)):
    if not fn.endswith(".agent.md"):
        continue
    fm, ok, frontmatter_errors = canonical_frontmatter(
        os.path.join(AGENTS, fn),
        AGENT_FRONTMATTER_FIELDS,
        bare_fields={"name"},
        boolean_fields={"user-invocable", "disable-model-invocation"},
        list_fields={"tools"},
    )
    if not ok:
        violations.append(f"agents/{fn}: missing frontmatter")
        continue
    if frontmatter_errors:
        violations.extend(f"agents/{fn}: {error}" for error in frontmatter_errors)
        continue
    for field in ("name", "description", "model"):
        if not fm.get(field):
            violations.append(f"agents/{fn}: missing {field}")
    require_english_description(f"agents/{fn}", fm.get("description", ""))

# ---------- Language-policy anchors ----------
if not os.path.isfile(LANGUAGE_POLICY):
    violations.append("docs/agents/language-policy.md: missing canonical language policy")
for path in (ROOT_AGENTS, COPILOT_INSTRUCTIONS):
    if not os.path.isfile(path):
        continue
    with open(path, encoding="utf-8") as f:
        if "docs/agents/language-policy.md" not in f.read():
            violations.append(
                f"{os.path.relpath(path, REPOSITORY_ROOT)}: must link to "
                "docs/agents/language-policy.md"
            )

# ---------- Global checks (identity numbers + unpinned actions) ----------
fnr = re.compile(r"\b\d{11}\b")
action = re.compile(r"(?<![\w/])(?!nais/)[\w.-]+/[\w.-]+@(v\d+|main|master)\b")
for root in (SKILLS, AGENTS, INSTRUCTIONS):
    for path in iter_md(root):
        rel = os.path.relpath(path, GITHUB)
        with open(path, encoding="utf-8") as f:
            file_lines = f.readlines()
        for i, line in enumerate(file_lines, 1):
            for m in fnr.finditer(line):
                # Never print the match itself because it may be PII. The
                # staged-content gate follows the same principle.
                if m.group(0) != "00000000000":
                    violations.append(f"{rel}:{i}: possible real identity number — use 00000000000")
            if action.search(line):
                low = line.lower()
                window = "".join(file_lines[max(0, i - 3):i - 1])
                if "replace" not in low and "example" not in low and "❌" not in line and "❌" not in window:
                    violations.append(f"{rel}:{i}: unpinned action — pin to a commit SHA")

for w in warnings:
    print(f"WARNING: {w}")
print(
    "Skill catalog (all project skills): "
    f"{len(project_descriptions)} skills, {project_description_total}/"
    f"{PROJECT_DESCRIPTION_BUDGET} description characters"
)
if project_descriptions:
    print("  " + ", ".join(
        f"{name}={length}" for name, length in project_descriptions
    ))
print(
    "Skill routing: "
    f"{len(callable_descriptions)} model-invokable "
    f"({callable_description_total} description characters), "
    f"{len(manual_skills)} manual"
)
if manual_skills:
    print("  " + ", ".join(manual_skills))
print(
    "Ambient instruction context: "
    f"{ambient_instruction_total}/{AMBIENT_INSTRUCTION_BUDGET} characters"
)
print(
    "Default context index: "
    f"{context_index_total}/{CONTEXT_INDEX_BUDGET} characters"
)
if CLI_ARGS == ["--self-test"]:
    valid_context_fixture = "decisions.md" + (
        "x" * (CONTEXT_INDEX_BUDGET - len("decisions.md"))
    )
    oversized_context_fixture = valid_context_fixture + "x"
    if context_index_policy_errors(valid_context_fixture):
        violations.append("context-index budget self-test rejected the exact limit")
    elif not any(
        "guardrail" in error
        for error in context_index_policy_errors(oversized_context_fixture)
    ):
        violations.append("context-index budget self-test accepted an oversized index")
    else:
        print("Context-index budget self-test: OK")

    apply_to_cases = {
        "**": True,
        "**/*": True,
        "**/**": True,
        "*, README.md": True,
        "{**/*,README.md}": True,
        "README.md,{**/*,docs/**}": True,
        "**/*.md": False,
        "**/*.{kt,kts}": False,
        "src/{main,test}/**": False,
        "README.md": False,
        "{README.md": True,
    }
    failed_cases = [
        repr(value)
        for value, expected in apply_to_cases.items()
        if is_ambient_apply_to(value) != expected
    ]
    if failed_cases:
        violations.append(
            "ambient applyTo normalization self-test failed for "
            + ", ".join(failed_cases)
        )
    else:
        print("Ambient applyTo normalization self-test: OK")

    route_self_test_start = len(violations)
    canonical_routes = {
        agent_file: sorted(routes)
        for agent_file, routes in EXPECTED_AGENT_SKILL_ROUTES.items()
    }
    if agent_route_policy_errors(canonical_routes):
        violations.append("agent route policy self-test rejected canonical routes")
    extra_route_fixture = {
        agent_file: list(routes)
        for agent_file, routes in canonical_routes.items()
    }
    extra_route_fixture["barista.agent.md"].append("bounded-research")
    extra_route_errors = agent_route_policy_errors(extra_route_fixture)
    if not any(
        "barista.agent.md" in error and "unexpected" in error
        and "/bounded-research" in error
        for error in extra_route_errors
    ):
        violations.append("agent route policy self-test allowed an extra route")
    missing_route_fixture = {
        agent_file: list(routes)
        for agent_file, routes in canonical_routes.items()
    }
    missing_route_fixture["kokk.agent.md"].remove("tdd")
    missing_route_errors = agent_route_policy_errors(missing_route_fixture)
    if not any(
        "kokk.agent.md" in error and "missing" in error and "/tdd" in error
        for error in missing_route_errors
    ):
        violations.append("agent route policy self-test allowed a missing route")
    if not any(
        "duplicate" in error
        for error in agent_route_policy_errors(
            {
                **canonical_routes,
                "kokk.agent.md": ["tdd", "tdd"],
            }
        )
    ):
        violations.append("agent route policy self-test allowed a duplicate route")
    if not any(
        "unregistered" in error
        for error in agent_route_policy_errors(
            {
                **canonical_routes,
                "extra.agent.md": ["bounded-research"],
            }
        )
    ):
        violations.append(
            "agent route policy self-test allowed an unregistered agent route"
        )
    if route_owner_policy_error("agents/barista.agent.md", "pull-request"):
        violations.append("route owner self-test rejected a registered agent")
    root_route_error = route_owner_policy_error(
        "copilot-instructions.md", "bounded-research"
    )
    if not root_route_error or "forbidden" not in root_route_error:
        violations.append(
            "route owner self-test allowed a root-instruction skill route"
        )
    kokk_prompt_fixture = """---
name: kokk
---
<!-- model-invokes-skill: /tdd -->
When implementation is done, invoke /pull-request to publish it.
"""
    kokk_prompt_errors = agent_body_skill_route_errors(
        "kokk.agent.md",
        kokk_prompt_fixture,
    )
    if not any(
        "/pull-request" in error and "not a declared route" in error
        for error in kokk_prompt_errors
    ):
        violations.append(
            "agent route policy self-test allowed Kokk to mention /pull-request"
        )
    if any("/tdd" in error for error in kokk_prompt_errors):
        violations.append(
            "agent route policy self-test rejected Kokk's declared /tdd route"
        )
    if len(violations) == route_self_test_start:
        print("Agent declared-route self-test: OK")

    reserved_name_self_test_start = len(violations)
    for reserved_name in sorted(RESERVED_COPILOT_CLI_SKILL_NAMES):
        if not reserved_skill_name_error(reserved_name):
            violations.append(
                f"reserved-name self-test accepted /{reserved_name}"
            )
    for project_name in ("bounded-research", "nav-security-review"):
        if reserved_skill_name_error(project_name):
            violations.append(
                f"reserved-name self-test rejected /{project_name}"
            )
    if len(violations) == reserved_name_self_test_start:
        print("Copilot built-in command collision self-test: OK")

    inspector_budget_self_test_start = len(violations)
    canonical_inspector_budget = (
        "<!-- max-inspector-patch-bytes: "
        f"{INSPECTOR_PATCH_BYTE_BUDGET} -->"
    )
    if inspector_patch_budget_error(canonical_inspector_budget):
        violations.append(
            "inspector-patch budget self-test rejected the canonical limit"
        )
    for invalid_budget in (
        "",
        "<!-- max-inspector-patch-bytes: 120001 -->",
        canonical_inspector_budget + "\n" + canonical_inspector_budget,
    ):
        if not inspector_patch_budget_error(invalid_budget):
            violations.append(
                "inspector-patch budget self-test accepted an invalid declaration"
            )
    if len(violations) == inspector_budget_self_test_start:
        print("Inspector-patch budget self-test: OK")

    artifact_contract_self_test_start = len(violations)
    for contract_directive in (INSPECTOR_ARTIFACT_CONTRACT_DIRECTIVE,):
        if exact_contract_directive_error(contract_directive, contract_directive):
            violations.append(
                "worktree-artifact self-test rejected a canonical contract"
            )
        if not exact_contract_directive_error("", contract_directive):
            violations.append(
                "worktree-artifact self-test accepted a missing contract"
            )
        if not exact_contract_directive_error(
            contract_directive.replace("-v4 -->", "-v3 -->"),
            contract_directive,
        ):
            violations.append(
                "worktree-artifact self-test accepted the stale v3 contract"
            )
        if not exact_contract_directive_error(
            contract_directive + "\n" + contract_directive,
            contract_directive,
        ):
            violations.append(
                "worktree-artifact self-test accepted a duplicate contract"
            )
    if len(violations) == artifact_contract_self_test_start:
        print("Worktree-artifact contract self-test: OK")

    workflow_policy_self_test_start = len(violations)
    canonical_ci = workflow_contents.get(CI_WORKFLOW, "")
    canonical_reusable = workflow_contents.get(CI_REUSABLE_WORKFLOW, "")
    canonical_trusted = workflow_contents.get(TRUSTED_POLICY_WORKFLOW, "")
    if ci_workflow_policy_errors(canonical_ci, canonical_reusable):
        violations.append(
            "trusted-workflow self-test rejected the canonical CI workflows"
        )
    if trusted_policy_workflow_errors(canonical_trusted):
        violations.append(
            "trusted-workflow self-test rejected the canonical policy workflow"
        )
    canonical_workflows_by_relative_path = {
        os.path.relpath(path, REPOSITORY_ROOT).replace(os.sep, "/"): content
        for path, content in workflow_contents.items()
    }
    if deployment_workflow_policy_errors(canonical_workflows_by_relative_path):
        violations.append(
            "deployment-workflow self-test rejected the canonical workflows"
        )
    unpinned_workflows = dict(canonical_workflows_by_relative_path)
    unpinned_workflows[".github/workflows/deploy.yaml"] = (
        unpinned_workflows.get(".github/workflows/deploy.yaml", "").replace(
            "nais/login@bb6c3e1e14e53d40d69d0d092bd2fce2e39ae3bf",
            "nais/login@v0",
            1,
        )
    )
    if not deployment_workflow_policy_errors(unpinned_workflows):
        violations.append(
            "deployment-workflow self-test accepted a mutable remote action"
        )
    oidc_ci_workflows = dict(canonical_workflows_by_relative_path)
    oidc_ci_workflows[".github/workflows/ci-reusable.yml"] = (
        oidc_ci_workflows.get(".github/workflows/ci-reusable.yml", "")
        + "\npermissions:\n  "
        + OIDC_WRITE_PERMISSION
        + "\n"
    )
    if not deployment_workflow_policy_errors(oidc_ci_workflows):
        violations.append(
            "deployment-workflow self-test accepted OIDC in pull-request CI"
        )
    dispatch_workflows = dict(canonical_workflows_by_relative_path)
    dispatch_workflows[".github/workflows/deploy.yaml"] = (
        dispatch_workflows.get(".github/workflows/deploy.yaml", "").replace(
            "  push:\n",
            "  workflow_dispatch:\n  push:\n",
            1,
        )
    )
    if not deployment_workflow_policy_errors(dispatch_workflows):
        violations.append(
            "deployment-workflow self-test accepted privileged manual dispatch"
        )

    conditional_ci = canonical_ci.replace(
        "  ci:\n    name: CI\n",
        "  ci:\n    name: CI\n    if: ${{ false }}\n",
        1,
    )
    if not ci_workflow_policy_errors(conditional_ci, canonical_reusable):
        violations.append(
            "trusted-workflow self-test accepted a conditionally skipped CI job"
        )

    skipped_success = re.sub(
        r"(?ms)(^\s{12}skipped\)\n.*?)(^\s{14}exit 1\n)",
        r"\1",
        canonical_ci,
        count=1,
    )
    if (
        skipped_success == canonical_ci
        or not ci_workflow_policy_errors(skipped_success, canonical_reusable)
    ):
        violations.append(
            "trusted-workflow self-test accepted a successful skipped result"
        )

    missing_build = canonical_reusable.replace(
        "run: ./gradlew build --no-daemon",
        "run: echo build omitted",
        1,
    )
    if not ci_workflow_policy_errors(canonical_ci, missing_build):
        violations.append(
            "trusted-workflow self-test accepted a missing Gradle build"
        )

    no_merge_group = canonical_trusted.replace(
        "  merge_group:\n"
        "    types:\n"
        "      - checks_requested\n",
        "",
        1,
    )
    if not trusted_policy_workflow_errors(no_merge_group):
        violations.append(
            "trusted-workflow self-test accepted a missing merge_group trigger"
        )

    quoted_conditional_ci = canonical_ci.replace(
        "  ci:\n    name: CI\n",
        '  ci:\n    name: CI\n    "if": $' + "{{ false }}\n",
        1,
    )
    if not ci_workflow_policy_errors(quoted_conditional_ci, canonical_reusable):
        violations.append(
            "trusted-workflow self-test accepted a quoted conditional CI job"
        )

    commented_skipped_exit = canonical_ci.replace(
        '              exit 1\n              ;;\n            *)',
        '              # exit 1\n              ;;\n            *)',
        1,
    )
    if not ci_workflow_policy_errors(commented_skipped_exit, canonical_reusable):
        violations.append(
            "trusted-workflow self-test accepted a commented skipped-result exit"
        )

    candidate_execution_fixtures = (
        "\nworking-directory: candidate\n",
        "\n- run: ./candidate/scripts/example.sh\n",
        "\n- run: bash candidate/scripts/example.sh\n",
        "\n- uses: ./candidate/.github/actions/example\n",
    )
    for fixture in candidate_execution_fixtures:
        candidate_execution = canonical_trusted + fixture
        if not trusted_policy_workflow_errors(candidate_execution):
            violations.append(
                "trusted-workflow self-test accepted candidate code execution "
                f"fixture {fixture.strip()!r}"
            )
    if len(violations) == workflow_policy_self_test_start:
        print("Trusted workflow policy self-test: OK")

    canonical_settings_text = json.dumps(
        EXPECTED_COPILOT_SETTINGS, separators=(",", ":")
    )
    if parse_exact_copilot_settings(canonical_settings_text)[1]:
        violations.append("Copilot settings self-test rejected canonical settings")
    settings_negative_fixtures = {
        "wrong model": (
            '{"model":"claude-opus-5","contextTier":"default",'
            '"includeCoAuthoredBy":true,"disabledSkills":'
            '["grill-me","to-issues","to-prd"]}'
        ),
        "wrong context tier": (
            '{"model":"gpt-5.6-terra","contextTier":"expanded",'
            '"includeCoAuthoredBy":true,"disabledSkills":'
            '["grill-me","to-issues","to-prd"]}'
        ),
        "extra setting": (
            '{"model":"gpt-5.6-terra","contextTier":"default",'
            '"includeCoAuthoredBy":true,"disabledSkills":'
            '["grill-me","to-issues","to-prd"],"other":true}'
        ),
        "co-author trailer disabled": (
            '{"model":"gpt-5.6-terra","contextTier":"default",'
            '"includeCoAuthoredBy":false,"disabledSkills":'
            '["grill-me","to-issues","to-prd"]}'
        ),
        "missing disabled skill": (
            '{"model":"gpt-5.6-terra","contextTier":"default",'
            '"includeCoAuthoredBy":true,"disabledSkills":'
            '["grill-me","to-issues"]}'
        ),
        "unsupported memory setting": (
            '{"model":"gpt-5.6-terra","contextTier":"default",'
            '"includeCoAuthoredBy":true,"disabledSkills":'
            '["grill-me","to-issues","to-prd"],"memory":false}'
        ),
        "duplicate key": (
            '{"model":"gpt-5.6-terra","model":"claude-opus-5",'
            '"contextTier":"default","includeCoAuthoredBy":true,'
            '"disabledSkills":["grill-me","to-issues","to-prd"]}'
        ),
    }
    allowed_settings_fixtures = [
        label
        for label, fixture in settings_negative_fixtures.items()
        if parse_exact_copilot_settings(fixture)[1] is None
    ]
    if allowed_settings_fixtures:
        violations.append(
            "Copilot settings self-test allowed "
            + ", ".join(allowed_settings_fixtures)
        )
    else:
        print("Copilot settings self-test: OK")

    def assert_canonical_fixture(
        label: str,
        content: str,
        allowed_fields: set[str],
        *,
        bare_fields: set[str],
        boolean_fields: set[str],
        list_fields: set[str],
        expected: dict | None = None,
        rejected: bool = False,
    ) -> None:
        parsed, complete, errors = canonical_frontmatter_text(
            content,
            allowed_fields,
            bare_fields=bare_fields,
            boolean_fields=boolean_fields,
            list_fields=list_fields,
        )
        if not complete or bool(errors) != rejected:
            violations.append(f"canonical frontmatter self-test failed: {label}")
        elif expected is not None and parsed != expected:
            violations.append(f"canonical frontmatter self-test decoded {label} unexpectedly")

    assert_canonical_fixture(
        "decoded ambient escape",
        '---\ndescription: "scope"\napplyTo: "\\u002A\\u002A"\n---\n',
        INSTRUCTION_FRONTMATTER_FIELDS,
        bare_fields=set(),
        boolean_fields=set(),
        list_fields=set(),
        expected={"description": "scope", "applyTo": "**"},
    )
    escaped_fm, escaped_ok, escaped_errors = canonical_frontmatter_text(
        '---\ndescription: "scope"\napplyTo: "\\u002A\\u002A"\n---\n',
        INSTRUCTION_FRONTMATTER_FIELDS,
        bare_fields=set(),
        boolean_fields=set(),
        list_fields=set(),
    )
    if not escaped_ok or escaped_errors or not is_ambient_apply_to(escaped_fm.get("applyTo", "")):
        violations.append("canonical frontmatter self-test failed: escaped all-files applyTo")
    assert_canonical_fixture(
        "instruction alias",
        '---\nall: &all "**"\ndescription: "scope"\napplyTo: *all\n---\n',
        INSTRUCTION_FRONTMATTER_FIELDS,
        bare_fields=set(),
        boolean_fields=set(),
        list_fields=set(),
        rejected=True,
    )
    assert_canonical_fixture(
        "agent hidden tools merge",
        '---\nname: barista\ndescription: "Entry point"\nmodel: "gpt-5.6-terra"\nuser-invocable: true\ndefaults: &capabilities\n  tools:\n    - agent\n<<: *capabilities\n---\n',
        AGENT_FRONTMATTER_FIELDS,
        bare_fields={"name"},
        boolean_fields={"user-invocable", "disable-model-invocation"},
        list_fields={"tools"},
        rejected=True,
    )
    assert_canonical_fixture(
        "skill hidden invocation merge",
        '---\nname: sample\ndescription: "Create a sample. Use when testing."\ndefaults: &manual\n  disable-model-invocation: true\n<<: *manual\n---\n',
        SKILL_FRONTMATTER_FIELDS,
        bare_fields={"name", "license"},
        boolean_fields={"user-invocable", "disable-model-invocation"},
        list_fields={"allowed-tools"},
        rejected=True,
    )
    assert_canonical_fixture(
        "skill allowed-tools string",
        '---\nname: sample\ndescription: "Create a sample. Use when testing."\n'
        'allowed-tools: "Bash(git:*) Bash(jq:*) Read"\n---\n',
        SKILL_FRONTMATTER_FIELDS,
        bare_fields={"name", "license"},
        boolean_fields={"user-invocable", "disable-model-invocation"},
        list_fields={"allowed-tools"},
        expected={
            "name": "sample",
            "description": "Create a sample. Use when testing.",
            "allowed-tools": "Bash(git:*) Bash(jq:*) Read",
        },
    )
    assert_canonical_fixture(
        "skill allowed-tools array",
        '---\nname: sample\ndescription: "Create a sample. Use when testing."\n'
        'allowed-tools:\n  - "read"\n  - "shell(git:*)"\n---\n',
        SKILL_FRONTMATTER_FIELDS,
        bare_fields={"name", "license"},
        boolean_fields={"user-invocable", "disable-model-invocation"},
        list_fields={"allowed-tools"},
        expected={
            "name": "sample",
            "description": "Create a sample. Use when testing.",
            "allowed-tools": ["read", "shell(git:*)"],
        },
    )
elif CLI_ARGS:
    print(
        "Usage: scripts/validate-skills.py "
        "[--repository-root PATH] [--self-test]",
        file=sys.stderr,
    )
    sys.exit(2)

if violations:
    print(f"\n{len(violations)} VIOLATIONS:")
    for v in violations:
        print(f"  - {v}")
    sys.exit(1)
print("Content lint: OK")
