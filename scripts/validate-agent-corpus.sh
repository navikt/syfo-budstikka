#!/usr/bin/env bash
# Deterministic gate for the agent corpus (.github/skills, .github/agents,
# .github/instructions, and the two agent-facing files at the root of .github).
# Enforces the invariants established when the corpus was pruned and migrated to
# English, so they cannot decay silently.
#
# Checks:
#   1. No Norwegian prose in agent-facing files, except a deliberate allowlist
#      that must itself stay justified.
#   2. Every skill's `name:` matches its directory (the name is the skill id).
#   3. No reference to a skill or instruction file that does not exist.
#   4. Links into a skill's own references/ directory resolve.
#
# Usage: bash scripts/validate-agent-corpus.sh
# Exit:  0 = clean, 1 = violation
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

failures=0

fail() {
    printf '::error::%s\n' "$1" >&2
    failures=1
}

# --- Norwegian detection thresholds -----------------------------------------
#
# Norwegian is detected from function words, not from æøå. Canonical domain
# terms (sykmelding, fødselsnummer, påminnelse) are *required* to survive
# translation by docs/agents/language-policy.md, so a per-character measure
# punishes exactly the files that follow the policy. Function words separate
# prose language instead; they do not occur in English sentences.
#
# Measured on this corpus (stopwords per 100 words, code stripped):
#   English, highest: auth-overview 0.14, diagnosing-bugs 0.06,
#                     readme-update 0.06, everything else 0.00
#   Norwegian, lowest: klarsprak/for-og-etter 8.00, klarsprak/SKILL.md 8.20,
#                      fagtermer-og-anglisismer 8.21, norwegian-text 14.33
#                      (docs/, the Norwegian half of the repository, runs
#                      8.96 to 20.25)
# A threshold of 4.00 sits 28x above the loudest English file and 2x below the
# quietest Norwegian one.
#
# Ratio alone is not enough: a 20-word file crosses 4.00 on a single hit. The
# absolute minimum makes the measure refuse to speak until it has evidence.
NORWEGIAN_RATIO_THRESHOLD=400 # 4.00 stopwords per 100 words, scaled by 100
NORWEGIAN_MINIMUM_HITS=5

# A gate that scans nothing reports success. If the pathspecs below stop
# matching (a directory is renamed, a checkout is partial), that must fail
# loudly rather than pass vacuously.
MINIMUM_CORPUS_FILES=20

# --- Allowlist: files that are deliberately Norwegian ------------------------
#
# Each entry must still measure as Norwegian; a stale exception would blind the
# gate to a file it is supposed to police, so this script fails when an entry
# stops being needed. Patterns are matched with bash globbing, where `*` also
# spans directory separators.
#
#   klarsprak/*                     Norwegian language craft is the subject
#   norwegian-text.instructions.md  same
#
# Files whose only Norwegian is a verbatim template or an error string inside a
# code block (triage/SKILL.md, triage/AGENT-BRIEF.md, pull-request/SKILL.md,
# readme-update/SKILL.md, observability-setup/references/alerting.md) need no
# entry: the measure below ignores code, so they are checked like any other
# English file and their English prose is still policed.
norwegian_allowlist=(
    '.github/skills/klarsprak/*'
    '.github/instructions/norwegian-text.instructions.md'
)

# --- Collect the corpus ------------------------------------------------------
#
# -z, because git quotes paths that contain non-ASCII bytes ("p\303\245.md").
# Reading those unquoted made every Norwegian-named file silently unreadable,
# which is the opposite of what this gate is for.
corpus_files=()
while IFS= read -r -d '' corpus_file; do
    # A staged deletion is still listed by git ls-files; nothing to read.
    [[ -f "$corpus_file" ]] || continue
    corpus_files+=("$corpus_file")
done < <(
    git ls-files -z \
        '.github/skills/*.md' \
        '.github/skills/**/*.md' \
        '.github/agents/*.md' \
        '.github/instructions/*.md' \
        '.github/copilot-instructions.md' \
        '.github/GRILLMESTER.md'
)

if [[ "${#corpus_files[@]}" -lt "$MINIMUM_CORPUS_FILES" ]]; then
    fail "Only ${#corpus_files[@]} agent-facing files matched; expected at least ${MINIMUM_CORPUS_FILES}. The pathspecs in this script have stopped matching the corpus — fix them rather than letting the gate pass on an empty set."
    echo "Agent corpus validation failed." >&2
    exit 1
fi

# --- One analysis pass over the corpus ---------------------------------------
#
# One interpreter for the whole corpus rather than one per file per check.
# Records are NUL-separated and fields are separated by US (0x1f), so paths
# keep whatever bytes they have and empty fields survive the round trip.
analysis_program=$(
    cat <<'PYTHON'
import re
import sys

STOPWORDS = (
    "og", "ikke", "som", "skal", "det", "den", "hvis", "når", "derfor",
    "eller", "men", "for å", "med", "fra", "til", "kan", "må", "blir",
    "være", "har",
)
STOPWORD_RE = re.compile(
    "|".join(
        r"\b" + r"\s+".join(re.escape(part) for part in word.split()) + r"\b"
        for word in STOPWORDS
    ),
    re.IGNORECASE,
)
# Code is not prose. Domain terms are usually cited as inline code, and fenced
# blocks hold Kotlin samples, Norwegian error strings and verbatim issue
# templates — all of which docs/agents/language-policy.md says to preserve.
FENCED_CODE_RE = re.compile(r"^[ \t]*(`{3,}|~{3,})[^\n]*$.*?^[ \t]*\1[ \t]*$", re.M | re.S)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
WORD_RE = re.compile(r"[^\W\d_]+")
# Only the frontmatter block declares the id; a `name:` inside an example is
# someone else's YAML.
FRONTMATTER_RE = re.compile(r"\A---[ \t]*\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|\Z)", re.S)
NAME_RE = re.compile(r"^name:[ \t]*(.+)$", re.M)
REFERENCE_LINK_RE = re.compile(r"\]\((?:\./)?(references/[A-Za-z0-9_.-]+\.md)\)")

US = b"\x1f"
out = sys.stdout.buffer


def emit(kind, value, path):
    out.write(kind + US + value.replace("\t", " ").encode("utf-8") + US + path + b"\0")


for path in sys.stdin.buffer.read().split(b"\0"):
    if not path:
        continue
    try:
        with open(path, "rb") as handle:
            blob = handle.read()
    except OSError as error:
        emit(b"UNREADABLE", str(error), path)
        continue
    try:
        text = blob.decode("utf-8")
    except UnicodeDecodeError as error:
        emit(b"DECODE", str(error), path)
        continue

    prose = INLINE_CODE_RE.sub(" ", FENCED_CODE_RE.sub(" ", text))
    words = len(WORD_RE.findall(prose))
    hits = len(STOPWORD_RE.findall(prose))
    ratio = round(hits / words * 10000) if words else 0
    emit(b"LANG", "%d %d" % (ratio, hits), path)

    parts = path.split(b"/")
    if len(parts) == 4 and parts[:2] == [b".github", b"skills"] and parts[3] == b"SKILL.md":
        frontmatter = FRONTMATTER_RE.match(text)
        match = NAME_RE.search(frontmatter.group(1)) if frontmatter else None
        emit(b"NAME", match.group(1).strip().strip("\"'") if match else "", path)

    if path.startswith(b".github/skills/"):
        for target in REFERENCE_LINK_RE.findall(text):
            emit(b"LINK", target, path)
PYTHON
)

allowlist_matches=()
for index in $(seq 0 $((${#norwegian_allowlist[@]} - 1))); do
    allowlist_matches[index]=0
done

# Checks 1 (Norwegian prose), 2 (skill name matches directory) and 4 (links into
# references/ resolve) all read the same records.
while IFS=$'\x1f' read -r -d '' kind value path; do
    case "$kind" in
    UNREADABLE)
        fail "File could not be read: ${path} (${value})."
        ;;

    DECODE)
        fail "File is not valid UTF-8 and cannot be checked: ${path} (${value}). Re-encode it as UTF-8."
        ;;

    LANG)
        ratio="${value% *}"
        hits="${value#* }"
        norwegian=0
        if [[ "$ratio" -gt "$NORWEGIAN_RATIO_THRESHOLD" && "$hits" -ge "$NORWEGIAN_MINIMUM_HITS" ]]; then
            norwegian=1
        fi
        printf -v measured '%d.%02d' "$((ratio / 100))" "$((ratio % 100))"

        allowlisted=-1
        for index in $(seq 0 $((${#norwegian_allowlist[@]} - 1))); do
            # Unquoted on purpose: the allowlist entries are glob patterns.
            # shellcheck disable=SC2053
            if [[ "$path" == ${norwegian_allowlist[index]} ]]; then
                allowlisted="$index"
                break
            fi
        done

        if [[ "$allowlisted" -ge 0 ]]; then
            allowlist_matches[allowlisted]=$((allowlist_matches[allowlisted] + 1))
            if [[ "$norwegian" -eq 0 ]]; then
                fail "Allowlisted file is no longer Norwegian (${measured} stopwords/100 words, ${hits} hits): ${path}. The exception now only blinds this gate — drop it from the allowlist in this script."
            fi
        elif [[ "$norwegian" -eq 1 ]]; then
            fail "Norwegian prose in agent-facing file (${measured} stopwords/100 words, ${hits} hits, threshold 4.00): ${path}. Translate it, or add it to the allowlist in this script with a reason."
        fi
        ;;

    NAME)
        directory="${path%/*}"
        directory="${directory##*/}"
        if [[ -z "$value" ]]; then
            fail "Skill has no name in its frontmatter: ${path}"
        elif [[ "$value" != "$directory" ]]; then
            fail "Skill name '${value}' does not match its directory '${directory}': ${path}. The name is the invocation id."
        fi
        ;;

    LINK)
        [[ -e "${path%/*}/${value}" ]] ||
            fail "Broken link to '${value}' in: ${path}"
        ;;
    esac
done < <(printf '%s\0' "${corpus_files[@]}" | python3 -c "$analysis_program")

for index in $(seq 0 $((${#norwegian_allowlist[@]} - 1))); do
    if [[ "${allowlist_matches[index]}" -eq 0 ]]; then
        fail "Norwegian allowlist entry '${norwegian_allowlist[index]}' matches no file in the corpus. Remove it from this script."
    fi
done

# --- 3a. No references to skills that do not exist ---------------------------
#
# Every `/name` cited in backticks must resolve to a skill that exists. Deriving
# the invalid set from a hardcoded list of retirements only ever catches the
# retirements someone remembered to write down; this catches deletions, renames
# and typos alike.
#
# Multi-segment paths (`/internal/isalive`, `/rest/v1/...`) never match the
# pattern, because a skill id has no slash in it. Single-segment paths that are
# not skills need to be named:
non_skill_paths=(
    actuator api bin dev etc health healthz home internal isalive isready
    livez metrics opt proc prometheus ready readyz root run srv sys tmp usr var
)

existing_skills="$(git ls-files '.github/skills/*/SKILL.md' | awk -F/ '{print $3}' | sort -u)"
# Named separately only to say "retired" instead of "unknown" in the message.
deleted_skills="$(
    git log --diff-filter=D --format= --name-only -- '.github/skills/*/SKILL.md' 2>/dev/null |
        awk -F/ 'NF >= 3 {print $3}' | sort -u || true
)"

while IFS= read -r cited; do
    [[ -n "$cited" ]] || continue
    printf '%s\n' "$existing_skills" | grep -qxF -- "$cited" && continue

    known_non_skill=0
    for candidate in "${non_skill_paths[@]}"; do
        [[ "$cited" == "$candidate" ]] && known_non_skill=1 && break
    done
    [[ "$known_non_skill" -eq 1 ]] && continue

    locations="$(git grep -lnF -- "\`/${cited}\`" -- '.github' 'docs' 'README.md' || true)"
    if printf '%s\n' "$deleted_skills" | grep -qxF -- "$cited"; then
        fail "Reference to retired skill '/${cited}' in: $(printf '%s' "$locations" | tr '\n' ' ')"
    else
        fail "Reference to unknown skill '/${cited}' in: $(printf '%s' "$locations" | tr '\n' ' '). Either it is a typo, or it is an ordinary path that belongs in non_skill_paths in this script."
    fi
done < <(
    # shellcheck disable=SC2016  # the backticks are literal: skills are cited as `/name`
    git grep -ohE '`/[a-z0-9][a-z0-9-]*`' -- '.github' 'docs' 'README.md' 2>/dev/null |
        tr -d '`' | cut -c2- | sort -u || true
)

# --- 3c. No references to instruction files that do not exist ----------------
while IFS= read -r instruction_reference; do
    [[ -n "$instruction_reference" ]] || continue
    [[ -f "$instruction_reference" ]] && continue
    locations="$(git grep -lnF -- "$instruction_reference" -- '.github' 'docs' 'README.md' || true)"
    fail "Reference to missing instruction file '${instruction_reference}' in: $(printf '%s' "$locations" | tr '\n' ' ')"
done < <(
    git grep -ohE '\.github/instructions/[a-z0-9_-]+\.instructions\.md' -- '.github' 'docs' 'README.md' 2>/dev/null |
        sort -u || true
)

if [[ "$failures" -ne 0 ]]; then
    echo "Agent corpus validation failed." >&2
    exit 1
fi

printf 'Agent corpus valid: %d files scanned, no undeclared Norwegian, skill names match directories, no dangling references.\n' "${#corpus_files[@]}"
