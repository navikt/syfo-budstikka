#!/usr/bin/env bash
# Deterministic gate for the agent corpus (.github/skills, .github/agents,
# .github/instructions, docs/agents, and the two agent-facing files at the root
# of .github). Enforces the invariants established when the corpus was pruned
# and migrated to English, so they cannot decay silently.
#
# Checks:
#   1. No Norwegian prose in agent-facing files, except a deliberate allowlist
#      that must itself stay justified.
#   2. Every skill's `name:` matches its directory (the name is the skill id),
#      its frontmatter carries a non-empty description, and only known keys are
#      used — a misspelled boundary key silently changes who can invoke it.
#   3. No reference to a skill or instruction file that does not exist,
#      including unbackticked citations of retired skills.
#   4. Relative links in corpus prose resolve.
#   5. Every agent in .github/agents/ appears in both roster maps, and @agent
#      references resolve to an existing agent.
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
# Distinct words guard against English collisions in the stopword list: "HAR"
# (the devtools HTTP Archive format), "men" and "den" are legitimate English
# tokens, and an English file about one of them can repeat it past both
# thresholds on its own. Norwegian prose always spreads across many function
# words; repetition of a single one is English jargon, not Norwegian.
NORWEGIAN_RATIO_THRESHOLD=400 # 4.00 stopwords per 100 words, scaled by 100
NORWEGIAN_MINIMUM_HITS=5
NORWEGIAN_MINIMUM_DISTINCT=3

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
        '.github/GRILLMESTER.md' \
        'docs/agents/*.md'
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
# \r? on the closing fence: a CRLF checkout must not silently un-close every
# fence and turn its code into counted prose.
FENCED_CODE_RE = re.compile(r"^[ \t]*(`{3,}|~{3,})[^\n]*$.*?^[ \t]*\1[ \t]*\r?$", re.M | re.S)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
WORD_RE = re.compile(r"[^\W\d_]+")
# Only the frontmatter block declares the id; a `name:` inside an example is
# someone else's YAML.
FRONTMATTER_RE = re.compile(r"\A---[ \t]*\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|\Z)", re.S)
NAME_RE = re.compile(r"^name:[ \t]*(.+)$", re.M)
DESCRIPTION_RE = re.compile(r"^description:[ \t]*(.+)$", re.M)
KEY_RE = re.compile(r"^([A-Za-z][A-Za-z_-]*):", re.M)
# Any relative link, extracted from code-stripped prose so illustrative links
# inside fenced examples stay out of scope. Anchors are split off before the
# target is resolved.
RELATIVE_LINK_RE = re.compile(r"\]\((?!https?://|mailto:|#|/)([^)#\s]+?)(?:#[^)]*)?\)")
# Agents are cited as @name in prose; a retired or misspelled agent reference
# has no other guard. The lookahead keeps identifiers with a longer tail
# (@event_name) from matching on their prefix.
AGENT_REFERENCE_RE = re.compile(r"@([a-z][a-z-]+)(?![A-Za-z0-9_])")

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
    matches = STOPWORD_RE.findall(prose)
    hits = len(matches)
    distinct = len({re.sub(r"\s+", " ", match.lower()) for match in matches})
    ratio = round(hits / words * 10000) if words else 0
    emit(b"LANG", "%d %d %d" % (ratio, hits, distinct), path)

    parts = path.split(b"/")
    if len(parts) == 4 and parts[:2] == [b".github", b"skills"] and parts[3] == b"SKILL.md":
        frontmatter = FRONTMATTER_RE.match(text)
        match = NAME_RE.search(frontmatter.group(1)) if frontmatter else None
        emit(b"NAME", match.group(1).strip().strip("\"'") if match else "", path)
        description = DESCRIPTION_RE.search(frontmatter.group(1)) if frontmatter else None
        keys = KEY_RE.findall(frontmatter.group(1)) if frontmatter else []
        described = 1 if description and description.group(1).strip().strip("\"'") else 0
        emit(b"SKILLFM", "%d %s" % (described, ",".join(keys)), path)

    for target in RELATIVE_LINK_RE.findall(prose):
        emit(b"LINK", target, path)
    for cited_agent in AGENT_REFERENCE_RE.findall(prose):
        emit(b"AGENTREF", cited_agent, path)
PYTHON
)

allowlist_matches=()
for index in $(seq 0 $((${#norwegian_allowlist[@]} - 1))); do
    allowlist_matches[index]=0
done

# The roster feeds both halves of check 5: @agent references (in the stream)
# and the roster maps (after it).
agent_roster="$(git ls-files '.github/agents/*.agent.md' | sed -e 's|.*/||' -e 's|\.agent\.md$||' | sort -u)"

# Checks 1 (Norwegian prose), 2 (skill identity and frontmatter), 4 (relative
# links) and the @agent half of check 5 all read the same records.
while IFS=$'\x1f' read -r -d '' kind value path; do
    case "$kind" in
    UNREADABLE)
        fail "File could not be read: ${path} (${value})."
        ;;

    DECODE)
        fail "File is not valid UTF-8 and cannot be checked: ${path} (${value}). Re-encode it as UTF-8."
        ;;

    LANG)
        read -r ratio hits distinct <<<"$value"
        norwegian=0
        if [[ "$ratio" -gt "$NORWEGIAN_RATIO_THRESHOLD" && "$hits" -ge "$NORWEGIAN_MINIMUM_HITS" && "$distinct" -ge "$NORWEGIAN_MINIMUM_DISTINCT" ]]; then
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
                fail "Allowlisted file is no longer Norwegian (${measured} stopwords/100 words, ${hits} hits, ${distinct} distinct): ${path}. The exception now only blinds this gate — drop it from the allowlist in this script."
            fi
        elif [[ "$norwegian" -eq 1 ]]; then
            fail "Norwegian prose in agent-facing file (${measured} stopwords/100 words, ${hits} hits across ${distinct} distinct stopwords, threshold 4.00): ${path}. Translate it, or add it to the allowlist in this script with a reason."
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

    SKILLFM)
        read -r described frontmatter_keys <<<"$value"
        if [[ "$described" -ne 1 ]]; then
            fail "Skill has no description in its frontmatter: ${path}. The description is the discovery surface that decides when the skill loads."
        fi
        IFS=',' read -ra skill_keys <<<"$frontmatter_keys"
        for skill_key in "${skill_keys[@]}"; do
            case "$skill_key" in
            name | description | disable-model-invocation | argument-hint) ;;
            *)
                fail "Unknown frontmatter key '${skill_key}' in: ${path}. Allowed: name, description, disable-model-invocation, argument-hint. A misspelled boundary key silently changes who can invoke the skill."
                ;;
            esac
        done
        ;;

    LINK)
        [[ -e "${path%/*}/${value}" ]] ||
            fail "Broken link to '${value}' in: ${path}"
        ;;

    AGENTREF)
        printf '%s\n' "$agent_roster" | grep -qxF -- "$value" ||
            fail "Reference to unknown agent '@${value}' in: ${path}. Agents live in .github/agents/*.agent.md."
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

# Backticks are the citation convention, but descriptions and running prose
# also cite skills bare (/prototype) or by path (skills/prototype/). A bare
# unknown token is indistinguishable from an ordinary path, so this pass only
# polices the names known to be retired — which is the decay that matters.
while IFS= read -r retired; do
    [[ -n "$retired" ]] || continue
    printf '%s\n' "$existing_skills" | grep -qxF -- "$retired" && continue
    locations="$(git grep -lE "(^|[^\`a-zA-Z0-9/_-])/${retired}([^a-z0-9/-]|\$)|skills/${retired}/" -- '.github' 'docs' 'README.md' || true)"
    [[ -z "$locations" ]] ||
        fail "Unbackticked reference to retired skill '/${retired}' in: $(printf '%s' "$locations" | tr '\n' ' ')"
done <<<"$deleted_skills"

# --- 3b. No references to instruction files that do not exist ----------------
while IFS= read -r instruction_reference; do
    [[ -n "$instruction_reference" ]] || continue
    [[ -f "$instruction_reference" ]] && continue
    locations="$(git grep -lnF -- "$instruction_reference" -- '.github' 'docs' 'README.md' || true)"
    fail "Reference to missing instruction file '${instruction_reference}' in: $(printf '%s' "$locations" | tr '\n' ' ')"
done < <(
    git grep -ohE '\.github/instructions/[a-z0-9_-]+\.instructions\.md' -- '.github' 'docs' 'README.md' 2>/dev/null |
        sort -u || true
)

# --- 5. Roster maps stay in sync with .github/agents/ ------------------------
#
# GRILLMESTER.md calls itself the human-facing map and copilot-instructions.md
# names the agent lineup. An agent absent from either is discoverable only by
# listing the directory — which is how two agents once went unmapped for weeks.
for mapped_agent in $agent_roster; do
    for roster_map in .github/GRILLMESTER.md .github/copilot-instructions.md; do
        grep -qiE "(^|[^a-z-])${mapped_agent}([^a-z-]|\$)" "$roster_map" ||
            fail "Agent '${mapped_agent}' is missing from the roster map ${roster_map}."
    done
done

if [[ "$failures" -ne 0 ]]; then
    echo "Agent corpus validation failed." >&2
    exit 1
fi

printf 'Agent corpus valid: %d files scanned, no undeclared Norwegian, skill identities and frontmatter valid, roster maps in sync, no dangling references.\n' "${#corpus_files[@]}"
