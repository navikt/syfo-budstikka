#!/usr/bin/env python3
"""Create and verify deterministic, quota-bounded review artifacts.

Usage:
    scripts/review-artifact.py capture --base SHA --output-dir DIR
        --brief ID=PATH --implementation-result ID=PATH [...]
    scripts/review-artifact.py validate-contracts --base SHA
        --brief ID=PATH --implementation-result ID=PATH [...]
    scripts/review-artifact.py verify --manifest PATH --patch PATH
        --brief ID=PATH --implementation-result ID=PATH [...]
    scripts/review-artifact.py validate-result --manifest PATH --result PATH
        --axis implementation
    scripts/review-artifact.py --self-test

The patch represents BASE to the current worktree post-state: committed,
staged, and unstaged tracked changes plus every selected untracked file. The
helper never prints patch content.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import selectors
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any


ARTIFACT_CONTRACT = "baseline-to-current-worktree-v4"
RESULT_CONTRACT = "REVIEW_RESULT v1"
PATCH_BYTE_LIMIT = 120_000
CONTRACT_BYTE_LIMIT = 6_000
CONTRACT_TOTAL_BYTE_LIMIT = 24_000
CONTRACT_COUNT_LIMIT = 8
REVIEW_CONTEXT_BYTE_LIMIT = 128_000
MANIFEST_BYTE_LIMIT = 24_000
CAPTURED_PATH_COUNT_LIMIT = 256
COMMIT_COUNT_LIMIT = 256
WORKTREE_INPUT_BYTE_LIMIT = 8_000_000
DECISION_REF_BYTE_LIMIT = 12_000
DECISION_REF_TOTAL_BYTE_LIMIT = 24_000
DECISION_REF_COUNT_LIMIT = 8
PATCH_NAME = "review.patch"
MANIFEST_NAME = "manifest.json"
IDENTITY_FIELDS = (
    "axis",
    "artifact_contract",
    "artifact_id",
    "base_sha",
    "head_sha",
    "patch_sha256",
    "patch_bytes",
)
AXIS_STATUSES = {
    "implementation": {
        "APPROVED",
        "CONCERNS",
        "CHANGES_REQUIRED",
        "MISSING_EVIDENCE",
        "NEEDS_CONTEXT",
        "NEEDS_SCOPE",
    },
}
RESULT_SUMMARY_FIELDS = (
    "request_ids",
    "scope",
    "acceptance",
    "governing_decisions",
    "verification",
    "next_action",
)
FINDING_SEVERITIES = {"blocker", "warning", "note"}
NEXT_ACTIONS = {
    "ready for caller",
    "revise implementation contract",
    "needs evidence",
}
MANIFEST_FIELDS = {
    "artifact_contract",
    "artifact_id",
    "base_sha",
    "brief_ids",
    "brief_sha256",
    "captured_paths",
    "commit_count",
    "commit_sha256",
    "decision_ref_bytes",
    "decision_ref_sha256",
    "head_sha",
    "patch_bytes",
    "patch_sha256",
    "patch_stat_base64",
    "result_ids",
    "result_sha256",
    "repository_state_sha256",
    "scope",
}
SHA_RE = re.compile(r"^[0-9a-f]{40}$|^[0-9a-f]{64}$")
ARTIFACT_ID_RE = re.compile(r"^[0-9a-f]{64}$")
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
BRIEF_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}$")
ATTEMPT_NUMBER_RE = re.compile(r"^[1-9][0-9]{0,8}$")
COMPLETED_KOKK_STATUSES = {"DONE", "DONE_WITH_CONCERNS"}
SAFE_GIT_CONFIG = (
    "-c",
    "core.hooksPath=/dev/null",
    "-c",
    "core.fsmonitor=false",
    "-c",
    "core.untrackedCache=false",
    "-c",
    "core.fileMode=true",
    "-c",
    "diff.external=",
    "-c",
    "core.quotePath=true",
    "-c",
    "color.ui=false",
    "-c",
    "core.pager=cat",
    "-c",
    "diff.algorithm=myers",
    "-c",
    "diff.renames=false",
)


class ArtifactError(RuntimeError):
    """A deterministic artifact contract violation."""


def git_environment() -> dict[str, str]:
    # Git accepts many process-level repository/config overrides. They are
    # useful interactively but would make the same capture command resolve a
    # different repository or index, so none may cross this contract boundary.
    environment = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith("GIT_")
    }
    environment.update(
        {
            "LC_ALL": "C",
            "LANG": "C",
            "LANGUAGE": "C",
            "TZ": "UTC",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_SYSTEM": os.devnull,
            "GIT_ATTR_NOSYSTEM": "1",
            "GIT_OPTIONAL_LOCKS": "0",
            "GIT_PAGER": "cat",
            "GIT_TERMINAL_PROMPT": "0",
        }
    )
    return environment


def git(
    repository_root: Path,
    *arguments: str,
    input_bytes: bytes | None = None,
    allowed_returncodes: tuple[int, ...] = (0,),
) -> bytes:
    command = ["git", *SAFE_GIT_CONFIG, *arguments]
    result = subprocess.run(
        command,
        cwd=repository_root,
        env=git_environment(),
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode not in allowed_returncodes:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ArtifactError(
            f"Git command failed with exit {result.returncode}: "
            f"{' '.join(arguments)}{': ' + detail if detail else ''}"
        )
    return result.stdout


def git_limited_output(
    repository_root: Path,
    *arguments: str,
    byte_limit: int,
    allowed_returncodes: tuple[int, ...] = (0,),
) -> bytes:
    """Run Git while aborting before stdout can exceed a strict byte limit."""
    command = ["git", *SAFE_GIT_CONFIG, *arguments]
    process = subprocess.Popen(
        command,
        cwd=repository_root,
        env=git_environment(),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if process.stdout is None or process.stderr is None:
        process.kill()
        process.wait()
        raise ArtifactError("unable to capture bounded Git output")

    stdout = bytearray()
    stderr = bytearray()
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ, "stdout")
    selector.register(process.stderr, selectors.EVENT_READ, "stderr")
    try:
        while selector.get_map():
            for key, _events in selector.select():
                chunk = os.read(key.fileobj.fileno(), 65_536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                if key.data == "stdout":
                    if len(stdout) + len(chunk) > byte_limit:
                        process.kill()
                        process.wait()
                        raise ArtifactError(
                            f"NEEDS_SCOPE patch_bytes>{PATCH_BYTE_LIMIT} "
                            f"low_quota_limit={PATCH_BYTE_LIMIT}"
                        )
                    stdout.extend(chunk)
                elif len(stderr) < 65_536:
                    stderr.extend(chunk[: 65_536 - len(stderr)])
        return_code = process.wait()
    finally:
        selector.close()
        process.stdout.close()
        process.stderr.close()
        if process.poll() is None:
            process.kill()
            process.wait()

    if return_code not in allowed_returncodes:
        detail = bytes(stderr).decode("utf-8", "replace").strip()
        raise ArtifactError(
            f"Git command failed with exit {return_code}: "
            f"{' '.join(arguments)}{': ' + detail if detail else ''}"
        )
    return bytes(stdout)


def repository_root(path: Path) -> Path:
    resolved = git(path, "rev-parse", "--show-toplevel").decode("utf-8").strip()
    return Path(resolved).resolve()


def full_commit(repository: Path, revision: str) -> str:
    resolved = git(
        repository,
        "rev-parse",
        "--verify",
        f"{revision}^{{commit}}",
    ).decode("ascii").strip()
    if not SHA_RE.fullmatch(resolved):
        raise ArtifactError(f"Git returned a non-canonical commit SHA for {revision!r}")
    return resolved


def split_nul(data: bytes, label: str) -> list[bytes]:
    if not data:
        return []
    if not data.endswith(b"\0"):
        raise ArtifactError(f"{label} was not NUL terminated")
    values = data[:-1].split(b"\0")
    if any(not value for value in values):
        raise ArtifactError(f"{label} contained an empty path")
    return values


def display_path(path: bytes) -> str:
    return path.decode("utf-8", "surrogateescape")


def canonical_scope(raw_scopes: list[str]) -> list[str]:
    if not raw_scopes:
        raise ArtifactError("review scope must be explicit in the bound contract")
    scopes: list[str] = []
    for raw_scope in raw_scopes:
        if "\0" in raw_scope:
            raise ArtifactError("scope paths may not contain NUL")
        normalized = raw_scope.removeprefix("./").rstrip("/") or "."
        path = PurePosixPath(normalized)
        if (
            normalized == "."
            or path.is_absolute()
            or ".." in path.parts
            or normalized != path.as_posix()
            or ".git" in path.parts
        ):
            raise ArtifactError(f"scope must stay inside the repository: {raw_scope!r}")
        if any(character in normalized for character in "*?["):
            raise ArtifactError(f"scope must be a literal path: {raw_scope!r}")
        scopes.append(normalized)
    return sorted(set(scopes), key=os.fsencode)


def canonical_request_ids(
    label: str, raw_ids: list[str], *, required: bool
) -> list[str]:
    if required and not raw_ids:
        raise ArtifactError(f"at least one {label} is required")
    invalid = [
        request_id
        for request_id in raw_ids
        if not REQUEST_ID_RE.fullmatch(request_id)
    ]
    if invalid:
        raise ArtifactError(
            f"{label} values must use 1-128 canonical ID characters"
        )
    if len(set(raw_ids)) != len(raw_ids):
        raise ArtifactError(f"{label} values must be unique")
    return sorted(raw_ids)


def split_contract_binding(raw_binding: str) -> tuple[str, Path]:
    request_id, separator, raw_path = raw_binding.partition("=")
    if (
        not separator
        or not REQUEST_ID_RE.fullmatch(request_id)
        or not raw_path
    ):
        raise ArtifactError(
            "contract bindings must use canonical ID=/absolute/path syntax"
        )
    path = Path(raw_path)
    if not path.is_absolute() or str(path.resolve()) != raw_path:
        raise ArtifactError("contract paths must be absolute and resolved")
    return request_id, path


def unique_contract_field(
    lines: list[str],
    field: str,
    contract_label: str,
) -> str:
    """Read one exact top-level field from a compact line-oriented contract."""
    prefix = f"{field}:"
    values = [
        line[len(prefix) :].strip()
        for line in lines[1:]
        if line.startswith(prefix)
    ]
    if len(values) != 1 or not values[0]:
        raise ArtifactError(
            f"{contract_label} must contain exactly one non-empty {field!r} field"
        )
    return values[0]


def exact_contract_field(
    line: str,
    field: str,
    contract_label: str,
) -> str:
    prefix = f"{field}:"
    if not line.startswith(prefix):
        raise ArtifactError(
            f"{contract_label} field {field!r} is missing or out of order"
        )
    value = line[len(prefix) :].strip()
    if not value:
        raise ArtifactError(f"{contract_label} field {field!r} is empty")
    return value


def canonical_decision_reference(raw_reference: str) -> str:
    """Return one canonical docs Markdown path or docs/decisions.md#Bnn."""
    path_part, fragment_separator, fragment = raw_reference.partition("#")
    reference = PurePosixPath(path_part)
    if (
        not raw_reference
        or "\\" in raw_reference
        or reference.is_absolute()
        or ".." in reference.parts
        or ".git" in reference.parts
        or len(reference.parts) < 2
        or reference.parts[0] != "docs"
        or reference.suffix != ".md"
        or path_part != reference.as_posix()
        or (
            fragment_separator
            and (
                reference.as_posix() != "docs/decisions.md"
                or not re.fullmatch(r"B[1-9][0-9]*", fragment)
            )
        )
    ):
        raise ArtifactError(
            "decision reference is not a canonical docs Markdown path or "
            f"docs/decisions.md#Bnn selector: {raw_reference!r}"
        )
    return (
        f"{reference.as_posix()}#{fragment}"
        if fragment_separator
        else reference.as_posix()
    )


def section_entries(
    lines: list[str],
    section: str,
    contract_label: str,
) -> list[str]:
    """Read one exact indented-list section from a compact contract."""
    header = f"{section}:"
    header_indexes = [index for index, line in enumerate(lines) if line == header]
    if len(header_indexes) != 1:
        raise ArtifactError(
            f"{contract_label} must contain exactly one {section!r} section"
        )
    entries: list[str] = []
    for line in lines[header_indexes[0] + 1 :]:
        if line and not line.startswith(" "):
            break
        if not line:
            raise ArtifactError(
                f"{contract_label} {section!r} section may not contain blank lines"
            )
        if not line.startswith("  - ") or not line.removeprefix("  - ").strip():
            raise ArtifactError(
                f"{contract_label} {section!r} entries must use '  - value'"
            )
        entries.append(line.removeprefix("  - ").strip())
    if not entries:
        raise ArtifactError(
            f"{contract_label} must contain at least one {section!r} entry"
        )
    return entries


def scope_path(entry: str, contract_label: str) -> str:
    raw = entry.removeprefix("create: ")
    path, separator, description = raw.partition(" — ")
    if not separator or not description.strip():
        raise ArtifactError(
            f"{contract_label} scope entries require 'path — allowed change'"
        )
    return canonical_scope([path])[0]


def validate_review_request(lines: list[str], contract_label: str) -> None:
    expected_top_level = [
        "id",
        "base_sha",
        "goal",
        "specification",
        "scope",
        "acceptance",
        "verification",
        "risk",
    ]
    actual_top_level = [
        line.split(":", 1)[0]
        for line in lines[1:]
        if line and not line.startswith(" ")
    ]
    if actual_top_level != expected_top_level:
        raise ArtifactError(
            f"{contract_label} fields are missing, duplicated, or out of order"
        )
    unique_contract_field(lines, "id", contract_label)
    unique_contract_field(lines, "base_sha", contract_label)
    unique_contract_field(lines, "goal", contract_label)
    specification = unique_contract_field(
        lines,
        "specification",
        contract_label,
    )
    if specification.startswith("ref: "):
        canonical_decision_reference(specification.removeprefix("ref: "))
    elif not specification.startswith("choice: ") or not specification.removeprefix(
        "choice: "
    ).strip():
        raise ArtifactError(
            f"{contract_label} specification must use 'ref: ...' or 'choice: ...'"
        )
    for entry in section_entries(lines, "scope", contract_label):
        scope_path(entry, contract_label)
    section_entries(lines, "acceptance", contract_label)
    for entry in section_entries(lines, "verification", contract_label):
        command, separator, expected_evidence = entry.partition(" — ")
        if not separator or not command.strip() or not expected_evidence.strip():
            raise ArtifactError(
                f"{contract_label} verification entries require "
                "'command — expected evidence'"
            )
    risk = unique_contract_field(lines, "risk", contract_label)
    if not re.fullmatch(r"R[0-2] — \S(?:.*\S)?", risk):
        raise ArtifactError(
            f"{contract_label} risk must be R0, R1, or R2 plus an em-dash reason"
        )


def parse_verification_blocks(
    lines: list[str],
    *,
    start: int,
    end: int,
    contract_label: str,
) -> None:
    block = lines[start:end]
    if not block or len(block) % 3 != 0:
        raise ArtifactError(
            f"{contract_label} must contain complete verification triples"
        )
    for index in range(0, len(block), 3):
        command = exact_contract_field(
            block[index].removeprefix("  - "),
            "command",
            contract_label,
        ) if block[index].startswith("  - command:") else ""
        result = exact_contract_field(
            block[index + 1].removeprefix("    "),
            "result",
            contract_label,
        ) if block[index + 1].startswith("    result:") else ""
        exit_code = exact_contract_field(
            block[index + 2].removeprefix("    "),
            "exit_code",
            contract_label,
        ) if block[index + 2].startswith("    exit_code:") else ""
        if not command or not result or not re.fullmatch(r"[0-9]{1,3}", exit_code):
            raise ArtifactError(
                f"{contract_label} verification entries are malformed"
            )
        if int(exit_code) > 255:
            raise ArtifactError(
                f"{contract_label} verification exit_code must be between 0 and 255"
            )


def validate_kokk_result(
    lines: list[str],
    contract_label: str,
) -> tuple[str, str]:
    if len(lines) < 11:
        raise ArtifactError(f"{contract_label} is incomplete")
    status = exact_contract_field(lines[1], "status", contract_label)
    brief_id = exact_contract_field(lines[2], "brief_id", contract_label)
    result_id = exact_contract_field(lines[3], "result_id", contract_label)
    exact_contract_field(lines[4], "summary", contract_label)
    exact_contract_field(lines[5], "changed_files", contract_label)
    if lines[6] != "verification:":
        raise ArtifactError(
            f"{contract_label} field 'verification' is missing or out of order"
        )
    concerns_index = next(
        (
            index
            for index in range(7, len(lines))
            if lines[index].startswith("concerns_or_blockers:")
        ),
        -1,
    )
    if concerns_index == -1 or concerns_index != len(lines) - 2:
        raise ArtifactError(
            f"{contract_label} concerns_or_blockers is missing or out of order"
        )
    parse_verification_blocks(
        lines,
        start=7,
        end=concerns_index,
        contract_label=contract_label,
    )
    concerns = exact_contract_field(
        lines[concerns_index],
        "concerns_or_blockers",
        contract_label,
    )
    needed = exact_contract_field(lines[-1], "needed", contract_label)
    if status not in COMPLETED_KOKK_STATUSES:
        raise ArtifactError(f"{contract_label} is not a completed Kokk result")
    if needed != "none":
        raise ArtifactError(f"{contract_label} completed result must set needed: none")
    if status == "DONE" and concerns != "none":
        raise ArtifactError(f"{contract_label} DONE result must have no concerns")
    if status == "DONE_WITH_CONCERNS" and concerns == "none":
        raise ArtifactError(
            f"{contract_label} DONE_WITH_CONCERNS must name a concern"
        )
    return brief_id, result_id


def validate_review_evidence(
    lines: list[str],
    contract_label: str,
) -> tuple[str, str]:
    if len(lines) < 7:
        raise ArtifactError(f"{contract_label} is incomplete")
    request_id = exact_contract_field(lines[1], "request_id", contract_label)
    result_id = exact_contract_field(lines[2], "result_id", contract_label)
    if lines[3] != "verification:":
        raise ArtifactError(
            f"{contract_label} field 'verification' is missing or out of order"
        )
    concerns_index = next(
        (
            index
            for index in range(4, len(lines))
            if lines[index].startswith("concerns:")
        ),
        -1,
    )
    if concerns_index == -1 or concerns_index != len(lines) - 1:
        raise ArtifactError(
            f"{contract_label} concerns is missing or out of order"
        )
    parse_verification_blocks(
        lines,
        start=4,
        end=concerns_index,
        contract_label=contract_label,
    )
    exact_contract_field(lines[-1], "concerns", contract_label)
    return request_id, result_id


def contract_text(
    raw_binding: str,
    *,
    label: str,
    repository: Path,
) -> tuple[str, str]:
    """Load one already path-safe, owner-read-only UTF-8 contract."""
    binding_id, path = split_contract_binding(raw_binding)
    validate_artifact_file(path, repository)
    try:
        content = path.read_bytes()
        text = content.decode("utf-8")
    except (OSError, UnicodeError) as error:
        raise ArtifactError(
            f"unable to read UTF-8 {label} contract {binding_id!r}: {error}"
        ) from error
    if not content or b"\0" in content:
        raise ArtifactError(
            f"{label} contract {binding_id!r} must be non-empty UTF-8 text"
        )
    if len(content) > CONTRACT_BYTE_LIMIT:
        raise ArtifactError(
            f"{label} contract {binding_id!r} exceeds "
            f"{CONTRACT_BYTE_LIMIT} bytes"
        )
    return binding_id, text


def validate_contract_relationships(
    raw_briefs: list[str],
    raw_results: list[str],
    repository: Path,
    artifact_base_sha: str,
    artifact_head_sha: str,
) -> None:
    """Bind CLI labels to contract-internal IDs and their request relationship."""
    if not raw_briefs:
        raise ArtifactError("at least one brief contract is required")
    if not raw_results:
        raise ArtifactError("at least one result contract is required")

    brief_kinds: dict[str, str] = {}
    brief_bases: dict[str, str] = {}
    for raw_binding in raw_briefs:
        binding_id, text = contract_text(
            raw_binding,
            label="brief",
            repository=repository,
        )
        lines = text.splitlines()
        if not lines or lines[0] not in {
            "IMPLEMENTATION_BRIEF v1",
            "REVIEW_REQUEST v1",
        }:
            raise ArtifactError(
                f"brief contract {binding_id!r} has an unsupported header"
            )
        internal_id = unique_contract_field(lines, "id", f"brief {binding_id!r}")
        if not BRIEF_ID_RE.fullmatch(internal_id):
            raise ArtifactError(
                "brief IDs must use 1-96 canonical ID characters so a bounded "
                "result suffix remains representable"
            )
        if internal_id != binding_id:
            raise ArtifactError(
                f"brief binding ID {binding_id!r} does not match internal "
                f"id {internal_id!r}"
            )
        brief_kinds[binding_id] = (
            "kokk"
            if lines[0] == "IMPLEMENTATION_BRIEF v1"
            else "barista"
        )
        if lines[0] == "REVIEW_REQUEST v1":
            validate_review_request(lines, f"brief {binding_id!r}")
        internal_base = unique_contract_field(
            lines,
            "base_sha",
            f"brief {binding_id!r}",
        )
        if not SHA_RE.fullmatch(internal_base):
            raise ArtifactError(
                f"brief contract {binding_id!r} has a non-canonical base_sha"
            )
        if full_commit(repository, internal_base) != internal_base:
            raise ArtifactError(
                f"brief contract {binding_id!r} base_sha does not resolve exactly"
            )
        brief_bases[binding_id] = internal_base

    def is_ancestor(ancestor: str, descendant: str) -> bool:
        result = subprocess.run(
            ["git", "merge-base", "--is-ancestor", ancestor, descendant],
            cwd=repository,
            env=git_environment(),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode not in {0, 1}:
            raise ArtifactError("unable to verify bound brief baseline ancestry")
        return result.returncode == 0

    unique_bases = sorted(set(brief_bases.values()))
    for index, left_base in enumerate(unique_bases):
        for right_base in unique_bases[index + 1 :]:
            if not is_ancestor(left_base, right_base) and not is_ancestor(
                right_base, left_base
            ):
                raise ArtifactError(
                    "bound brief baselines must form one ancestor-ordered chain"
                )

    earliest_candidates = []
    for candidate in unique_bases:
        if all(is_ancestor(candidate, other) for other in unique_bases):
            earliest_candidates.append(candidate)
    if len(earliest_candidates) != 1:
        raise ArtifactError("bound brief baselines are divergent or ambiguous")
    if artifact_base_sha != earliest_candidates[0]:
        raise ArtifactError(
            "artifact base must equal the earliest bound brief base_sha"
        )
    for brief_id, brief_base in brief_bases.items():
        if not is_ancestor(brief_base, artifact_head_sha):
            raise ArtifactError(
                f"brief {brief_id!r} base_sha is not on the artifact HEAD history"
            )

    result_count_by_brief: dict[str, int] = {}
    for raw_binding in raw_results:
        binding_id, text = contract_text(
            raw_binding,
            label="result",
            repository=repository,
        )
        lines = text.splitlines()
        if not lines:
            raise ArtifactError(f"result contract {binding_id!r} is empty")
        if lines[0] == "KOKK_RESULT":
            brief_id, result_id = validate_kokk_result(
                lines,
                f"result {binding_id!r}",
            )
            suffix_prefix = f"{brief_id}.attempt-"
            attempt = result_id.removeprefix(suffix_prefix)
            if (
                brief_kinds.get(brief_id) != "kokk"
                or result_id != binding_id
                or not result_id.startswith(suffix_prefix)
                or not ATTEMPT_NUMBER_RE.fullmatch(attempt)
                or not REQUEST_ID_RE.fullmatch(result_id)
            ):
                raise ArtifactError(
                    f"result contract {binding_id!r} does not bind a canonical "
                    "Kokk brief/result identity"
                )
        elif lines[0] == "REVIEW_EVIDENCE v1":
            brief_id, result_id = validate_review_evidence(
                lines,
                f"result {binding_id!r}",
            )
            suffix_prefix = f"{brief_id}.evidence-"
            evidence_number = result_id.removeprefix(suffix_prefix)
            if (
                brief_kinds.get(brief_id) != "barista"
                or result_id != binding_id
                or not result_id.startswith(suffix_prefix)
                or not ATTEMPT_NUMBER_RE.fullmatch(evidence_number)
                or not REQUEST_ID_RE.fullmatch(result_id)
            ):
                raise ArtifactError(
                    f"result contract {binding_id!r} does not bind a canonical "
                    "Barista request/evidence identity"
                )
        else:
            raise ArtifactError(
                f"result contract {binding_id!r} has an unsupported header"
            )
        result_count_by_brief[brief_id] = result_count_by_brief.get(brief_id, 0) + 1

    uncovered = sorted(set(brief_kinds) - set(result_count_by_brief))
    if uncovered:
        raise ArtifactError(
            "every brief must have bound implementation evidence; missing "
            + ", ".join(uncovered)
        )
    duplicates = sorted(
        brief_id
        for brief_id, count in result_count_by_brief.items()
        if count != 1
    )
    if duplicates:
        raise ArtifactError(
            "every brief must bind exactly one result; duplicate evidence for "
            + ", ".join(duplicates)
        )


def decision_reference_digests(
    raw_briefs: list[str],
    repository: Path,
) -> tuple[dict[str, str], int]:
    """Bind bounded canonical decision documents referenced by implementation briefs."""
    references: set[str] = set()
    for raw_binding in raw_briefs:
        _binding_id, text = contract_text(
            raw_binding,
            label="brief",
            repository=repository,
        )
        lines = text.splitlines()
        if not lines:
            continue
        if lines[0] == "IMPLEMENTATION_BRIEF v1":
            in_locked_decisions = False
            for line in lines[1:]:
                if line and not line.startswith(" "):
                    in_locked_decisions = line == "locked_decisions:"
                    continue
                if not in_locked_decisions or not line.startswith("  - ref: "):
                    continue
                raw_reference = line.removeprefix("  - ref: ").split(" — ", 1)[0]
                references.add(canonical_decision_reference(raw_reference))
        elif lines[0] == "REVIEW_REQUEST v1":
            specification = unique_contract_field(
                lines,
                "specification",
                f"brief {_binding_id!r}",
            )
            if specification.startswith("ref: "):
                references.add(
                    canonical_decision_reference(
                        specification.removeprefix("ref: ")
                    )
                )

    if len(references) > DECISION_REF_COUNT_LIMIT:
        raise ArtifactError(
            f"NEEDS_SCOPE decision_ref_count={len(references)} "
            f"low_quota_limit={DECISION_REF_COUNT_LIMIT}"
        )

    digests: dict[str, str] = {}
    total_bytes = 0
    for reference in sorted(references):
        path_part, fragment_separator, fragment = reference.partition("#")
        path = repository / path_part
        try:
            resolved = path.resolve(strict=True)
            resolved.relative_to(repository.resolve())
            metadata = path.lstat()
        except (OSError, ValueError) as error:
            raise ArtifactError(
                f"decision reference escapes or is inaccessible: {reference!r}"
            ) from error
        if (
            resolved != repository.resolve() / path_part
            or path.is_symlink()
            or not stat.S_ISREG(metadata.st_mode)
        ):
            raise ArtifactError(
                f"decision reference must be a regular in-repository file: "
                f"{reference!r}"
            )
        try:
            full_content = path.read_bytes()
            decoded = full_content.decode("utf-8")
        except (OSError, UnicodeError) as error:
            raise ArtifactError(
                f"decision reference must be readable UTF-8: {reference!r}"
            ) from error
        content = full_content
        if fragment_separator:
            entry_starts = [
                index
                for index, line in enumerate(decoded.splitlines(keepends=True))
                if line.startswith(f"- {fragment}:")
            ]
            if len(entry_starts) != 1:
                raise ArtifactError(
                    f"decision selector must match exactly one entry: {reference!r}"
                )
            lines = decoded.splitlines(keepends=True)
            start = entry_starts[0]
            end = len(lines)
            for index in range(start + 1, len(lines)):
                if re.match(r"^- B[1-9][0-9]*:", lines[index]):
                    end = index
                    break
            content = "".join(lines[start:end]).encode("utf-8")
        if b"\0" in content or len(content) > DECISION_REF_BYTE_LIMIT:
            raise ArtifactError(
                f"decision reference {reference!r} exceeds the UTF-8 byte limit "
                f"{DECISION_REF_BYTE_LIMIT}"
            )
        total_bytes += len(content)
        if total_bytes > DECISION_REF_TOTAL_BYTE_LIMIT:
            raise ArtifactError(
                f"NEEDS_SCOPE decision_ref_bytes={total_bytes} "
                f"low_quota_limit={DECISION_REF_TOTAL_BYTE_LIMIT}"
            )
        digests[reference] = hashlib.sha256(content).hexdigest()
    return digests, total_bytes


def contract_scopes(raw_briefs: list[str], repository: Path) -> list[str]:
    """Derive the only allowed review scope from immutable bound contracts."""
    scopes: list[str] = []
    for raw_binding in raw_briefs:
        binding_id, text = contract_text(
            raw_binding,
            label="brief",
            repository=repository,
        )
        lines = text.splitlines()
        if not lines or lines[0] not in {
            "IMPLEMENTATION_BRIEF v1",
            "REVIEW_REQUEST v1",
        }:
            raise ArtifactError(
                f"brief contract {binding_id!r} has an unsupported header"
            )
        for entry in section_entries(lines, "scope", f"brief {binding_id!r}"):
            scopes.append(scope_path(entry, f"brief {binding_id!r}"))
    return canonical_scope(scopes)


def path_is_selected(path: bytes, scopes: list[str]) -> bool:
    for scope in scopes:
        if scope == ".":
            return True
        scope_bytes = os.fsencode(scope)
        if path == scope_bytes or path.startswith(scope_bytes + b"/"):
            return True
    return False


def changed_paths(
    repository: Path, base_sha: str, scopes: list[str]
) -> tuple[list[bytes], list[bytes], list[bytes]]:
    tracked = set(
        split_nul(
            git(
                repository,
                "diff",
                "--name-only",
                "-z",
                "--no-renames",
                "--ignore-submodules=none",
                base_sha,
                "--",
            ),
            "tracked path inventory",
        )
    )
    untracked = set(
        split_nul(
            git(repository, "ls-files", "--others", "--exclude-standard", "-z"),
            "untracked path inventory",
        )
    )
    all_paths = sorted(tracked | untracked)
    selected = [path for path in all_paths if path_is_selected(path, scopes)]
    return all_paths, sorted(tracked & set(selected)), sorted(untracked & set(selected))


def index_entries(repository: Path, *arguments: str) -> list[bytes]:
    return split_nul(
        git(repository, "ls-files", *arguments, "-z"),
        "index inventory",
    )


def assert_visible_repository_state(
    repository: Path,
    *,
    label: str = "repository",
    visited: set[Path] | None = None,
) -> None:
    """Reject index flags and nested dirt that normal Git diff can hide."""
    seen = visited if visited is not None else set()
    resolved_repository = repository.resolve()
    if resolved_repository in seen:
        raise ArtifactError(f"recursive Git repository detected at {label}")
    seen.add(resolved_repository)

    for entry in index_entries(repository, "-v"):
        if len(entry) < 3 or entry[1:2] != b" ":
            raise ArtifactError(f"unable to parse index visibility for {label}")
        tag = entry[:1]
        if tag == b"S" or tag.islower():
            raise ArtifactError(
                f"{label} contains assume-unchanged or skip-worktree index flags"
            )

    for entry in index_entries(repository, "--stage"):
        metadata, separator, raw_path = entry.partition(b"\t")
        fields = metadata.split()
        if not separator or len(fields) != 3:
            raise ArtifactError(f"unable to parse staged index entry for {label}")
        if fields[2] != b"0":
            raise ArtifactError(f"{label} contains unresolved index conflicts")
        if fields[0] != b"160000":
            continue
        relative_path = display_path(raw_path)
        submodule_path = repository / relative_path
        if submodule_path.is_symlink() or not submodule_path.is_dir():
            raise ArtifactError(
                f"{label} requires an initialized submodule worktree at "
                f"{relative_path!r}"
            )
        try:
            submodule_root = repository_root(submodule_path)
        except ArtifactError as error:
            raise ArtifactError(
                f"{label} has an unreadable submodule at {relative_path!r}"
            ) from error
        try:
            indexed_commit = fields[1].decode("ascii")
        except UnicodeDecodeError as error:
            raise ArtifactError(
                f"{label} has a non-canonical submodule gitlink"
            ) from error
        if full_commit(submodule_root, "HEAD") != indexed_commit:
            raise ArtifactError(
                f"{label} submodule {relative_path!r} HEAD does not match "
                "the parent index gitlink"
            )
        submodule_status = git(
            submodule_root,
            "status",
            "--porcelain=v2",
            "-z",
            "--untracked-files=all",
            "--ignore-submodules=none",
        )
        if submodule_status:
            raise ArtifactError(
                f"{label} has dirty submodule contents at {relative_path!r}"
            )
        assert_visible_repository_state(
            submodule_root,
            label=f"{label} submodule {relative_path!r}",
            visited=seen,
        )


def diff_arguments() -> list[str]:
    return [
        "--binary",
        "--full-index",
        "--no-ext-diff",
        "--no-textconv",
        "--no-color",
        "--no-renames",
        "--ignore-submodules=none",
        "--diff-algorithm=myers",
        "--src-prefix=a/",
        "--dst-prefix=b/",
    ]


def build_patch(
    repository: Path,
    base_sha: str,
    tracked_paths: list[bytes],
    untracked_paths: list[bytes],
) -> bytes:
    patch_parts: list[bytes] = []
    patch_bytes = 0

    def append_bounded(arguments: list[str], *, returncodes: tuple[int, ...]) -> None:
        nonlocal patch_bytes
        part = git_limited_output(
            repository,
            *arguments,
            byte_limit=PATCH_BYTE_LIMIT - patch_bytes,
            allowed_returncodes=returncodes,
        )
        patch_parts.append(part)
        patch_bytes += len(part)

    if tracked_paths:
        pathspecs = [":(literal)" + display_path(path) for path in tracked_paths]
        append_bounded(
            [
                "diff",
                *diff_arguments(),
                base_sha,
                "--",
                *pathspecs,
            ],
            returncodes=(0,),
        )
    for raw_path in untracked_paths:
        relative_path = display_path(raw_path)
        target = repository / relative_path
        try:
            file_mode = target.lstat().st_mode
        except OSError as error:
            raise ArtifactError(
                f"unable to inspect selected untracked path {relative_path!r}: {error}"
            ) from error
        if not (stat.S_ISREG(file_mode) or stat.S_ISLNK(file_mode)):
            raise ArtifactError(
                f"selected untracked path is not a regular file or symlink: "
                f"{relative_path!r}"
            )
        append_bounded(
            [
                "diff",
                *diff_arguments(),
                "--no-index",
                "--",
                os.devnull,
                relative_path,
            ],
            returncodes=(0, 1),
        )
    return b"".join(patch_parts)


def patch_stat(repository: Path, patch: bytes) -> bytes:
    return git(
        repository,
        "apply",
        "--numstat",
        "--summary",
        "--allow-empty",
        "-",
        input_bytes=patch,
    )


def hash_frame(digest: Any, label: bytes, content: bytes) -> None:
    """Add one unambiguous binary frame to a running digest."""
    digest.update(len(label).to_bytes(4, "big"))
    digest.update(label)
    digest.update(len(content).to_bytes(8, "big"))
    digest.update(content)


def repository_state_sha256(
    repository: Path,
    status: bytes,
    all_paths: list[bytes],
) -> str:
    """Bind index metadata and current bytes for every base-to-worktree path."""
    digest = hashlib.sha256()
    hash_frame(digest, b"status", status)
    hash_frame(
        digest,
        b"index",
        git(repository, "ls-files", "--stage", "-z"),
    )
    for raw_path in all_paths:
        relative_path = display_path(raw_path)
        target = repository / relative_path
        hash_frame(digest, b"path", raw_path)
        try:
            metadata = target.lstat()
        except FileNotFoundError:
            hash_frame(digest, b"type", b"missing")
            continue
        except OSError as error:
            raise ArtifactError(
                f"unable to fingerprint worktree path {relative_path!r}: {error}"
            ) from error

        mode = metadata.st_mode
        hash_frame(digest, b"mode", f"{mode:o}".encode("ascii"))
        if stat.S_ISREG(mode):
            content_digest = hashlib.sha256()
            try:
                with target.open("rb") as source:
                    while chunk := source.read(65_536):
                        content_digest.update(chunk)
            except OSError as error:
                raise ArtifactError(
                    f"unable to fingerprint worktree file "
                    f"{relative_path!r}: {error}"
                ) from error
            hash_frame(digest, b"file", content_digest.digest())
        elif stat.S_ISLNK(mode):
            try:
                link_target = os.fsencode(os.readlink(target))
            except OSError as error:
                raise ArtifactError(
                    f"unable to fingerprint worktree symlink "
                    f"{relative_path!r}: {error}"
                ) from error
            hash_frame(digest, b"symlink", link_target)
        elif stat.S_ISDIR(mode):
            try:
                submodule_head = full_commit(repository_root(target), "HEAD")
            except ArtifactError as error:
                raise ArtifactError(
                    f"worktree directory {relative_path!r} is not a clean "
                    "tracked submodule"
                ) from error
            hash_frame(digest, b"submodule", submodule_head.encode("ascii"))
        else:
            raise ArtifactError(
                f"worktree path has unsupported file type: {relative_path!r}"
            )
    return digest.hexdigest()


def capture_snapshot(
    repository: Path, base_sha: str, scopes: list[str]
) -> dict[str, Any]:
    assert_visible_repository_state(repository)
    head_sha = full_commit(repository, "HEAD")
    ancestor_result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", base_sha, head_sha],
        cwd=repository,
        env=git_environment(),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    ).returncode
    if ancestor_result == 1:
        raise ArtifactError("base SHA must be an ancestor of current HEAD")
    if ancestor_result != 0:
        raise ArtifactError("unable to verify the review base ancestry")
    status = git(
        repository,
        "status",
        "--porcelain=v2",
        "-z",
        "--untracked-files=all",
        "--ignore-submodules=none",
    )
    all_paths, tracked_paths, untracked_paths = changed_paths(
        repository, base_sha, scopes
    )
    selected_paths = sorted(tracked_paths + untracked_paths)
    if selected_paths != all_paths:
        unselected_count = len(set(all_paths) - set(selected_paths))
        raise ArtifactError(
            f"NEEDS_CONTEXT unselected_worktree_paths={unselected_count}; "
            "review scope must cover the complete base-to-worktree change"
        )
    ensure_captured_path_count(len(all_paths))
    input_bytes = 0
    for raw_path in all_paths:
        target = repository / display_path(raw_path)
        try:
            metadata = target.lstat()
        except FileNotFoundError:
            continue
        except OSError as error:
            raise ArtifactError(f"unable to size review input: {error}") from error
        if stat.S_ISREG(metadata.st_mode):
            input_bytes += metadata.st_size
            if input_bytes > WORKTREE_INPUT_BYTE_LIMIT:
                raise ArtifactError(
                    f"NEEDS_SCOPE worktree_input_bytes={input_bytes} "
                    f"low_quota_limit={WORKTREE_INPUT_BYTE_LIMIT}"
                )
    if not selected_paths:
        raise ArtifactError("selected review patch is empty")
    patch = build_patch(repository, base_sha, tracked_paths, untracked_paths)
    if not patch:
        raise ArtifactError("selected review patch is empty")
    return {
        "head_sha": head_sha,
        "all_paths": all_paths,
        "repository_state_sha256": repository_state_sha256(
            repository,
            status,
            all_paths,
        ),
        "selected_paths": selected_paths,
        "patch": patch,
    }


def stable_snapshot(
    repository: Path, base_sha: str, scopes: list[str]
) -> dict[str, Any]:
    first = capture_snapshot(repository, base_sha, scopes)
    second = capture_snapshot(repository, base_sha, scopes)
    if first != second:
        raise ArtifactError("worktree changed while the review artifact was captured")
    return first


def canonical_json(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("ascii")


def payload_from_snapshot(
    repository: Path,
    base_sha: str,
    scopes: list[str],
    brief_ids: list[str],
    brief_sha256: dict[str, str],
    decision_ref_sha256: dict[str, str],
    decision_ref_bytes: int,
    result_ids: list[str],
    result_sha256: dict[str, str],
    snapshot: dict[str, Any],
) -> dict[str, Any]:
    patch = snapshot["patch"]
    commits = git(repository, "rev-list", "--reverse", f"{base_sha}..{snapshot['head_sha']}")
    commit_shas = commits.decode("ascii").splitlines()
    if len(commit_shas) > COMMIT_COUNT_LIMIT:
        raise ArtifactError(
            f"NEEDS_SCOPE commit_count={len(commit_shas)} "
            f"low_quota_limit={COMMIT_COUNT_LIMIT}"
        )
    ensure_captured_path_count(len(snapshot["selected_paths"]))
    return {
        "artifact_contract": ARTIFACT_CONTRACT,
        "base_sha": base_sha,
        "brief_ids": brief_ids,
        "brief_sha256": brief_sha256,
        "captured_paths": [display_path(path) for path in snapshot["selected_paths"]],
        "commit_count": len(commit_shas),
        "commit_sha256": hashlib.sha256(commits).hexdigest(),
        "decision_ref_bytes": decision_ref_bytes,
        "decision_ref_sha256": decision_ref_sha256,
        "head_sha": snapshot["head_sha"],
        "patch_bytes": len(patch),
        "patch_sha256": hashlib.sha256(patch).hexdigest(),
        "patch_stat_base64": base64.b64encode(patch_stat(repository, patch)).decode(
            "ascii"
        ),
        "result_ids": result_ids,
        "result_sha256": result_sha256,
        "repository_state_sha256": snapshot["repository_state_sha256"],
        "scope": scopes,
    }


def ensure_captured_path_count(path_count: int) -> None:
    if path_count > CAPTURED_PATH_COUNT_LIMIT:
        raise ArtifactError(
            f"NEEDS_SCOPE captured_path_count={path_count} "
            f"low_quota_limit={CAPTURED_PATH_COUNT_LIMIT}"
        )


def manifest_from_payload(payload: dict[str, Any]) -> dict[str, Any]:
    artifact_id = hashlib.sha256(canonical_json(payload)).hexdigest()
    return {**payload, "artifact_id": artifact_id}


def ensure_patch_limit(patch_bytes: int) -> None:
    if patch_bytes > PATCH_BYTE_LIMIT:
        raise ArtifactError(
            f"NEEDS_SCOPE patch_bytes={patch_bytes} "
            f"low_quota_limit={PATCH_BYTE_LIMIT}"
        )


def ensure_manifest_limit(manifest_bytes: int) -> None:
    if manifest_bytes > MANIFEST_BYTE_LIMIT:
        raise ArtifactError(
            f"NEEDS_SCOPE manifest_bytes={manifest_bytes} "
            f"low_quota_limit={MANIFEST_BYTE_LIMIT}"
        )


def prepare_output_directory(output_dir: Path, repository: Path) -> None:
    if not output_dir.is_absolute() or output_dir.resolve() != output_dir:
        raise ArtifactError(
            "review output directory must be a canonical absolute path"
        )
    try:
        output_dir.resolve().relative_to(repository.resolve())
    except ValueError:
        pass
    else:
        raise ArtifactError("review artifacts must be outside the repository")
    if output_dir.exists():
        if not output_dir.is_dir():
            raise ArtifactError(f"output path is not a directory: {output_dir}")
        if any(output_dir.iterdir()):
            raise ArtifactError(f"output directory must be empty: {output_dir}")
        mode = stat.S_IMODE(output_dir.stat().st_mode)
        if output_dir.stat().st_uid != os.getuid():
            raise ArtifactError("output directory must be owned by the current user")
        if mode & 0o077:
            raise ArtifactError(
                f"output directory must be owner-only (mode was {mode:04o})"
            )
    else:
        output_dir.mkdir(mode=0o700, parents=False)


def write_read_only(path: Path, content: bytes) -> None:
    temporary = path.with_name("." + path.name + ".tmp")
    try:
        with temporary.open("xb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        temporary.chmod(0o400)
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def capture(
    repository: Path,
    base_revision: str,
    output_dir: Path,
    raw_scopes: list[str],
    raw_briefs: list[str],
    raw_results: list[str],
) -> tuple[Path, Path, dict[str, Any]]:
    root = repository_root(repository)
    base_sha = full_commit(root, base_revision)
    expected_head_sha = full_commit(root, "HEAD")
    validate_contract_relationships(
        raw_briefs,
        raw_results,
        root,
        base_sha,
        expected_head_sha,
    )
    brief_ids, brief_sha256 = contract_digests(
        raw_briefs,
        label="brief",
        required=True,
        repository=root,
    )
    result_ids, result_sha256 = contract_digests(
        raw_results,
        label="result",
        required=True,
        repository=root,
    )
    decision_ref_sha256, decision_ref_bytes = decision_reference_digests(
        raw_briefs,
        root,
    )
    contract_bytes = ensure_contract_set_budget(raw_briefs, raw_results)
    prepare_output_directory(output_dir, root)
    scopes = contract_scopes(raw_briefs, root)
    if raw_scopes and canonical_scope(raw_scopes) != scopes:
        raise ArtifactError(
            "caller-supplied scope does not exactly match the bound contracts"
        )
    snapshot = stable_snapshot(root, base_sha, scopes)
    if snapshot["head_sha"] != expected_head_sha:
        raise ArtifactError("HEAD changed during artifact capture")
    if decision_reference_digests(raw_briefs, root) != (
        decision_ref_sha256,
        decision_ref_bytes,
    ):
        raise ArtifactError("decision references changed during artifact capture")
    payload = payload_from_snapshot(
        root,
        base_sha,
        scopes,
        brief_ids,
        brief_sha256,
        decision_ref_sha256,
        decision_ref_bytes,
        result_ids,
        result_sha256,
        snapshot,
    )
    ensure_patch_limit(payload["patch_bytes"])
    manifest = manifest_from_payload(payload)
    patch_path = output_dir / PATCH_NAME
    manifest_path = output_dir / MANIFEST_NAME
    manifest_bytes = canonical_json(manifest)
    ensure_manifest_limit(len(manifest_bytes))
    prompt_bytes = inspector_prompt_bytes(
        manifest,
        manifest_path,
        patch_path,
        raw_briefs,
        raw_results,
    )
    ensure_review_context_budget(
        payload["patch_bytes"],
        contract_bytes,
        decision_ref_bytes,
        len(manifest_bytes),
        prompt_bytes,
    )
    write_read_only(patch_path, snapshot["patch"])
    write_read_only(manifest_path, manifest_bytes)
    return manifest_path, patch_path, manifest


def load_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    try:
        raw = path.read_bytes()
        manifest = json.loads(raw)
    except (OSError, json.JSONDecodeError) as error:
        raise ArtifactError(f"unable to read canonical manifest: {error}") from error
    if not isinstance(manifest, dict) or canonical_json(manifest) != raw:
        raise ArtifactError("manifest is not canonical JSON")
    ensure_manifest_limit(len(raw))
    if set(manifest) != MANIFEST_FIELDS:
        raise ArtifactError("manifest fields do not match the artifact contract")
    artifact_id = manifest.get("artifact_id")
    if not isinstance(artifact_id, str) or not ARTIFACT_ID_RE.fullmatch(artifact_id):
        raise ArtifactError("manifest has an invalid artifact_id")
    payload = dict(manifest)
    del payload["artifact_id"]
    expected_id = hashlib.sha256(canonical_json(payload)).hexdigest()
    if artifact_id != expected_id:
        raise ArtifactError("manifest artifact_id does not match its content")
    for field in (
        "brief_ids",
        "captured_paths",
        "result_ids",
        "scope",
    ):
        value = manifest.get(field)
        if not isinstance(value, list) or not all(
            isinstance(item, str) for item in value
        ):
            raise ArtifactError(f"manifest field {field!r} must be a string list")
    if canonical_request_ids(
        "brief-id", manifest["brief_ids"], required=True
    ) != manifest["brief_ids"]:
        raise ArtifactError("manifest brief_ids are not canonical")
    if canonical_request_ids(
        "result-id", manifest["result_ids"], required=True
    ) != manifest["result_ids"]:
        raise ArtifactError("manifest result_ids are not canonical")
    if len(manifest["captured_paths"]) > CAPTURED_PATH_COUNT_LIMIT:
        raise ArtifactError("manifest captured_paths exceeds its count limit")
    for ids_field, digest_field in (
        ("brief_ids", "brief_sha256"),
        ("result_ids", "result_sha256"),
    ):
        digests = manifest.get(digest_field)
        if (
            not isinstance(digests, dict)
            or set(digests) != set(manifest[ids_field])
            or not all(
                isinstance(request_id, str)
                and isinstance(digest, str)
                and ARTIFACT_ID_RE.fullmatch(digest)
                for request_id, digest in digests.items()
            )
        ):
            raise ArtifactError(
                f"manifest field {digest_field!r} does not bind {ids_field}"
            )
    decision_digests = manifest.get("decision_ref_sha256")
    if (
        not isinstance(decision_digests, dict)
        or len(decision_digests) > DECISION_REF_COUNT_LIMIT
        or not all(
            isinstance(reference, str)
            and reference.startswith("docs/")
            and (
                reference.endswith(".md")
                or re.fullmatch(r"docs/decisions\.md#B[1-9][0-9]*", reference)
            )
            and isinstance(digest, str)
            and ARTIFACT_ID_RE.fullmatch(digest)
            for reference, digest in decision_digests.items()
        )
    ):
        raise ArtifactError("manifest decision_ref_sha256 is invalid")
    decision_ref_bytes = manifest.get("decision_ref_bytes")
    if (
        not isinstance(decision_ref_bytes, int)
        or decision_ref_bytes < 0
        or decision_ref_bytes > DECISION_REF_TOTAL_BYTE_LIMIT
    ):
        raise ArtifactError("manifest decision_ref_bytes is invalid")
    for field in ("base_sha", "head_sha"):
        value = manifest.get(field)
        if not isinstance(value, str) or not SHA_RE.fullmatch(value):
            raise ArtifactError(f"manifest field {field!r} is not a full commit SHA")
    for field in (
        "patch_sha256",
        "commit_sha256",
        "repository_state_sha256",
    ):
        digest = manifest.get(field)
        if not isinstance(digest, str) or not ARTIFACT_ID_RE.fullmatch(digest):
            raise ArtifactError(f"manifest {field} is invalid")
    if not isinstance(manifest.get("patch_bytes"), int):
        raise ArtifactError("manifest patch_bytes must be an integer")
    commit_count = manifest.get("commit_count")
    if (
        not isinstance(commit_count, int)
        or commit_count < 0
        or commit_count > COMMIT_COUNT_LIMIT
    ):
        raise ArtifactError("manifest commit_count is invalid")
    for field in ("patch_stat_base64",):
        value = manifest.get(field)
        if not isinstance(value, str):
            raise ArtifactError(f"manifest field {field!r} must be base64 text")
        try:
            base64.b64decode(value, validate=True)
        except ValueError as error:
            raise ArtifactError(
                f"manifest field {field!r} is not valid base64"
            ) from error
    return manifest, raw


def validate_artifact_file(path: Path, repository: Path) -> None:
    if not path.is_absolute() or path.resolve() != path:
        raise ArtifactError(
            f"review artifact path must be canonical and absolute: {path}"
        )
    if path.is_symlink() or not path.is_file():
        raise ArtifactError(f"review artifact must be a regular file: {path}")
    metadata = path.stat()
    if metadata.st_uid != os.getuid():
        raise ArtifactError(f"review artifact must be owned by the current user: {path}")
    if stat.S_IMODE(metadata.st_mode) & 0o277:
        raise ArtifactError(f"review artifact must be owner-read-only: {path}")
    parent = path.parent
    parent_metadata = parent.stat()
    if parent_metadata.st_uid != os.getuid() or (
        stat.S_IMODE(parent_metadata.st_mode) & 0o077
    ):
        raise ArtifactError("review artifact directory must be owner-only")
    try:
        path.resolve().relative_to(repository.resolve())
    except ValueError:
        return
    raise ArtifactError("review artifacts must be outside the repository")


def contract_digests(
    raw_bindings: list[str],
    *,
    label: str,
    required: bool,
    repository: Path,
) -> tuple[list[str], dict[str, str]]:
    if required and not raw_bindings:
        raise ArtifactError(f"at least one {label} contract is required")
    bindings = [split_contract_binding(binding) for binding in raw_bindings]
    ids = canonical_request_ids(
        label,
        [request_id for request_id, _path in bindings],
        required=required,
    )
    digests: dict[str, str] = {}
    for request_id, path in bindings:
        validate_artifact_file(path, repository)
        try:
            content = path.read_bytes()
            content.decode("utf-8")
        except (OSError, UnicodeError) as error:
            raise ArtifactError(
                f"unable to read UTF-8 {label} contract {request_id!r}: {error}"
            ) from error
        if not content or b"\0" in content:
            raise ArtifactError(
                f"{label} contract {request_id!r} must be non-empty UTF-8 text"
            )
        if len(content) > CONTRACT_BYTE_LIMIT:
            raise ArtifactError(
                f"{label} contract {request_id!r} exceeds "
                f"{CONTRACT_BYTE_LIMIT} bytes"
            )
        digests[request_id] = hashlib.sha256(content).hexdigest()
    return ids, digests


def ensure_contract_set_budget(
    raw_briefs: list[str],
    raw_results: list[str],
) -> int:
    """Bound aggregate contract context independently of the patch limit."""
    raw_bindings = [*raw_briefs, *raw_results]
    if len(raw_bindings) > CONTRACT_COUNT_LIMIT:
        raise ArtifactError(
            f"NEEDS_SCOPE contract_count={len(raw_bindings)} "
            f"low_quota_limit={CONTRACT_COUNT_LIMIT}"
        )
    total_bytes = sum(
        split_contract_binding(raw_binding)[1].stat().st_size
        for raw_binding in raw_bindings
    )
    if total_bytes > CONTRACT_TOTAL_BYTE_LIMIT:
        raise ArtifactError(
            f"NEEDS_SCOPE contract_bytes={total_bytes} "
            f"low_quota_limit={CONTRACT_TOTAL_BYTE_LIMIT}"
        )
    return total_bytes


def ensure_review_context_budget(
    patch_bytes: int,
    contract_bytes: int,
    decision_ref_bytes: int,
    manifest_bytes: int,
    prompt_bytes: int,
) -> None:
    total_bytes = (
        patch_bytes
        + contract_bytes
        + decision_ref_bytes
        + manifest_bytes
        + prompt_bytes
    )
    if total_bytes > REVIEW_CONTEXT_BYTE_LIMIT:
        raise ArtifactError(
            f"NEEDS_SCOPE review_context_bytes={total_bytes} "
            f"low_quota_limit={REVIEW_CONTEXT_BYTE_LIMIT}"
        )


def validate_contracts(
    repository: Path,
    base_revision: str,
    raw_briefs: list[str],
    raw_results: list[str],
) -> tuple[list[str], list[str]]:
    """Validate immutable request/result contracts before artifact capture."""
    root = repository_root(repository)
    base_sha = full_commit(root, base_revision)
    head_sha = full_commit(root, "HEAD")
    validate_contract_relationships(
        raw_briefs,
        raw_results,
        root,
        base_sha,
        head_sha,
    )
    brief_ids, _brief_sha256 = contract_digests(
        raw_briefs,
        label="brief",
        required=True,
        repository=root,
    )
    result_ids, _result_sha256 = contract_digests(
        raw_results,
        label="result",
        required=True,
        repository=root,
    )
    decision_reference_digests(raw_briefs, root)
    contract_scopes(raw_briefs, root)
    ensure_contract_set_budget(raw_briefs, raw_results)
    return brief_ids, result_ids


def inspector_prompt_bytes(
    manifest: dict[str, Any],
    manifest_path: Path,
    patch_path: Path,
    raw_briefs: list[str],
    raw_results: list[str],
) -> int:
    contracts = {
        f"{label}:{binding_id}": str(path)
        for label, raw_bindings in (
            ("brief", raw_briefs),
            ("result", raw_results),
        )
        for binding_id, path in (
            split_contract_binding(raw_binding)
            for raw_binding in raw_bindings
        )
    }
    prompt = (
        "INSPECTOR_REVIEW v1\n"
        "INSPECTOR_ARTIFACT v1\n"
        f"artifact_id: {manifest['artifact_id']}\n"
        f"manifest: {manifest_path}\n"
        f"patch: {patch_path}\n"
        "contracts: "
        + json.dumps(
            contracts,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    )
    return len(prompt.encode("utf-8"))


def verify(
    manifest_path: Path,
    patch_path: Path,
    repository: Path,
    raw_briefs: list[str],
    raw_results: list[str],
) -> dict[str, Any]:
    root = repository_root(repository)
    validate_artifact_file(manifest_path, root)
    validate_artifact_file(patch_path, root)
    manifest, manifest_bytes = load_manifest(manifest_path)
    if manifest.get("artifact_contract") != ARTIFACT_CONTRACT:
        raise ArtifactError("manifest uses an unsupported artifact contract")
    patch = patch_path.read_bytes()
    if len(patch) != manifest.get("patch_bytes"):
        raise ArtifactError("patch byte count does not match the manifest")
    if hashlib.sha256(patch).hexdigest() != manifest.get("patch_sha256"):
        raise ArtifactError("patch checksum does not match the manifest")
    ensure_patch_limit(len(patch))
    validate_contract_relationships(
        raw_briefs,
        raw_results,
        root,
        manifest["base_sha"],
        manifest["head_sha"],
    )
    brief_ids, brief_sha256 = contract_digests(
        raw_briefs,
        label="brief",
        required=True,
        repository=root,
    )
    result_ids, result_sha256 = contract_digests(
        raw_results,
        label="result",
        required=True,
        repository=root,
    )
    decision_ref_sha256, decision_ref_bytes = decision_reference_digests(
        raw_briefs,
        root,
    )
    contract_bytes = ensure_contract_set_budget(raw_briefs, raw_results)
    ensure_review_context_budget(
        len(patch),
        contract_bytes,
        decision_ref_bytes,
        len(manifest_bytes),
        inspector_prompt_bytes(
            manifest,
            manifest_path,
            patch_path,
            raw_briefs,
            raw_results,
        ),
    )
    if (
        brief_ids != manifest["brief_ids"]
        or brief_sha256 != manifest["brief_sha256"]
        or result_ids != manifest["result_ids"]
        or result_sha256 != manifest["result_sha256"]
        or decision_ref_sha256 != manifest["decision_ref_sha256"]
        or decision_ref_bytes != manifest["decision_ref_bytes"]
    ):
        raise ArtifactError(
            "contract or decision files do not match the captured manifest"
        )

    base_sha = manifest.get("base_sha")
    raw_scopes = manifest.get("scope")
    if not isinstance(base_sha, str) or not SHA_RE.fullmatch(base_sha):
        raise ArtifactError("manifest base_sha is invalid")
    if not isinstance(raw_scopes, list) or not all(
        isinstance(scope, str) for scope in raw_scopes
    ):
        raise ArtifactError("manifest scope is invalid")
    scopes = canonical_scope(raw_scopes)
    if scopes != raw_scopes:
        raise ArtifactError("manifest scope is not canonical")
    if contract_scopes(raw_briefs, root) != scopes:
        raise ArtifactError(
            "manifest scope does not match the immutable bound contracts"
        )
    snapshot = stable_snapshot(root, base_sha, scopes)
    if decision_reference_digests(raw_briefs, root) != (
        decision_ref_sha256,
        decision_ref_bytes,
    ):
        raise ArtifactError("decision references changed during artifact verification")
    expected_payload = payload_from_snapshot(
        root,
        base_sha,
        scopes,
        brief_ids,
        brief_sha256,
        decision_ref_sha256,
        decision_ref_bytes,
        result_ids,
        result_sha256,
        snapshot,
    )
    expected_manifest = manifest_from_payload(expected_payload)
    if expected_manifest != manifest or snapshot["patch"] != patch:
        raise ArtifactError("current worktree does not match the captured artifact")
    return manifest


def parse_result(path: Path) -> dict[str, Any]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise ArtifactError(f"unable to read review result: {error}") from error
    if not lines or lines[0] != RESULT_CONTRACT:
        raise ArtifactError(f"review result must start with {RESULT_CONTRACT!r}")
    values: dict[str, Any] = {}
    expected_order = [
        *IDENTITY_FIELDS,
        "status",
        *RESULT_SUMMARY_FIELDS,
        "findings",
    ]
    for expected_index, field in enumerate(expected_order, start=1):
        if expected_index >= len(lines):
            raise ArtifactError(f"review result is missing {field!r}")
        line = lines[expected_index]
        prefix = f"{field}:"
        if not line.startswith(prefix):
            raise ArtifactError(
                f"review result field {field!r} is missing or out of order"
            )
        value = line[len(prefix) :].strip()
        if field != "findings" and not value:
            raise ArtifactError(f"review result field {field!r} is empty")
        values[field] = value

    findings_start = len(expected_order) + 1
    finding_lines = lines[findings_start:]
    if not finding_lines:
        raise ArtifactError("review result must contain at least one finding")
    if len(finding_lines) % 3 != 0:
        raise ArtifactError("review result contains an incomplete finding")

    findings: list[dict[str, str]] = []
    for index in range(0, len(finding_lines), 3):
        block = finding_lines[index : index + 3]
        expected_prefixes = (
            "  - severity: ",
            "    location: ",
            "    issue: ",
        )
        finding: dict[str, str] = {}
        for line, prefix, field in zip(
            block,
            expected_prefixes,
            ("severity", "location", "issue"),
            strict=True,
        ):
            if not line.startswith(prefix):
                raise ArtifactError(
                    "review result finding fields are malformed or out of order"
                )
            value = line[len(prefix) :].strip()
            if not value:
                raise ArtifactError(
                    f"review result finding field {field!r} is empty"
                )
            finding[field] = value
        if finding["severity"] not in FINDING_SEVERITIES:
            raise ArtifactError(
                f"invalid finding severity: {finding['severity']!r}"
            )
        findings.append(finding)
    values["findings"] = findings
    return values


def validate_result(
    manifest_path: Path,
    result_path: Path,
    expected_axis: str,
    repository: Path | None = None,
) -> dict[str, Any]:
    if expected_axis not in AXIS_STATUSES:
        raise ArtifactError(f"unsupported review axis: {expected_axis!r}")
    if repository is not None:
        root = repository_root(repository)
        validate_artifact_file(manifest_path, root)
        if not result_path.is_absolute() or result_path.resolve() != result_path:
            raise ArtifactError("review result path must be canonical and absolute")
        if result_path.is_symlink() or not result_path.is_file():
            raise ArtifactError("review result must be a regular file")
        result_metadata = result_path.stat()
        if result_metadata.st_uid != os.getuid() or (
            stat.S_IMODE(result_metadata.st_mode) & 0o077
        ):
            raise ArtifactError("review result must be owner-only")
    manifest, _ = load_manifest(manifest_path)
    if manifest.get("artifact_contract") != ARTIFACT_CONTRACT:
        raise ArtifactError("manifest uses an unsupported artifact contract")
    result = parse_result(result_path)
    expected_values = {
        "axis": expected_axis,
        "artifact_contract": ARTIFACT_CONTRACT,
        "artifact_id": manifest["artifact_id"],
        "base_sha": manifest["base_sha"],
        "head_sha": manifest["head_sha"],
        "patch_sha256": manifest["patch_sha256"],
        "patch_bytes": str(manifest["patch_bytes"]),
        "request_ids": json.dumps(
            {
                "brief_ids": manifest["brief_ids"],
                "result_ids": manifest["result_ids"],
            },
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        ),
    }
    mismatches = [
        field
        for field, expected in expected_values.items()
        if result.get(field) != expected
    ]
    if mismatches:
        raise ArtifactError(
            "review result is not bound to this artifact: " + ", ".join(mismatches)
        )
    if result["status"] not in AXIS_STATUSES[expected_axis]:
        raise ArtifactError(
            f"invalid {expected_axis} review status: {result['status']!r}"
        )
    if not (
        result["scope"] == "ok"
        or result["scope"].startswith("deviation: ")
    ):
        raise ArtifactError("review result scope must be 'ok' or 'deviation: ...'")
    if not (
        result["acceptance"] == "met"
        or result["acceptance"].startswith(("missing: ", "unknown: "))
    ):
        raise ArtifactError(
            "review result acceptance must be met, missing: ..., or unknown: ..."
        )
    if not (
        result["governing_decisions"] in {"followed", "not-applicable"}
        or result["governing_decisions"].startswith(("deviation: ", "unknown: "))
    ):
        raise ArtifactError(
            "review result governing_decisions has an invalid summary"
        )
    if not (
        result["verification"] == "sufficient"
        or result["verification"].startswith("missing: ")
    ):
        raise ArtifactError(
            "review result verification must be sufficient or missing: ..."
        )
    if result["next_action"] not in NEXT_ACTIONS:
        raise ArtifactError(
            f"invalid review next_action: {result['next_action']!r}"
        )
    if result["status"] == "APPROVED":
        approval_errors: list[str] = []
        if result["scope"] != "ok":
            approval_errors.append("scope")
        if result["acceptance"] != "met":
            approval_errors.append("acceptance")
        if result["governing_decisions"] not in {
            "followed",
            "not-applicable",
        }:
            approval_errors.append("governing_decisions")
        if result["verification"] != "sufficient":
            approval_errors.append("verification")
        if result["next_action"] != "ready for caller":
            approval_errors.append("next_action")
        if any(
            finding["severity"] in {"blocker", "warning"}
            for finding in result["findings"]
        ):
            approval_errors.append("findings")
        if approval_errors:
            raise ArtifactError(
                "APPROVED review result has unresolved fields: "
                + ", ".join(approval_errors)
            )
    if result["status"] == "CONCERNS":
        concern_errors: list[str] = []
        if result["scope"] != "ok":
            concern_errors.append("scope")
        if result["acceptance"] != "met":
            concern_errors.append("acceptance")
        if result["governing_decisions"] not in {
            "followed",
            "not-applicable",
        }:
            concern_errors.append("governing_decisions")
        if result["verification"] != "sufficient":
            concern_errors.append("verification")
        if result["next_action"] != "ready for caller":
            concern_errors.append("next_action")
        if any(
            finding["severity"] == "blocker"
            for finding in result["findings"]
        ):
            concern_errors.append("findings")
        if not any(
            finding["severity"] == "warning"
            for finding in result["findings"]
        ):
            concern_errors.append("warning")
        if concern_errors:
            raise ArtifactError(
                "CONCERNS review result has blocking fields: "
                + ", ".join(concern_errors)
            )
    if result["status"] == "CHANGES_REQUIRED":
        change_errors: list[str] = []
        if result["next_action"] != "revise implementation contract":
            change_errors.append("next_action")
        if not any(
            finding["severity"] == "blocker"
            for finding in result["findings"]
        ):
            change_errors.append("blocker")
        if change_errors:
            raise ArtifactError(
                "CHANGES_REQUIRED review result lacks required change evidence: "
                + ", ".join(change_errors)
            )
    if result["status"] == "MISSING_EVIDENCE":
        evidence_errors: list[str] = []
        if not result["verification"].startswith("missing: "):
            evidence_errors.append("verification")
        if result["next_action"] != "needs evidence":
            evidence_errors.append("next_action")
        if not any(
            finding["severity"] == "blocker"
            for finding in result["findings"]
        ):
            evidence_errors.append("blocker")
        if evidence_errors:
            raise ArtifactError(
                "MISSING_EVIDENCE review result is inconsistent: "
                + ", ".join(evidence_errors)
            )
    if result["status"] == "NEEDS_CONTEXT":
        context_errors: list[str] = []
        if not (
            result["acceptance"].startswith("unknown: ")
            or result["governing_decisions"].startswith("unknown: ")
            or result["verification"].startswith("missing: ")
        ):
            context_errors.append("missing context")
        if result["next_action"] != "needs evidence":
            context_errors.append("next_action")
        if not any(
            finding["severity"] == "blocker"
            for finding in result["findings"]
        ):
            context_errors.append("blocker")
        if context_errors:
            raise ArtifactError(
                "NEEDS_CONTEXT review result is inconsistent: "
                + ", ".join(context_errors)
            )
    if result["status"] == "NEEDS_SCOPE":
        scope_errors: list[str] = []
        if not result["scope"].startswith("deviation: "):
            scope_errors.append("scope")
        if result["next_action"] != "revise implementation contract":
            scope_errors.append("next_action")
        if not any(
            finding["severity"] == "blocker"
            for finding in result["findings"]
        ):
            scope_errors.append("blocker")
        if scope_errors:
            raise ArtifactError(
                "NEEDS_SCOPE review result is inconsistent: "
                + ", ".join(scope_errors)
            )
    return result


def self_test() -> int:
    failures: list[str] = []
    try:
        ensure_patch_limit(PATCH_BYTE_LIMIT)
        try:
            ensure_patch_limit(PATCH_BYTE_LIMIT + 1)
            failures.append("120001-byte patch was accepted")
        except ArtifactError:
            pass
        ensure_manifest_limit(MANIFEST_BYTE_LIMIT)
        try:
            ensure_manifest_limit(MANIFEST_BYTE_LIMIT + 1)
            failures.append("oversized manifest was accepted")
        except ArtifactError:
            pass
        ensure_captured_path_count(CAPTURED_PATH_COUNT_LIMIT)
        try:
            ensure_captured_path_count(CAPTURED_PATH_COUNT_LIMIT + 1)
            failures.append("oversized captured-path inventory was accepted")
        except ArtifactError:
            pass
        for broad_scope in (".", "./"):
            try:
                canonical_scope([broad_scope])
                failures.append(
                    f"repository-root scope {broad_scope!r} was accepted"
                )
            except ArtifactError:
                pass
        ensure_review_context_budget(
            1,
            1,
            1,
            1,
            REVIEW_CONTEXT_BYTE_LIMIT - 4,
        )
        try:
            ensure_review_context_budget(
                1,
                1,
                1,
                1,
                REVIEW_CONTEXT_BYTE_LIMIT - 3,
            )
            failures.append("combined review context limit was not enforced")
        except ArtifactError:
            pass

        with tempfile.TemporaryDirectory(prefix="review-artifact-self-test-") as temp:
            temp = str(Path(temp).resolve())
            root = Path(temp) / "repo"
            root.mkdir()
            git(root, "init", "-q")
            git(root, "config", "user.name", "review artifact self-test")
            git(root, "config", "user.email", "review-artifact@example.invalid")
            (root / "tracked.txt").write_text("before\n", encoding="utf-8")
            (root / "renamed.txt").write_text("rename me\n", encoding="utf-8")
            (root / "docs").mkdir()
            (root / "docs" / "decision.md").write_text(
                "# Decision\n\nKeep the contract bounded.\n",
                encoding="utf-8",
            )
            (root / "docs" / "decisions.md").write_text(
                "# Decisions\n\n"
                "- B1: First active decision.\n"
                "  - Detail for B1.\n"
                "- B2: Second active decision.\n",
                encoding="utf-8",
            )
            git(root, "add", ".")
            git(root, "commit", "-q", "-m", "baseline")
            base_sha = full_commit(root, "HEAD")
            fsmonitor_marker = Path(temp) / "fsmonitor-executed"
            fsmonitor_hook = Path(temp) / "fsmonitor.sh"
            fsmonitor_hook.write_text(
                "#!/bin/sh\n"
                'touch "$REVIEW_ARTIFACT_FSMONITOR_MARKER"\n'
                "printf '0\\n'\n",
                encoding="utf-8",
            )
            fsmonitor_hook.chmod(0o755)
            git(root, "config", "core.fsmonitor", str(fsmonitor_hook))
            previous_marker = os.environ.get(
                "REVIEW_ARTIFACT_FSMONITOR_MARKER"
            )
            os.environ["REVIEW_ARTIFACT_FSMONITOR_MARKER"] = str(
                fsmonitor_marker
            )
            try:
                git(root, "status", "--porcelain=v2", "-z")
                if fsmonitor_marker.exists():
                    failures.append(
                        "repository-local core.fsmonitor executed through git()"
                    )
                fsmonitor_marker.unlink(missing_ok=True)
                git_limited_output(
                    root,
                    "status",
                    "--porcelain=v2",
                    "-z",
                    byte_limit=PATCH_BYTE_LIMIT,
                )
                if fsmonitor_marker.exists():
                    failures.append(
                        "repository-local core.fsmonitor executed through "
                        "git_limited_output()"
                    )
            finally:
                if previous_marker is None:
                    os.environ.pop(
                        "REVIEW_ARTIFACT_FSMONITOR_MARKER",
                        None,
                    )
                else:
                    os.environ[
                        "REVIEW_ARTIFACT_FSMONITOR_MARKER"
                    ] = previous_marker
            try:
                prepare_output_directory(Path("relative-review-output"), root)
                failures.append("relative review output directory was accepted")
            except ArtifactError:
                pass
            brief_path = Path(temp) / "brief.txt"
            brief_path.write_text(
                "IMPLEMENTATION_BRIEF v1\n"
                "id: brief-1\n"
                f"base_sha: {base_sha}\n"
                "scope:\n"
                "  - tracked.txt — modify the tracked fixture\n"
                "  - create: staged.txt — add the staged fixture\n"
                "  - create: binary.bin — add the binary fixture\n"
                "  - create: link — add the symlink fixture\n"
                "  - create: odd — contain unusual path fixtures\n"
                "  - create: large-review-input.txt — test the patch limit\n"
                "  - create: outside-large.bin — test the input limit\n"
                "  - create: module — test submodule visibility\n"
                "locked_decisions:\n"
                "  - ref: docs/decision.md\n",
                encoding="utf-8",
            )
            brief_path.chmod(0o400)
            result_contract_path = Path(temp) / "kokk-result.txt"
            result_contract_path.write_text(
                "KOKK_RESULT\n"
                "status: DONE\n"
                "brief_id: brief-1\n"
                "result_id: brief-1.attempt-1\n"
                "summary: completed self-test change\n"
                "changed_files: tracked.txt\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns_or_blockers: none\n"
                "needed: none\n",
                encoding="utf-8",
            )
            result_contract_path.chmod(0o400)
            brief_binding = f"brief-1={brief_path.resolve()}"
            result_binding = (
                f"brief-1.attempt-1={result_contract_path.resolve()}"
            )

            git(root, "commit", "--allow-empty", "-q", "-m", "later baseline")
            later_base_sha = full_commit(root, "HEAD")

            def read_only_contract(name: str, contents: str) -> Path:
                path = Path(temp) / name
                path.write_text(contents, encoding="utf-8")
                path.chmod(0o400)
                return path

            def assert_invalid_relationship(
                label: str,
                briefs: list[str],
                results: list[str],
                artifact_base: str = base_sha,
                artifact_head: str = later_base_sha,
            ) -> None:
                try:
                    validate_contract_relationships(
                        briefs,
                        results,
                        root,
                        artifact_base,
                        artifact_head,
                    )
                    failures.append(f"{label} was accepted")
                except ArtifactError:
                    pass

            assert_invalid_relationship(
                "brief binding/internal ID mismatch",
                [f"other={brief_path.resolve()}"],
                [result_binding],
            )
            assert_invalid_relationship(
                "result binding/internal ID mismatch",
                [brief_binding],
                [f"other={result_contract_path.resolve()}"],
            )
            assert_invalid_relationship(
                "missing implementation result",
                [brief_binding],
                [],
            )
            assert_invalid_relationship(
                "later artifact baseline",
                [brief_binding],
                [result_binding],
                later_base_sha,
            )
            for status in ("NEEDS_CONTEXT", "NEEDS_DECISION", "BLOCKED"):
                incomplete_result = read_only_contract(
                    f"incomplete-{status}.txt",
                    "KOKK_RESULT\n"
                    f"status: {status}\n"
                    "brief_id: brief-1\n"
                    "result_id: brief-1.attempt-2\n",
                )
                assert_invalid_relationship(
                    f"incomplete Kokk status {status}",
                    [brief_binding],
                    [f"brief-1.attempt-2={incomplete_result.resolve()}"],
                )
            duplicate_result = read_only_contract(
                "duplicate-result.txt",
                "KOKK_RESULT\n"
                "status: DONE\n"
                "brief_id: brief-1\n"
                "result_id: brief-1.attempt-2\n"
                "summary: completed duplicate result\n"
                "changed_files: tracked.txt\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns_or_blockers: none\n"
                "needed: none\n",
            )
            assert_invalid_relationship(
                "duplicate result for one brief",
                [brief_binding],
                [
                    result_binding,
                    f"brief-1.attempt-2={duplicate_result.resolve()}",
                ],
            )

            second_brief = read_only_contract(
                "brief-2.txt",
                "IMPLEMENTATION_BRIEF v1\n"
                "id: brief-2\n"
                f"base_sha: {later_base_sha}\n"
                "scope:\n"
                "  - tracked.txt — modify the tracked fixture\n",
            )
            second_result = read_only_contract(
                "brief-2-result.txt",
                "KOKK_RESULT\n"
                "status: DONE_WITH_CONCERNS\n"
                "brief_id: brief-2\n"
                "result_id: brief-2.attempt-1\n"
                "summary: completed later slice\n"
                "changed_files: tracked.txt\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns_or_blockers: non-blocking self-test concern\n"
                "needed: none\n",
            )
            try:
                validate_contract_relationships(
                    [brief_binding, f"brief-2={second_brief.resolve()}"],
                    [
                        result_binding,
                        f"brief-2.attempt-1={second_result.resolve()}",
                    ],
                    root,
                    base_sha,
                    later_base_sha,
                )
            except ArtifactError as error:
                failures.append(
                    f"integrated ancestor-ordered contracts were rejected: {error}"
                )

            base_tree = git(
                root,
                "rev-parse",
                f"{base_sha}^{{tree}}",
            ).decode("ascii").strip()
            divergent_base_sha = git(
                root,
                "commit-tree",
                base_tree,
                "-p",
                base_sha,
                input_bytes=b"divergent baseline\n",
            ).decode("ascii").strip()
            divergent_brief = read_only_contract(
                "divergent-brief.txt",
                "IMPLEMENTATION_BRIEF v1\n"
                "id: divergent\n"
                f"base_sha: {divergent_base_sha}\n"
                "scope:\n"
                "  - tracked.txt — modify the tracked fixture\n",
            )
            divergent_result = read_only_contract(
                "divergent-result.txt",
                "KOKK_RESULT\n"
                "status: DONE\n"
                "brief_id: divergent\n"
                "result_id: divergent.attempt-1\n"
                "summary: completed divergent slice\n"
                "changed_files: tracked.txt\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns_or_blockers: none\n"
                "needed: none\n",
            )
            assert_invalid_relationship(
                "divergent later brief baseline",
                [brief_binding, f"divergent={divergent_brief.resolve()}"],
                [
                    result_binding,
                    f"divergent.attempt-1={divergent_result.resolve()}",
                ],
            )

            merge_left_base_sha = git(
                root,
                "commit-tree",
                base_tree,
                "-p",
                base_sha,
                input_bytes=b"merge left baseline\n",
            ).decode("ascii").strip()
            merge_right_base_sha = git(
                root,
                "commit-tree",
                base_tree,
                "-p",
                base_sha,
                input_bytes=b"merge right baseline\n",
            ).decode("ascii").strip()
            merge_head_sha = git(
                root,
                "commit-tree",
                base_tree,
                "-p",
                merge_left_base_sha,
                "-p",
                merge_right_base_sha,
                input_bytes=b"merged divergent baselines\n",
            ).decode("ascii").strip()
            merge_left_brief = read_only_contract(
                "merge-left-brief.txt",
                "IMPLEMENTATION_BRIEF v1\n"
                "id: merge-left\n"
                f"base_sha: {merge_left_base_sha}\n"
                "scope:\n"
                "  - tracked.txt — modify the tracked fixture\n",
            )
            merge_right_brief = read_only_contract(
                "merge-right-brief.txt",
                "IMPLEMENTATION_BRIEF v1\n"
                "id: merge-right\n"
                f"base_sha: {merge_right_base_sha}\n"
                "scope:\n"
                "  - tracked.txt — modify the tracked fixture\n",
            )
            merge_left_result = read_only_contract(
                "merge-left-result.txt",
                "KOKK_RESULT\n"
                "status: DONE\n"
                "brief_id: merge-left\n"
                "result_id: merge-left.attempt-1\n"
                "summary: completed left branch slice\n"
                "changed_files: tracked.txt\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns_or_blockers: none\n"
                "needed: none\n",
            )
            merge_right_result = read_only_contract(
                "merge-right-result.txt",
                "KOKK_RESULT\n"
                "status: DONE\n"
                "brief_id: merge-right\n"
                "result_id: merge-right.attempt-1\n"
                "summary: completed right branch slice\n"
                "changed_files: tracked.txt\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns_or_blockers: none\n"
                "needed: none\n",
            )
            assert_invalid_relationship(
                "merge-DAG divergent brief baselines",
                [
                    brief_binding,
                    f"merge-left={merge_left_brief.resolve()}",
                    f"merge-right={merge_right_brief.resolve()}",
                ],
                [
                    result_binding,
                    f"merge-left.attempt-1={merge_left_result.resolve()}",
                    f"merge-right.attempt-1={merge_right_result.resolve()}",
                ],
                artifact_head=merge_head_sha,
            )

            review_request = read_only_contract(
                "review-request.txt",
                "REVIEW_REQUEST v1\n"
                "id: review-1\n"
                f"base_sha: {base_sha}\n"
                "goal: review the self-test change\n"
                "specification: ref: docs/decision.md\n"
                "scope:\n"
                "  - tracked.txt — review the tracked fixture\n"
                "acceptance:\n"
                "  - the change remains deterministic\n"
                "verification:\n"
                "  - true — exits zero\n"
                "risk: R2 — bounded self-test review\n",
            )
            review_evidence = read_only_contract(
                "review-evidence.txt",
                "REVIEW_EVIDENCE v1\n"
                "request_id: review-1\n"
                "result_id: review-1.evidence-1\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns: none\n",
            )
            for label, malformed_request_text in (
                (
                    "empty Barista goal",
                    review_request.read_text(encoding="utf-8").replace(
                        "goal: review the self-test change",
                        "goal:",
                    ),
                ),
                (
                    "Barista verification without command/evidence boundary",
                    review_request.read_text(encoding="utf-8").replace(
                        "  - true — exits zero",
                        "  - all tests pass",
                    ),
                ),
                (
                    "repository-root Barista scope",
                    review_request.read_text(encoding="utf-8").replace(
                        "  - tracked.txt — review the tracked fixture",
                        "  - . — review everything",
                    ),
                ),
            ):
                try:
                    validate_review_request(
                        malformed_request_text.splitlines(),
                        label,
                    )
                    failures.append(f"{label} was accepted")
                except ArtifactError:
                    pass
            try:
                validate_contract_relationships(
                    [f"review-1={review_request.resolve()}"],
                    [
                        "review-1.evidence-1="
                        f"{review_evidence.resolve()}"
                    ],
                    root,
                    base_sha,
                    later_base_sha,
                )
            except ArtifactError as error:
                failures.append(
                    f"canonical Barista evidence relationship was rejected: {error}"
                )
            malformed_evidence = read_only_contract(
                "malformed-review-evidence.txt",
                "REVIEW_EVIDENCE v1\n"
                "request_id: review-1\n"
                "result_id: review-1.evidence-2\n"
                "concerns: none\n",
            )
            assert_invalid_relationship(
                "Barista evidence without verification",
                [f"review-1={review_request.resolve()}"],
                [
                    "review-1.evidence-2="
                    f"{malformed_evidence.resolve()}"
                ],
            )
            malformed_kokk_result = read_only_contract(
                "malformed-kokk-result.txt",
                "KOKK_RESULT\n"
                "status: DONE\n"
                "brief_id: brief-1\n"
                "result_id: brief-1.attempt-3\n"
                "summary: missing evidence\n"
                "changed_files: tracked.txt\n"
                "concerns_or_blockers: none\n"
                "needed: none\n",
            )
            assert_invalid_relationship(
                "completed Kokk result without verification",
                [brief_binding],
                [
                    "brief-1.attempt-3="
                    f"{malformed_kokk_result.resolve()}"
                ],
            )
            try:
                review_digests, review_reference_bytes = decision_reference_digests(
                    [f"review-1={review_request.resolve()}"],
                    root,
                )
                if set(review_digests) != {"docs/decision.md"} or (
                    review_reference_bytes <= 0
                ):
                    failures.append(
                        "Barista specification reference was not bound"
                    )
            except ArtifactError as error:
                failures.append(
                    f"Barista specification reference was rejected: {error}"
                )

            selector_brief = read_only_contract(
                "selector-brief.txt",
                "IMPLEMENTATION_BRIEF v1\n"
                "id: selector\n"
                f"base_sha: {base_sha}\n"
                "scope:\n"
                "  - tracked.txt — modify the tracked fixture\n"
                "locked_decisions:\n"
                "  - ref: docs/decisions.md#B1\n"
                "  - ref: docs/decisions.md#B2\n",
            )
            selector_result = read_only_contract(
                "selector-result.txt",
                "KOKK_RESULT\n"
                "status: DONE\n"
                "brief_id: selector\n"
                "result_id: selector.attempt-1\n"
                "summary: completed selector slice\n"
                "changed_files: tracked.txt\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns_or_blockers: none\n"
                "needed: none\n",
            )
            try:
                selector_digests, selector_bytes = decision_reference_digests(
                    [f"selector={selector_brief.resolve()}"],
                    root,
                )
                if (
                    set(selector_digests)
                    != {"docs/decisions.md#B1", "docs/decisions.md#B2"}
                    or selector_bytes <= 0
                    or selector_bytes
                    >= (root / "docs" / "decisions.md").stat().st_size
                ):
                    failures.append("decision selector did not bind one entry")
            except ArtifactError as error:
                failures.append(f"canonical decision selector was rejected: {error}")
            decisions_path = root / "docs" / "decisions.md"
            original_decisions = decisions_path.read_text(encoding="utf-8")
            decisions_path.write_text(
                original_decisions + "- B2: Duplicate active decision.\n",
                encoding="utf-8",
            )
            try:
                decision_reference_digests(
                    [f"selector={selector_brief.resolve()}"],
                    root,
                )
                failures.append("duplicate decision selector was accepted")
            except ArtifactError:
                pass
            decisions_path.write_text(original_decisions, encoding="utf-8")
            missing_selector_brief = read_only_contract(
                "missing-selector-brief.txt",
                "IMPLEMENTATION_BRIEF v1\n"
                "id: missing-selector\n"
                f"base_sha: {base_sha}\n"
                "locked_decisions:\n"
                "  - ref: docs/decisions.md#B99\n",
            )
            try:
                decision_reference_digests(
                    [f"missing-selector={missing_selector_brief.resolve()}"],
                    root,
                )
                failures.append("missing decision selector was accepted")
            except ArtifactError:
                pass

            if not BRIEF_ID_RE.fullmatch("b" * 96):
                failures.append("96-character brief ID was rejected")
            if BRIEF_ID_RE.fullmatch("b" * 97):
                failures.append("97-character brief ID was accepted")
            if not REQUEST_ID_RE.fullmatch(
                "b" * 96 + ".attempt-999999999"
            ):
                failures.append("bounded result suffix was not representable")

            ensure_contract_set_budget(
                [
                    f"brief-{index}={brief_path.resolve()}"
                    for index in range(CONTRACT_COUNT_LIMIT)
                ],
                [],
            )
            try:
                ensure_contract_set_budget(
                    [
                        f"brief-{index}={brief_path.resolve()}"
                        for index in range(CONTRACT_COUNT_LIMIT + 1)
                    ],
                    [],
                )
                failures.append("aggregate contract count limit was not enforced")
            except ArtifactError:
                pass
            aggregate_contract_bindings: list[str] = []
            for index in range(3):
                aggregate_path = Path(temp) / f"aggregate-{index}.txt"
                aggregate_path.write_text("x" * 50_000, encoding="utf-8")
                aggregate_path.chmod(0o400)
                aggregate_contract_bindings.append(
                    f"aggregate-{index}={aggregate_path.resolve()}"
                )
            try:
                ensure_contract_set_budget(aggregate_contract_bindings, [])
                failures.append("aggregate contract byte limit was not enforced")
            except ArtifactError:
                pass

            try:
                capture(
                    root,
                    base_sha,
                    Path(temp) / "missing-contract",
                    [],
                    [],
                    [],
                )
                failures.append("capture without a brief contract was accepted")
            except ArtifactError:
                pass
            try:
                capture(
                    root,
                    base_sha,
                    Path(temp) / "invalid-contract-id",
                    [],
                    [f"invalid id={brief_path.resolve()}"],
                    [],
                )
                failures.append("capture with an invalid contract ID was accepted")
            except ArtifactError:
                pass

            large_path = root / "large-review-input.txt"
            large_path.write_text(
                "".join(
                    f"{index:08d} unique review payload line {index:08d}\n"
                    for index in range(20_000)
                ),
                encoding="utf-8",
            )
            try:
                capture(
                    root,
                    base_sha,
                    Path(temp) / "oversized-patch",
                    [],
                    [brief_binding],
                    [result_binding],
                )
                failures.append("oversized generated patch was accepted")
            except ArtifactError:
                pass
            large_path.unlink()

            git(root, "config", "diff.external", "exit 99")
            git(root, "config", "diff.renames", "true")
            git(root, "config", "core.quotePath", "false")
            git(root, "config", "color.ui", "always")
            (root / "tracked.txt").write_text("after\n", encoding="utf-8")
            (root / "staged.txt").write_text("staged\n", encoding="utf-8")
            git(root, "add", "staged.txt")
            (root / "staged.txt").write_text(
                "staged worktree\n",
                encoding="utf-8",
            )
            (root / "binary.bin").write_bytes(b"\x00\xff\x01")
            (root / "odd").mkdir()
            (root / "odd" / "på\nvei.txt").write_text(
                "unusual\n",
                encoding="utf-8",
            )
            if hasattr(os, "symlink"):
                os.symlink("tracked.txt", root / "link")

            outside_large = root / "outside-large.bin"
            outside_large.write_bytes(b"x" * (WORKTREE_INPUT_BYTE_LIMIT + 1))
            narrow_brief = read_only_contract(
                "narrow-brief.txt",
                "IMPLEMENTATION_BRIEF v1\n"
                "id: narrow\n"
                f"base_sha: {base_sha}\n"
                "scope:\n"
                "  - tracked.txt — modify only the tracked fixture\n",
            )
            narrow_result = read_only_contract(
                "narrow-result.txt",
                "KOKK_RESULT\n"
                "status: DONE\n"
                "brief_id: narrow\n"
                "result_id: narrow.attempt-1\n"
                "summary: modified the tracked fixture\n"
                "changed_files: tracked.txt\n"
                "verification:\n"
                "  - command: true\n"
                "    result: passed\n"
                "    exit_code: 0\n"
                "concerns_or_blockers: none\n"
                "needed: none\n",
            )
            try:
                capture(
                    root,
                    base_sha,
                    Path(temp) / "partial-scope",
                    [],
                    [f"narrow={narrow_brief.resolve()}"],
                    [f"narrow.attempt-1={narrow_result.resolve()}"],
                )
                failures.append(
                    "contract scope with unrelated dirty paths was accepted"
                )
            except ArtifactError:
                pass
            outside_large.unlink()
            outside_large.write_bytes(b"x" * (WORKTREE_INPUT_BYTE_LIMIT + 1))
            try:
                capture(
                    root,
                    base_sha,
                    Path(temp) / "oversized-worktree-input",
                    [],
                    [brief_binding],
                    [result_binding],
                )
                failures.append("oversized worktree input was accepted")
            except ArtifactError:
                pass
            outside_large.unlink()

            first_dir = Path(temp) / "first"
            second_dir = Path(temp) / "second"
            inherited_git_dir = os.environ.get("GIT_DIR")
            os.environ["GIT_DIR"] = str(Path(temp) / "wrong-git-dir")
            try:
                first_manifest_path, first_patch_path, first_manifest = capture(
                    root,
                    base_sha,
                    first_dir,
                    [],
                    [brief_binding],
                    [result_binding],
                )
                second_manifest_path, second_patch_path, second_manifest = capture(
                    root,
                    base_sha,
                    second_dir,
                    [],
                    [brief_binding],
                    [result_binding],
                )
            finally:
                if inherited_git_dir is None:
                    os.environ.pop("GIT_DIR", None)
                else:
                    os.environ["GIT_DIR"] = inherited_git_dir
            if first_patch_path.read_bytes() != second_patch_path.read_bytes():
                failures.append("two captures produced different patches")
            if first_manifest != second_manifest:
                failures.append("two captures produced different manifests")
            verify(
                first_manifest_path,
                first_patch_path,
                root,
                [brief_binding],
                [result_binding],
            )

            staged_worktree = (root / "staged.txt").read_text(encoding="utf-8")
            (root / "staged.txt").write_text(
                "different staged bytes\n",
                encoding="utf-8",
            )
            git(root, "add", "staged.txt")
            (root / "staged.txt").write_text(
                staged_worktree,
                encoding="utf-8",
            )
            try:
                verify(
                    first_manifest_path,
                    first_patch_path,
                    root,
                    [brief_binding],
                    [result_binding],
                )
                failures.append("same-status index mutation was accepted")
            except ArtifactError:
                pass
            (root / "staged.txt").write_text("staged\n", encoding="utf-8")
            git(root, "add", "staged.txt")
            (root / "staged.txt").write_text(
                staged_worktree,
                encoding="utf-8",
            )
            verify(
                first_manifest_path,
                first_patch_path,
                root,
                [brief_binding],
                [result_binding],
            )

            original_result_contract = result_contract_path.read_text(
                encoding="utf-8"
            )
            result_contract_path.chmod(0o600)
            result_contract_path.write_text(
                original_result_contract + "summary: altered\n",
                encoding="utf-8",
            )
            result_contract_path.chmod(0o400)
            try:
                verify(
                    first_manifest_path,
                    first_patch_path,
                    root,
                    [brief_binding],
                    [result_binding],
                )
                failures.append("changed contract bytes were accepted")
            except ArtifactError:
                pass
            result_contract_path.chmod(0o600)
            result_contract_path.write_text(
                original_result_contract,
                encoding="utf-8",
            )
            result_contract_path.chmod(0o400)

            def result_text(
                *,
                axis: str = "implementation",
                status: str = "APPROVED",
                request_ids: str = "brief-1",
                scope: str = "ok",
                acceptance: str = "met",
                governing_decisions: str = "followed",
                verification: str = "sufficient",
                next_action: str = "ready for caller",
                finding_lines: list[str] | None = None,
            ) -> str:
                return (
                    "\n".join(
                        [
                            RESULT_CONTRACT,
                            f"axis: {axis}",
                            f"artifact_contract: {ARTIFACT_CONTRACT}",
                            f"artifact_id: {first_manifest['artifact_id']}",
                            f"base_sha: {first_manifest['base_sha']}",
                            f"head_sha: {first_manifest['head_sha']}",
                            f"patch_sha256: {first_manifest['patch_sha256']}",
                            f"patch_bytes: {first_manifest['patch_bytes']}",
                            f"status: {status}",
                            f"request_ids: {request_ids}",
                            f"scope: {scope}",
                            f"acceptance: {acceptance}",
                            f"governing_decisions: {governing_decisions}",
                            f"verification: {verification}",
                            f"next_action: {next_action}",
                            "findings:",
                            *(
                                finding_lines
                                or [
                                    "  - severity: note",
                                    "    location: n/a",
                                    "    issue: none",
                                ]
                            ),
                        ]
                    )
                    + "\n"
                )

            canonical_request_ids = json.dumps(
                {
                    "brief_ids": first_manifest["brief_ids"],
                    "result_ids": first_manifest["result_ids"],
                },
                ensure_ascii=True,
                sort_keys=True,
                separators=(",", ":"),
            )

            invalid_result_counter = 0

            def assert_invalid_result(label: str, contents: str) -> None:
                nonlocal invalid_result_counter
                invalid_result_counter += 1
                candidate = (
                    Path(temp)
                    / f"invalid-result-{invalid_result_counter}.txt"
                )
                candidate.write_text(contents, encoding="utf-8")
                candidate.chmod(0o600)
                try:
                    validate_result(
                        first_manifest_path,
                        candidate,
                        "implementation",
                        root,
                    )
                    failures.append(f"{label} was accepted")
                except ArtifactError:
                    pass

            valid_result_counter = 0

            def assert_valid_result(label: str, contents: str) -> None:
                nonlocal valid_result_counter
                valid_result_counter += 1
                candidate = (
                    Path(temp)
                    / f"valid-result-{valid_result_counter}.txt"
                )
                candidate.write_text(contents, encoding="utf-8")
                candidate.chmod(0o600)
                try:
                    validate_result(
                        first_manifest_path,
                        candidate,
                        "implementation",
                        root,
                    )
                except ArtifactError as error:
                    failures.append(f"{label} was rejected: {error}")

            valid_result = Path(temp) / "valid-result.txt"
            valid_result.write_text(
                result_text(request_ids=canonical_request_ids),
                encoding="utf-8",
            )
            valid_result.chmod(0o600)
            validate_result(
                first_manifest_path,
                valid_result,
                "implementation",
                root,
            )

            try:
                validate_result(
                    first_manifest_path,
                    valid_result,
                    "standards",
                    root,
                )
                failures.append("unsupported result axis was accepted")
            except ArtifactError:
                pass
            assert_invalid_result(
                "mismatched result axis",
                result_text(axis="spec"),
            )

            assert_invalid_result(
                "wrong request IDs",
                result_text(
                    request_ids=(
                        '{"brief_ids":["unrelated"],'
                        '"result_ids":["kokk-1"]}'
                    )
                ),
            )
            assert_invalid_result(
                "partial request IDs",
                result_text(
                    request_ids='{"brief_ids":["brief-1"],"result_ids":[]}'
                ),
            )

            complete_lines = result_text(
                request_ids=canonical_request_ids
            ).splitlines()
            findings_index = complete_lines.index("findings:")
            assert_invalid_result(
                "truncated result",
                "\n".join(complete_lines[: findings_index + 1]) + "\n",
            )
            for required_field in RESULT_SUMMARY_FIELDS:
                prefix = f"{required_field}:"
                without_field = [
                    line
                    for line in complete_lines
                    if not line.startswith(prefix)
                ]
                assert_invalid_result(
                    f"result missing {required_field}",
                    "\n".join(without_field) + "\n",
                )
            assert_invalid_result(
                "APPROVED result with missing acceptance",
                result_text(
                    request_ids=canonical_request_ids,
                    acceptance="missing: criterion",
                ),
            )
            assert_invalid_result(
                "APPROVED result with missing verification",
                result_text(
                    request_ids=canonical_request_ids,
                    verification="missing: test output",
                ),
            )
            assert_invalid_result(
                "APPROVED result with warning",
                result_text(
                    request_ids=canonical_request_ids,
                    finding_lines=[
                        "  - severity: warning",
                        "    location: tracked.txt:1",
                        "    issue: unresolved behavior",
                    ]
                ),
            )
            assert_invalid_result(
                "malformed finding block",
                result_text(
                    request_ids=canonical_request_ids,
                    finding_lines=[
                        "  - severity: note",
                        "    issue: fields are out of order",
                        "    location: n/a",
                    ]
                ),
            )
            assert_invalid_result(
                "trailing top-level result field",
                result_text(request_ids=canonical_request_ids)
                + "unexpected: value\n",
            )
            assert_invalid_result(
                "CONCERNS result with missing acceptance",
                result_text(
                    request_ids=canonical_request_ids,
                    status="CONCERNS",
                    acceptance="missing: criterion",
                    next_action="revise implementation contract",
                    finding_lines=[
                        "  - severity: warning",
                        "    location: tracked.txt:1",
                        "    issue: criterion is unmet",
                    ],
                ),
            )
            blocker_finding = [
                "  - severity: blocker",
                "    location: tracked.txt:1",
                "    issue: action is required",
            ]
            warning_finding = [
                "  - severity: warning",
                "    location: tracked.txt:1",
                "    issue: non-blocking concern",
            ]
            for status in (
                "CHANGES_REQUIRED",
                "MISSING_EVIDENCE",
                "NEEDS_CONTEXT",
                "NEEDS_SCOPE",
            ):
                assert_invalid_result(
                    f"inconsistent {status} result",
                    result_text(
                        request_ids=canonical_request_ids,
                        status=status,
                    ),
                )
            assert_invalid_result(
                "CONCERNS result without a warning",
                result_text(
                    request_ids=canonical_request_ids,
                    status="CONCERNS",
                ),
            )
            assert_valid_result(
                "consistent CONCERNS result",
                result_text(
                    request_ids=canonical_request_ids,
                    status="CONCERNS",
                    finding_lines=warning_finding,
                ),
            )
            assert_valid_result(
                "consistent CHANGES_REQUIRED result",
                result_text(
                    request_ids=canonical_request_ids,
                    status="CHANGES_REQUIRED",
                    acceptance="missing: criterion",
                    next_action="revise implementation contract",
                    finding_lines=blocker_finding,
                ),
            )
            assert_valid_result(
                "consistent MISSING_EVIDENCE result",
                result_text(
                    request_ids=canonical_request_ids,
                    status="MISSING_EVIDENCE",
                    verification="missing: command output",
                    next_action="needs evidence",
                    finding_lines=blocker_finding,
                ),
            )
            assert_valid_result(
                "consistent NEEDS_CONTEXT result",
                result_text(
                    request_ids=canonical_request_ids,
                    status="NEEDS_CONTEXT",
                    acceptance="unknown: contract detail",
                    next_action="needs evidence",
                    finding_lines=blocker_finding,
                ),
            )
            assert_valid_result(
                "consistent NEEDS_SCOPE result",
                result_text(
                    request_ids=canonical_request_ids,
                    status="NEEDS_SCOPE",
                    scope="deviation: change exceeds bounded review",
                    next_action="revise implementation contract",
                    finding_lines=blocker_finding,
                ),
            )

            obsolete_payload = dict(first_manifest)
            del obsolete_payload["artifact_id"]
            obsolete_payload["artifact_contract"] = "obsolete-contract"
            obsolete_manifest = manifest_from_payload(obsolete_payload)
            obsolete_dir = Path(temp) / "obsolete"
            prepare_output_directory(obsolete_dir, root)
            obsolete_manifest_path = obsolete_dir / MANIFEST_NAME
            write_read_only(
                obsolete_manifest_path, canonical_json(obsolete_manifest)
            )
            obsolete_result = Path(temp) / "obsolete-result.txt"
            obsolete_result.write_text(
                valid_result.read_text(encoding="utf-8").replace(
                    first_manifest["artifact_id"],
                    obsolete_manifest["artifact_id"],
                ),
                encoding="utf-8",
            )
            obsolete_result.chmod(0o600)
            try:
                validate_result(
                    obsolete_manifest_path,
                    obsolete_result,
                    "implementation",
                    root,
                )
                failures.append("unsupported manifest contract was accepted")
            except ArtifactError:
                pass

            forged_payload = dict(first_manifest)
            del forged_payload["artifact_id"]
            forged_payload["patch_bytes"] -= 1
            forged_manifest = manifest_from_payload(forged_payload)
            forged_dir = Path(temp) / "forged"
            prepare_output_directory(forged_dir, root)
            forged_manifest_path = forged_dir / MANIFEST_NAME
            write_read_only(forged_manifest_path, canonical_json(forged_manifest))
            try:
                verify(
                    forged_manifest_path,
                    first_patch_path,
                    root,
                    [brief_binding],
                    [result_binding],
                )
                failures.append("forged manifest byte count was accepted")
            except ArtifactError:
                pass

            original_patch = first_patch_path.read_bytes()
            first_patch_path.chmod(0o600)
            first_patch_path.write_bytes(original_patch + b"tamper")
            first_patch_path.chmod(0o400)
            try:
                verify(
                    first_manifest_path,
                    first_patch_path,
                    root,
                    [brief_binding],
                    [result_binding],
                )
                failures.append("tampered patch was accepted")
            except ArtifactError:
                pass
            first_patch_path.chmod(0o600)
            first_patch_path.write_bytes(original_patch)
            first_patch_path.chmod(0o400)

            (root / "tracked.txt").write_text("changed again\n", encoding="utf-8")
            try:
                verify(
                    first_manifest_path,
                    first_patch_path,
                    root,
                    [brief_binding],
                    [result_binding],
                )
                failures.append("stale worktree was accepted")
            except ArtifactError:
                pass

            (root / "tracked.txt").write_text("before\n", encoding="utf-8")
            git(root, "update-index", "--assume-unchanged", "tracked.txt")
            (root / "tracked.txt").write_text(
                "hidden assume-unchanged edit\n", encoding="utf-8"
            )
            try:
                capture(
                    root,
                    base_sha,
                    Path(temp) / "assume-unchanged",
                    [],
                    [brief_binding],
                    [result_binding],
                )
                failures.append("assume-unchanged index flag was accepted")
            except ArtifactError:
                pass
            git(root, "update-index", "--no-assume-unchanged", "tracked.txt")

            conflict_root = Path(temp) / "conflict-repository"
            conflict_root.mkdir()
            git(conflict_root, "init", "-q")
            git(conflict_root, "config", "user.name", "review artifact self-test")
            git(
                conflict_root,
                "config",
                "user.email",
                "review-artifact@example.invalid",
            )
            (conflict_root / "conflict.txt").write_text(
                "base\n", encoding="utf-8"
            )
            git(conflict_root, "add", ".")
            git(conflict_root, "commit", "-q", "-m", "conflict baseline")
            conflict_base = full_commit(conflict_root, "HEAD")
            original_branch = git(
                conflict_root, "branch", "--show-current"
            ).decode("utf-8").strip()
            git(conflict_root, "switch", "-q", "-c", "other")
            (conflict_root / "conflict.txt").write_text(
                "other\n", encoding="utf-8"
            )
            git(conflict_root, "commit", "-qam", "other")
            git(conflict_root, "switch", "-q", original_branch)
            (conflict_root / "conflict.txt").write_text(
                "current\n", encoding="utf-8"
            )
            git(conflict_root, "commit", "-qam", "current")
            git(
                conflict_root,
                "merge",
                "--no-edit",
                "other",
                allowed_returncodes=(1,),
            )
            try:
                capture(
                    conflict_root,
                    conflict_base,
                    Path(temp) / "unresolved-conflict",
                    [],
                    [brief_binding],
                    [result_binding],
                )
                failures.append("unresolved index conflict was accepted")
            except ArtifactError:
                pass

            (root / "tracked.txt").write_text("before\n", encoding="utf-8")
            git(root, "update-index", "--skip-worktree", "tracked.txt")
            (root / "tracked.txt").write_text(
                "hidden skip-worktree edit\n", encoding="utf-8"
            )
            try:
                capture(
                    root,
                    base_sha,
                    Path(temp) / "skip-worktree",
                    [],
                    [brief_binding],
                    [result_binding],
                )
                failures.append("skip-worktree index flag was accepted")
            except ArtifactError:
                pass
            finally:
                git(root, "update-index", "--no-skip-worktree", "tracked.txt")

            mode_root = Path(temp) / "mode-repo"
            mode_root.mkdir()
            git(mode_root, "init", "-q")
            git(mode_root, "config", "user.name", "review artifact self-test")
            git(
                mode_root,
                "config",
                "user.email",
                "review-artifact@example.invalid",
            )
            mode_file = mode_root / "mode.sh"
            mode_file.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
            mode_file.chmod(0o644)
            git(mode_root, "add", "mode.sh")
            git(mode_root, "commit", "-q", "-m", "mode baseline")
            mode_base = full_commit(mode_root, "HEAD")
            git(mode_root, "config", "core.fileMode", "false")
            mode_file.chmod(0o755)
            _all_mode_paths, tracked_mode_paths, _untracked_mode_paths = (
                changed_paths(mode_root, mode_base, ["mode.sh"])
            )
            if b"mode.sh" not in tracked_mode_paths:
                failures.append(
                    "chmod-only change was hidden by core.fileMode=false"
                )

            submodule_source = Path(temp) / "submodule-source"
            submodule_source.mkdir()
            git(submodule_source, "init", "-q")
            git(
                submodule_source,
                "config",
                "user.name",
                "review artifact self-test",
            )
            git(
                submodule_source,
                "config",
                "user.email",
                "review-artifact@example.invalid",
            )
            (submodule_source / "nested.txt").write_text(
                "clean\n", encoding="utf-8"
            )
            git(submodule_source, "add", ".")
            git(submodule_source, "commit", "-q", "-m", "nested baseline")
            git(
                root,
                "-c",
                "protocol.file.allow=always",
                "submodule",
                "add",
                "-q",
                str(submodule_source),
                "module",
            )
            (root / "module" / "nested.txt").write_text(
                "dirty\n", encoding="utf-8"
            )
            try:
                capture(
                    root,
                    base_sha,
                    Path(temp) / "dirty-submodule",
                    [],
                    [brief_binding],
                    [result_binding],
                )
                failures.append("dirty submodule contents were accepted")
            except ArtifactError:
                pass
            module_path = root / "module"
            (module_path / "nested.txt").write_text(
                "clean\n",
                encoding="utf-8",
            )
            (submodule_source / "nested.txt").write_text(
                "next\n",
                encoding="utf-8",
            )
            git(submodule_source, "commit", "-qam", "next nested commit")
            next_submodule_commit = full_commit(submodule_source, "HEAD")
            git(module_path, "fetch", "-q", "origin")
            git(module_path, "checkout", "-q", next_submodule_commit)
            git(root, "config", "submodule.module.ignore", "all")
            try:
                assert_visible_repository_state(root)
                failures.append(
                    "submodule HEAD/index mismatch hidden by ignore=all was accepted"
                )
            except ArtifactError:
                pass
            git(root, "add", "module")
            _all_submodule_paths, tracked_submodule_paths, _untracked = (
                changed_paths(root, base_sha, ["module"])
            )
            if b"module" not in tracked_submodule_paths:
                failures.append(
                    "staged submodule change hidden by ignore=all was omitted"
                )
            removed_module = Path(temp) / "removed-module-worktree"
            module_path.rename(removed_module)
            try:
                assert_visible_repository_state(root)
                failures.append("missing initialized submodule was accepted")
            except ArtifactError:
                pass
    except (ArtifactError, OSError, subprocess.SubprocessError) as error:
        failures.append(str(error))

    if failures:
        print("review-artifact self-test failed: " + "; ".join(failures))
        return 1
    print("review-artifact self-test: OK")
    return 0


def parser() -> argparse.ArgumentParser:
    argument_parser = argparse.ArgumentParser(description=__doc__)
    argument_parser.add_argument(
        "--repository-root",
        type=Path,
        default=Path.cwd(),
        help="repository to capture or verify (default: current directory)",
    )
    subparsers = argument_parser.add_subparsers(dest="command")

    capture_parser = subparsers.add_parser("capture")
    capture_parser.add_argument("--base", required=True)
    capture_parser.add_argument("--output-dir", required=True, type=Path)
    capture_parser.add_argument("--brief", action="append", default=[])
    capture_parser.add_argument(
        "--implementation-result",
        action="append",
        default=[],
    )

    contract_parser = subparsers.add_parser("validate-contracts")
    contract_parser.add_argument("--base", required=True)
    contract_parser.add_argument("--brief", action="append", default=[])
    contract_parser.add_argument(
        "--implementation-result",
        action="append",
        default=[],
    )

    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--manifest", required=True, type=Path)
    verify_parser.add_argument("--patch", required=True, type=Path)
    verify_parser.add_argument("--brief", action="append", default=[])
    verify_parser.add_argument(
        "--implementation-result",
        action="append",
        default=[],
    )

    result_parser = subparsers.add_parser("validate-result")
    result_parser.add_argument("--manifest", required=True, type=Path)
    result_parser.add_argument("--result", required=True, type=Path)
    result_parser.add_argument("--axis", required=True, choices=sorted(AXIS_STATUSES))
    return argument_parser


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        return self_test()
    args = parser().parse_args(argv)
    try:
        if args.command == "capture":
            manifest_path, patch_path, manifest = capture(
                args.repository_root,
                args.base,
                args.output_dir,
                [],
                args.brief,
                args.implementation_result,
            )
            print("READY")
            print(f"manifest: {manifest_path.resolve()}")
            print(f"patch: {patch_path.resolve()}")
            print(f"artifact_id: {manifest['artifact_id']}")
            print(f"patch_bytes: {manifest['patch_bytes']}")
            return 0
        if args.command == "validate-contracts":
            brief_ids, result_ids = validate_contracts(
                args.repository_root,
                args.base,
                args.brief,
                args.implementation_result,
            )
            print(
                "VALID_CONTRACTS "
                f"brief_ids={json.dumps(brief_ids, separators=(',', ':'))} "
                f"result_ids={json.dumps(result_ids, separators=(',', ':'))}"
            )
            return 0
        if args.command == "verify":
            manifest = verify(
                args.manifest,
                args.patch,
                args.repository_root,
                args.brief,
                args.implementation_result,
            )
            print(f"VERIFIED artifact_id={manifest['artifact_id']}")
            return 0
        if args.command == "validate-result":
            result = validate_result(
                args.manifest,
                args.result,
                args.axis,
                args.repository_root,
            )
            print(
                f"VALID_RESULT axis={result['axis']} status={result['status']} "
                f"artifact_id={result['artifact_id']}"
            )
            return 0
        parser().print_usage(sys.stderr)
        return 2
    except (ArtifactError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
