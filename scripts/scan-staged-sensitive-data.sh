#!/usr/bin/env bash
# Deterministic PII and secret gate for staged additions. An explicit local
# artifact directory may also be passed as an argument. Briefs and handoffs are
# task-scoped; the gate assumes no global agent directory.
#
# Usage: scripts/scan-staged-sensitive-data.sh [artifact-directory]
#        scripts/scan-staged-sensitive-data.sh --diff <base-sha> [head-sha]
#        scripts/scan-staged-sensitive-data.sh --self-test
# Run by .githooks/pre-commit (enable with: git config core.hooksPath .githooks).
# Never prints the match or path because either may be sensitive; diagnostics
# contain only an opaque file ordinal, line number, and rule.
# Exit: 0 = clean, 1 = suspicion (blocks commit)
set -uo pipefail

hits=0

# Rule name -> ERE pattern. Portable across GNU and BSD grep (no \b); AWS IDs
# use a custom scanner so the documented placeholder can be allowed precisely.
rule_names=(
  identity-number
  jwt
  pem
  secret
  bearer
  github-token
  aws-access-key-id
  slack-token
)
rule_regex=(
  '(^|[^0-9])[0-9]{11}([^0-9]|$)'
  'eyJ[A-Za-z0-9_-]{10,}'
  '-{5}BEGIN'
  '(passord|password|secret|client[_-]?secret|api[_-]?key|token)[[:space:]]*[:=]'
  'Bearer[[:space:]]+[A-Za-z0-9._-]{10,}'
  'gh[opusr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,}'
  '(AKIA|ASIA)[0-9A-Z]{16}'
  'xox[baprs]-[A-Za-z0-9-]{30,}'
)

# scan_records <display-label> <records-file>
# Each record is SOURCE_LINE<TAB>CONTENT, so output can identify the original
# file line without ever printing the potentially sensitive content.
scan_records() {
  local label="$1" records="$2" mode="${3:-content}" i name pat lines
  for i in "${!rule_names[@]}"; do
    name="${rule_names[$i]}"; pat="${rule_regex[$i]}"
    if [ "$name" = "identity-number" ]; then
      # Placeholder 00000000000 and explicitly reviewed technical identifiers
      # are allowed. Do not allow arbitrary hash-shaped values: padding an
      # identity number to 40/64 hex characters must remain blocked.
      if ! lines="$(perl -ne '
            my ($source_line, $content) = split(/\t/, $_, 2);
            next unless defined $content;
            my $hit = 0;
            my %allowed_technical_identifier = map { $_ => 1 } (
              "88f95534684957ec406db8ce1153058f9fc65c23e145ddec40454b6a6512b1cf",
              "fd01c00fb41bbdb6c562f6c48028319547ac250b",
            );
            while ($content =~ /(?<![0-9])([0-9]{11})(?![0-9])/g) {
              my $candidate = $1;
              next if $candidate eq "00000000000";
              my $left = $-[1];
              my $right = $+[1];
              --$left
                while $left > 0 &&
                  substr($content, $left - 1, 1) =~ /[0-9a-f]/i;
              ++$right
                while $right < length($content) &&
                  substr($content, $right, 1) =~ /[0-9a-f]/i;
              my $technical_identifier =
                lc substr($content, $left, $right - $left);
              $hit = 1
                unless $allowed_technical_identifier{$technical_identifier};
            }
            print "$source_line\n" if $hit;
          ' "$records" 2>/dev/null | paste -sd, -)"; then
        echo "ERROR: sensitive-data rule $name failed for $label" >&2
        return 2
      fi
    elif [ "$name" = "secret" ]; then
      # Evaluate each assignment value, not the whole line. Otherwise an
      # unrelated comment can hide a literal. An indirect lookup is exempt only
      # when it consumes the complete scalar; a valid prefix plus literal
      # suffix must still be blocked.
      if ! lines="$(SCAN_MODE="$mode" perl -e '
          sub inspect_record {
            my ($record, $next_record) = @_;
            my ($source_line, $remaining) = split(/\t/, $record, 2);
            return unless defined $remaining;
            my ($next_source_line, $next_content) =
              defined $next_record ? split(/\t/, $next_record, 2) : ();
            my $content = $remaining;
            my $hit = 0;
            my $sensitive_key =
              qr/(?:[a-z0-9]+[_.-])*(?:passord|password|client[_-]?secret|api[_-]?key|private[_-]?key|access[_-]?key|signing[_-]?key|encryption[_-]?key|credential(?:s)?|secret|token)(?:(?:value|path|file|ref(?:erence)?|name|hash|credential|key(?:ref)?|dev|test|qa|staging|prod(?:uction)?)(?:[_.-]?[0-9]+|[_.-][a-z0-9]+)*|(?:[_.-][a-z0-9]+)*|(?:[_.-]?[0-9]+)*)/i;
            my $assignment_operator =
              qr/(?:\?\?=|\|\|=|&&=|\+=|\?=|::?=|[:=])/i;
            my $quoted_operator =
              qr/(?:$assignment_operator|\bto\b)/i;
            my $key_gap =
              qr/(?:\s|\/\*.*?\*\/)*/;
            $hit = 1
              if $remaining =~ /"[^"\r\n]*\\[^"\r\n]*"$key_gap\]?$key_gap$quoted_operator/ ||
                $remaining =~ /\x27[^\x27\r\n]*\\[^\x27\r\n]*\x27$key_gap\]?$key_gap$quoted_operator/ ||
                $remaining =~ /(?:[a-z0-9_.-]|\\u[0-9a-f]{4})*\\u[0-9a-f]{4}(?:[a-z0-9_.-]|\\u[0-9a-f]{4})*\s*\]?\s*$assignment_operator/i;
            $remaining =~ s/\\u([0-9a-f]{4})/chr(hex($1))/egi;
            $remaining =~ s{
              \b(?:[a-z_][a-z0-9_.]*\.)?
              (?:putifabsent|put|setproperty|set)$key_gap\($key_gap
              key$key_gap=$key_gap(["\x27])($sensitive_key)\1$key_gap,\s*
              value$key_gap=$key_gap
            }{$2=}igx;
            $remaining =~ s{
              \b(?:[a-z_][a-z0-9_.]*\.)?
              (?:putifabsent|put|setproperty|set|of|pair)$key_gap\($key_gap
              (["\x27])($sensitive_key)\1$key_gap,
            }{$2=}igx;
            while (
              $remaining =~
                /(?:(["\x27\x60])($sensitive_key)\1$key_gap\]?$key_gap($quoted_operator)|($sensitive_key)\s*\]?\s*($assignment_operator))/ig
            ) {
              my $key = lc(defined $2 ? $2 : $4);
              my $operator = lc(defined $3 ? $3 : $5);
              my $value = substr($remaining, $+[0]);
              my $explicit_continuation = $value =~ /\\\s*(?:\r?\n)?\z/;
              my $scalar = $value;
              $scalar =~ s/[\r\n]+\z//;
              $scalar =~ s/^\s+|\s+\z//g;
              $scalar =~ s/[;,]\s*\z//;
              $scalar =~ s/\s+\z//;
              if ($scalar =~ /^"([^"\r\n]*)"\s*[\]\})]*\s*\z/) {
                $scalar = $1;
              } elsif ($scalar =~ /^\x27([^\x27\r\n]*)\x27\s*[\]\})]*\s*\z/) {
                $scalar = $1;
              }
              $scalar =~ s/^\s+|\s+\z//g;
              my $normalized = lc $scalar;
              my $tail = qr/\s*[\]\})]*\s*/;
              my $indirect =
                $normalized =~ /^\$\{\??[a-z_][a-z0-9_.-]*\}$tail\z/ ||
                $normalized =~ /^\$\{\{\s*[a-z_][a-z0-9_.-]*\s*\}\}$tail\z/ ||
                $normalized =~ /^(?:[a-z_][a-z0-9_.]*\.)?getenv\s*\(\s*["\x27][a-z_][a-z0-9_]*["\x27]\s*\)$tail\z/ ||
                $normalized =~ /^(?:(?:env\.config|[a-z_][a-z0-9_.]*)\.)?property\s*\(\s*["\x27][a-z0-9_.-]+["\x27]\s*\)(?:\.(?:getstring|getlist|getboolean)\s*\(\s*\))?$tail\z/ ||
                $normalized =~ /^(?:secretkeyref|valuefrom)\s*:?$tail\z/;
              my $is_oidc_permission =
                $key eq "id-token" &&
                $operator eq ":" &&
                $normalized =~ /^(?:read|write|none)$tail\z/;
              my $is_checkout_auth_disabled =
                $key eq "persist-credentials" &&
                $operator eq ":" &&
                $normalized =~ /^(?:false|none)$tail\z/;
              my $logical_continuation = $explicit_continuation;
              if (
                defined $next_content &&
                $next_source_line =~ /^\d+$/ &&
                $source_line =~ /^\d+$/ &&
                $next_source_line == $source_line + 1
              ) {
                my ($indent) = $content =~ /^([ \t]*)/;
                my ($next_indent) = $next_content =~ /^([ \t]*)/;
                my $next_is_operator =
                  $next_content =~ /^\s*(?:\+|\.|\?:|\?\?|&&|\|\|)/;
                my $next_is_structural =
                  $next_content =~
                    /^\s*(?:"[^"]+"|\x27[^\x27]+\x27|[a-z_][a-z0-9_.-]*)\s*[:=]/i;
                my $next_is_closer = $next_content =~ /^\s*(?:[}\])]|$)/;
                $logical_continuation = 1
                  if $next_is_operator ||
                    (
                      length($next_indent) > length($indent) &&
                      !$next_is_structural &&
                      !$next_is_closer
                    );
              }
              $hit = 1
                if $ENV{"SCAN_MODE"} eq "path" ||
                  $logical_continuation ||
                  (
                    !$is_oidc_permission &&
                    !$is_checkout_auth_disabled &&
                    !$indirect
                  );
              $remaining = $value;
            }
            print "$source_line\n" if $hit;
          }

          my $previous_record;
          while (my $record = <>) {
            inspect_record($previous_record, $record)
              if defined $previous_record;
            $previous_record = $record;
          }
          inspect_record($previous_record, undef)
            if defined $previous_record;
        ' "$records" 2>/dev/null | paste -sd, -)"; then
        echo "ERROR: sensitive-data rule $name failed for $label" >&2
        return 2
      fi
    elif [ "$name" = "aws-access-key-id" ]; then
      # The documented AWS example key ID is allowed. Inspect every long-lived
      # AKIA and temporary ASIA run so an actual key on the same line is still
      # blocked.
      if ! lines="$(perl -ne '
            my ($source_line, $content) = split(/\t/, $_, 2);
            next unless defined $content;
            my $hit = 0;
            while ($content =~ /((?:AKIA|ASIA)[0-9A-Z]+)/g) {
              my $candidate = $1;
              $hit = 1
                if length($candidate) >= 20 &&
                  substr($candidate, 0, 20) ne "AKIAIOSFODNN7EXAMPLE";
            }
            print "$source_line\n" if $hit;
          ' "$records" 2>/dev/null | paste -sd, -)"; then
        echo "ERROR: sensitive-data rule $name failed for $label" >&2
        return 2
      fi
    else
      # Treat content as text even when it contains a NUL byte. Keep only source
      # line numbers and discard content.
      if ! lines="$(
        { grep -aiE -e "$pat" "$records" 2>/dev/null || [ "$?" -eq 1 ]; } |
          cut -f1 |
          paste -sd, -
      )"; then
        echo "ERROR: sensitive-data rule $name failed for $label" >&2
        return 2
      fi
    fi
    if [ -n "$lines" ]; then
      echo "Possible PII or secret ($name) in $label — line(s): $lines"
      hits=1
    fi
  done
}

# scan_file <display-label> <file-on-disk>
scan_file() {
  local label="$1" file="$2" records
  records="$(mktemp)" || {
    echo "ERROR: unable to allocate a temporary scan file" >&2
    return 2
  }
  if ! perl -ne 'print "$.\t$_"' "$file" > "$records" 2>/dev/null; then
    rm -f "$records"
    echo "ERROR: unable to read $label for sensitive-data scanning" >&2
    return 2
  fi
  if ! scan_records "$label" "$records"; then
    rm -f "$records"
    return 2
  fi
  rm -f "$records"
}

# scan_path <opaque-display-label> <path>
# Git paths are NUL-delimited before this function. A path itself cannot contain
# NUL, but it may contain tabs or newlines; normalize those record delimiters
# before applying the same rules as content.
scan_path() {
  local label="$1" path="$2" records
  records="$(mktemp)" || {
    echo "ERROR: unable to allocate a temporary path scan file" >&2
    return 2
  }
  if ! SCANNED_PATH="$path" perl -e '
      my $path = $ENV{"SCANNED_PATH"};
      my $normalized = $path;
      $normalized =~ s/[\r\n\t]/\//g;
      my $compact = $path;
      $compact =~ s/[\r\n\t]//g;
      print "1\t$normalized\n";
      print "2\t$compact\n" if $compact ne $normalized;
    ' > "$records"; then
    rm -f "$records"
    echo "ERROR: unable to prepare $label path for sensitive-data scanning" >&2
    return 2
  fi
  if ! scan_records "$label path" "$records" path; then
    rm -f "$records"
    return 2
  fi
  rm -f "$records"
}

# render_post_image_records <added-line-numbers> <post-image> <records-file>
# A diff only tells us which post-image lines are new. Some valid Kotlin/Java
# forms put the sensitive key and the literal on separate lines, possibly with
# an arbitrary retained block comment between them. Reconstructing a fixed
# number of diff context lines is therefore a bypass. Instead, inspect the
# post-image and emit logical records only for a changed line: the line itself,
# its preceding assignment through blank/comment gaps, and an enclosing
# key/value method call. Unrelated unchanged assignments are never emitted.
render_post_image_records() {
  local added_lines="$1" post_image="$2" records="$3"
  if ! perl - "$added_lines" "$post_image" > "$records" <<'PERL'
use strict;
use warnings;

my ($added_path, $image_path) = @ARGV;
open my $added_fh, '<', $added_path or die "cannot read added-line map\n";
my %added;
while (my $line = <$added_fh>) {
  chomp $line;
  $added{$line} = 1 if $line =~ /^\d+$/;
}
exit 0 unless %added;

open my $image_fh, '<', $image_path or die "cannot read post-image\n";
my @lines = <$image_fh>;

my $sensitive_key = qr/
  (?:[a-z0-9]+[_.-])*
  (?:passord|password|client[_-]?secret|api[_-]?key|private[_-]?key|
     access[_-]?key|signing[_-]?key|encryption[_-]?key|credential(?:s)?|
     secret|token)
  (?:(?:value|path|file|ref(?:erence)?|name|hash|credential|key(?:ref)?|
       dev|test|qa|staging|prod(?:uction)?)
      (?:[_.-]?[0-9]+|[_.-][a-z0-9]+)*|
    (?:[_.-][a-z0-9]+)*|
    (?:[_.-]?[0-9]+)*)
/ix;
my $operator = qr/(?:\?\?=|\|\|=|&&=|\+=|\?=|::?=|[:=])/;
my $key_gap = qr/(?:\s|\/\*.*?\*\/)*/;

sub decoded {
  my ($line) = @_;
  $line =~ s/\\u([0-9a-f]{4})/chr(hex($1))/egi;
  return $line;
}

sub flattened {
  my (@parts) = @_;
  my $text = join ' ', @parts;
  $text =~ s/[\r\n]+/ /g;
  return $text;
}

sub is_gap {
  my ($line) = @_;
  $line =~ s/[\r\n]+\z//;
  return 1 if $line =~ /^\s*\z/;
  return $line =~ /^\s*(?:#|\/\/|\/\*|\*|\*\/).*/;
}

sub is_dangling_sensitive_assignment {
  my ($line) = @_;
  $line = decoded($line);
  $line =~ s/[\r\n]+\z//;
  return $line =~ /(?:["'`])$sensitive_key(?:["'`])?$key_gap\]?${key_gap}$operator\s*\z/i ||
    $line =~ /$sensitive_key\s*\]?\s*$operator\s*\z/i;
}

sub is_sensitive_assignment_call_start {
  my ($line) = @_;
  $line = decoded($line);
  $line =~ s/[\r\n]+\z//;
  return $line =~ /$sensitive_key\s*\]?\s*$operator.*\(/i;
}

sub is_key_value_method_start {
  my ($line) = @_;
  $line = decoded($line);
  return $line =~ /\b(?:[a-z_][a-z0-9_.]*\.)?
    (?:putifabsent|put|setproperty|set|of|pair)$key_gap\(/ix;
}

sub paren_delta {
  my ($line, $in_block_comment) = @_;
  $line = decoded($line);
  my $code = q{};
  while (length $line) {
    if ($$in_block_comment) {
      if ($line =~ s/\A.*?\*\///s) {
        $$in_block_comment = 0;
      } else {
        return 0;
      }
      next;
    }
    if ($line =~ s{\A(.*?)\/\*}{}s) {
      $code .= $1;
      $$in_block_comment = 1;
      next;
    }
    $code .= $line;
    last;
  }
  $code =~ s{"(?:\\.|[^"\\])*"}{}g;
  $code =~ s{'(?:\\.|[^'\\])*'}{}g;
  # Strip quoted strings before line comments: a URL or fragment marker inside
  # the value is data, not a comment that can hide the closing parenthesis.
  $code =~ s{//.*\z}{};
  $code =~ s{\#.*\z}{};
  return ($code =~ tr/(//) - ($code =~ tr/)//);
}

sub call_end {
  my ($start) = @_;
  my $depth = 0;
  my $in_block_comment = 0;
  for my $index ($start .. $#lines) {
    $depth += paren_delta($lines[$index], \$in_block_comment);
    return $index if $depth <= 0;
  }
  return undef;
}

sub emit {
  my ($line_number, @parts) = @_;
  print $line_number, "\t", flattened(@parts), "\n";
}

for my $line_number (sort { $a <=> $b } keys %added) {
  next if $line_number < 1 || $line_number > @lines;
  my $index = $line_number - 1;
  emit($line_number, $lines[$index]);

  # Reattach a preceding sensitive assignment after any retained blank or
  # comment-only lines. This is intentionally unbounded: an arbitrary comment
  # block is still part of the logical assignment expression.
  my @gap;
  my $previous = $index - 1;
  while ($previous >= 0 && is_gap($lines[$previous])) {
    unshift @gap, $lines[$previous];
    --$previous;
  }
  if ($previous >= 0 && is_dangling_sensitive_assignment($lines[$previous])) {
    emit($line_number, $lines[$previous], @gap, $lines[$index]);
  }

  # A changed value can also sit inside a retained wrapper call whose sensitive
  # assignment and wrapper opening are on the same earlier line.
  # Reconstruct the balanced assignment expression rather than inspecting the
  # changed literal without its retained key.
  for (my $start = $index; $start >= 0; --$start) {
    next unless is_sensitive_assignment_call_start($lines[$start]);
    my $end = call_end($start);
    next unless defined $end && $end >= $index;
    emit($line_number, @lines[$start .. $end]);
    last;
  }

  # A changed line inside a multiline map/property call must be evaluated with
  # the entire post-image call, including unchanged key/value siblings. Search
  # backwards without a fixed context limit; only emit a call whose balanced
  # parentheses actually contain this changed line.
  for (my $start = $index; $start >= 0; --$start) {
    next unless is_key_value_method_start($lines[$start]);
    my $end = call_end($start);
    next unless defined $end && $end >= $index;
    emit($line_number, @lines[$start .. $end]);
    last;
  }
}
PERL
  then
    echo "ERROR: unable to derive logical records from the post-image" >&2
    return 2
  fi
}

# scan_git_diff <label-prefix> <post-image-revision> <git-diff-arguments...>
# Scans additions in the selected Git diff. File enumeration is NUL-delimited
# because Git permits non-ASCII and newline-containing paths. `--text` and the
# disabled external/textconv drivers prevent repository attributes from hiding
# content that the gate must inspect.
scan_git_diff() {
  local label_prefix="$1" post_image_revision="$2" status old_path new_path f
  local records paths added_lines post_image post_image_spec
  local -a diff_paths
  local scan_status error_message file_index label
  shift 2
  paths="$(mktemp)" || {
    echo "ERROR: unable to allocate a temporary file for diff enumeration" >&2
    return 2
  }
  # Do not use process substitution here: its producer's exit status is not
  # observable by the loop. A bad range must fail closed, not look empty.
  # `--name-status -z` retains both sides of R/C entries. Rendering a rename
  # with only its new path loses the pairing and makes Git describe every old
  # line as an addition. Keep both pathspecs for the diff, but scan/read only
  # the new post-image path. `-M` makes that protocol independent of user Git
  # configuration.
  if ! git diff --text --no-ext-diff --no-textconv -M --name-status -z --diff-filter=ACMRT "$@" > "$paths" 2>/dev/null; then
    rm -f "$paths"
    echo "ERROR: unable to enumerate the Git diff" >&2
    return 2
  fi
  scan_status=0
  error_message=""
  file_index=0
  while IFS= read -r -d '' status; do
    old_path=""
    new_path=""
    case "$status" in
      R*|C*)
        if ! IFS= read -r -d '' old_path || ! IFS= read -r -d '' new_path; then
          scan_status=2
          error_message="ERROR: malformed rename/copy entry in the Git diff"
          break
        fi
        ;;
      A|M|T)
        if ! IFS= read -r -d '' new_path; then
          scan_status=2
          error_message="ERROR: malformed file entry in the Git diff"
          break
        fi
        ;;
      *)
        scan_status=2
        error_message="ERROR: unsupported file status in the Git diff"
        break
        ;;
    esac
    [ -z "$new_path" ] && {
      scan_status=2
      error_message="ERROR: empty post-image path in the Git diff"
      break
    }
    f="$new_path"
    file_index=$((file_index + 1))
    label="${label_prefix}file #${file_index}"
    if ! scan_path "$label" "$f"; then
      scan_status=2
      error_message="ERROR: unable to scan a Git path"
      break
    fi
    added_lines="$(mktemp)" || {
      scan_status=2
      error_message="ERROR: unable to allocate a temporary file for added-line rendering"
      break
    }
    if [ -n "$old_path" ]; then
      diff_paths=("$old_path" "$new_path")
    else
      diff_paths=("$new_path")
    fi
    if ! git diff --text --no-ext-diff --no-textconv -M --unified=0 --inter-hunk-context=0 --no-color "$@" -- "${diff_paths[@]}" 2>/dev/null |
      perl -ne '
        if (/^diff --git /) { $in_hunk = 0; next; }
        # Match only the fixed range fields. Function context after the second
        # @@ is arbitrary source text and may itself contain a fake "+123 @@".
        if (/^@@ -[0-9]+(?:,[0-9]+)? \+([0-9]+)(?:,[0-9]+)? @@/) {
          $new_line = $1 - 1;
          $in_hunk = 1;
          next;
        }
        if ($in_hunk && /^\+/) {
          $new_line++;
          print "$new_line\n";
          next;
        }
        if ($in_hunk && /^ /) {
          $new_line++;
        }
      ' > "$added_lines"; then
      rm -f "$added_lines"
      scan_status=2
      error_message="ERROR: unable to render changed line numbers"
      break
    fi
    if [ -s "$added_lines" ]; then
      post_image="$(mktemp)" || {
        rm -f "$added_lines"
        scan_status=2
        error_message="ERROR: unable to allocate a temporary post-image file"
        break
      }
      if [ "$post_image_revision" = ":" ]; then
        post_image_spec=":${f}"
      else
        post_image_spec="${post_image_revision}:${f}"
      fi
      if ! git show --no-textconv "$post_image_spec" > "$post_image" 2>/dev/null; then
        rm -f "$added_lines" "$post_image"
        scan_status=2
        error_message="ERROR: unable to read the Git post-image"
        break
      fi
      records="$(mktemp)" || {
        rm -f "$added_lines" "$post_image"
        scan_status=2
        error_message="ERROR: unable to allocate a temporary logical-record file"
        break
      }
      if ! render_post_image_records "$added_lines" "$post_image" "$records"; then
        rm -f "$added_lines" "$post_image" "$records"
        scan_status=2
        error_message="ERROR: unable to derive post-image logical records"
        break
      fi
      if ! scan_records "$label" "$records"; then
        rm -f "$added_lines" "$post_image" "$records"
        scan_status=2
        error_message="ERROR: unable to scan post-image logical records"
        break
      fi
      rm -f "$post_image" "$records"
    fi
    rm -f "$added_lines"
  done < "$paths"
  rm -f "$paths"
  if [ "$scan_status" -ne 0 ]; then
    echo "$error_message" >&2
    return "$scan_status"
  fi
}

is_full_sha() {
  # GitHub Actions passes immutable SHA-1 commit IDs here. Restricting the
  # public CLI to those IDs avoids treating a caller-supplied revision as an
  # option or broad revision expression.
  [[ "$1" =~ ^[0-9a-fA-F]{40}$ ]]
}

scan_committed_diff() { # base-sha [head-sha]
  local base="$1" head="${2:-HEAD}" base_commit head_commit repository_root
  if ! is_full_sha "$base" || { [ "$head" != "HEAD" ] && ! is_full_sha "$head"; }; then
    echo "Usage: scripts/scan-staged-sensitive-data.sh --diff <base-sha> [head-sha]" >&2
    return 2
  fi
  repository_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "ERROR: unable to determine repository root for committed scanning" >&2
    return 2
  }
  if ! cd "$repository_root"; then
    echo "ERROR: unable to enter repository root for committed scanning" >&2
    return 2
  fi
  base_commit="$(git rev-parse --verify --quiet "$base^{commit}")" || {
    echo "ERROR: diff base is not a reachable commit: $base" >&2
    return 2
  }
  head_commit="$(git rev-parse --verify --quiet "$head^{commit}")" || {
    echo "ERROR: diff head is not a reachable commit: $head" >&2
    return 2
  }
  if ! git merge-base "$base_commit" "$head_commit" >/dev/null 2>&1; then
    echo "ERROR: committed diff has no merge base" >&2
    return 2
  fi
  scan_git_diff "committed: " "$head_commit" "${base_commit}...${head_commit}"
}

self_test_case() { # label path content expected-rule expected-exit
  local label="$1" path="$2" content="$3" expected_rule="$4" expected_exit="$5"
  local repo output status
  repo="$SELF_TEST_ROOT/$label"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config core.quotePath true || return 1
  printf '%s\n' "$content" > "$repo/$path" || return 1
  git -C "$repo" add -- "$path" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne "$expected_exit" ]; then
    echo "SELF-TEST ERROR: $label exited $status (expected $expected_exit)" >&2
    return 1
  fi
  if [ -n "$expected_rule" ] && ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($expected_rule) in staged:"; then
    echo "SELF-TEST ERROR: $label did not report $expected_rule" >&2
    return 1
  fi
}

self_test_path_case() { # label path expected-rule
  local label="$1" path="$2" expected_rule="$3" repo output status
  repo="$SELF_TEST_ROOT/$label"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  printf '%s\n' "harmless" > "$repo/$path" || return 1
  git -C "$repo" add -- "$path" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($expected_rule) in staged:"; then
    echo "SELF-TEST ERROR: $label path did not report $expected_rule" >&2
    return 1
  fi
  if printf '%s\n' "$output" | grep -Fq -- "$path"; then
    echo "SELF-TEST ERROR: $label disclosed the sensitive path" >&2
    return 1
  fi
}

self_test_artifact_path_case() { # label path expected-rule
  local label="$1" path="$2" expected_rule="$3" repo artifact_dir output status
  repo="$SELF_TEST_ROOT/$label"
  artifact_dir="$repo/artifacts"
  mkdir -p "$artifact_dir" || return 1
  printf '%s\n' "harmless" > "$artifact_dir/$path" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" "$artifact_dir" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($expected_rule) in artifact file #"; then
    echo "SELF-TEST ERROR: $label artifact path did not report $expected_rule" >&2
    return 1
  fi
  if printf '%s\n' "$output" | grep -Fq -- "$path"; then
    echo "SELF-TEST ERROR: $label disclosed the sensitive artifact path" >&2
    return 1
  fi
}

self_test_artifact_content_case() { # label path content expected-rule
  local label="$1" path="$2" content="$3" expected_rule="$4"
  local repo artifact_dir output status
  repo="$SELF_TEST_ROOT/$label"
  artifact_dir="$repo/artifacts"
  mkdir -p "$artifact_dir" || return 1
  printf '%s\n' "$content" > "$artifact_dir/$path" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" "$artifact_dir" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($expected_rule) in artifact file #"; then
    echo "SELF-TEST ERROR: $label artifact content did not report $expected_rule" >&2
    return 1
  fi
}

self_test_artifact_find_failure() {
  local repo artifact_dir fake_bin output status
  repo="$SELF_TEST_ROOT/artifact-find-failure"
  artifact_dir="$repo/artifacts"
  fake_bin="$repo/fake-bin"
  mkdir -p "$artifact_dir" "$fake_bin" || return 1
  printf '%s\n' '#!/usr/bin/env bash' 'exit 9' > "$fake_bin/find" || return 1
  chmod +x "$fake_bin/find" || return 1

  output="$(cd "$repo" && PATH="$fake_bin:$PATH" bash "$SELF_PATH" "$artifact_dir" 2>&1)"
  status=$?
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "ERROR: unable to enumerate artifact files"; then
    echo "SELF-TEST ERROR: artifact find failure did not fail closed" >&2
    return 1
  fi
}

self_test_artifact_argument_failure() {
  local repo artifact_file artifact_dir output status
  repo="$SELF_TEST_ROOT/artifact-argument-failure"
  artifact_file="$repo/not-a-directory.txt"
  artifact_dir="$repo/artifacts"
  mkdir -p "$artifact_dir" || return 1
  printf '%s\n' "harmless" > "$artifact_file" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" "$artifact_file" 2>&1)"
  status=$?
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "ERROR: artifact target must be an existing directory"; then
    echo "SELF-TEST ERROR: artifact file argument did not fail closed" >&2
    return 1
  fi
  output="$(cd "$repo" && bash "$SELF_PATH" "$repo/missing" 2>&1)"
  status=$?
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "ERROR: artifact target must be an existing directory"; then
    echo "SELF-TEST ERROR: missing artifact argument did not fail closed" >&2
    return 1
  fi
  output="$(cd "$repo" && bash "$SELF_PATH" "$artifact_dir" extra 2>&1)"
  status=$?
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "Usage:"; then
    echo "SELF-TEST ERROR: extra artifact argument did not fail closed" >&2
    return 1
  fi
}

self_test_artifact_symlink_failure() {
  local repo artifact_dir target_file artifact_link root_link output status
  repo="$SELF_TEST_ROOT/artifact-symlink-failure"
  artifact_dir="$repo/artifacts"
  target_file="$repo/target.bin"
  artifact_link="$artifact_dir/link.bin"
  root_link="$repo/artifact-root-link"
  mkdir -p "$artifact_dir" || return 1
  printf '%s\n' "harmless" > "$target_file" || return 1
  ln -s "$target_file" "$artifact_link" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" "$artifact_dir" 2>&1)"
  status=$?
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "ERROR: artifact symlinks are not permitted"; then
    echo "SELF-TEST ERROR: nested artifact symlink did not fail closed" >&2
    return 1
  fi
  ln -s "$artifact_dir" "$root_link" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" "$root_link" 2>&1)"
  status=$?
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "ERROR: artifact symlinks are not permitted"; then
    echo "SELF-TEST ERROR: artifact root symlink did not fail closed" >&2
    return 1
  fi
}

self_test_unreadable_artifact_failure() {
  local repo artifact_dir artifact_file output status
  repo="$SELF_TEST_ROOT/unreadable-artifact"
  artifact_dir="$repo/artifacts"
  artifact_file="$artifact_dir/evidence.bin"
  mkdir -p "$artifact_dir" || return 1
  printf '%s\n' "harmless" > "$artifact_file" || return 1
  chmod a-r "$artifact_file" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" "$artifact_dir" 2>&1)"
  status=$?
  chmod u+r "$artifact_file" || return 1
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "ERROR: artifact file is not readable"; then
    echo "SELF-TEST ERROR: unreadable artifact did not fail closed" >&2
    return 1
  fi
}

self_test_diff_case() { # label path content expected-rule expected-exit
  local label="$1" path="$2" content="$3" expected_rule="$4" expected_exit="$5"
  local repo base head output status
  repo="$SELF_TEST_ROOT/$label"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config core.quotePath true || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  printf '%s\n' "baseline" > "$repo/baseline.md" || return 1
  git -C "$repo" add -- baseline.md || return 1
  git -C "$repo" commit -q -m "baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1
  printf '%s\n' "$content" > "$repo/$path" || return 1
  git -C "$repo" add -- "$path" || return 1
  git -C "$repo" commit -q -m "scan fixture" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne "$expected_exit" ]; then
    echo "SELF-TEST ERROR: $label exited $status (expected $expected_exit)" >&2
    return 1
  fi
  if [ -n "$expected_rule" ] && ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($expected_rule) in committed:"; then
    echo "SELF-TEST ERROR: $label did not report $expected_rule in committed diff" >&2
    return 1
  fi
}

self_test_hunk_context_case() {
  local repo base head output status fixture_key hunk_context
  repo="$SELF_TEST_ROOT/hunk-context"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  git -C "$repo" config diff.fixture.xfuncname '.*' || return 1
  fixture_key="TO"
  fixture_key="${fixture_key}KEN"
  hunk_context="function marker +777 @"
  hunk_context="${hunk_context}@"
  printf '%s\n' '*.fixture diff=fixture' > "$repo/.gitattributes" || return 1
  printf '%s\n' "$hunk_context" "${fixture_key}=\${SAFE}" > "$repo/config.fixture" || return 1
  git -C "$repo" add .gitattributes config.fixture || return 1
  git -C "$repo" commit -q -m "hunk context baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1
  printf '%s\n' "$hunk_context" "${fixture_key}=not-a-real-value" > "$repo/config.fixture" || return 1
  git -C "$repo" add config.fixture || return 1
  git -C "$repo" commit -q -m "hunk context fixture" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in committed:"; then
    echo "SELF-TEST ERROR: hunk context bypassed the committed scanner" >&2
    return 1
  fi
}

self_test_inter_hunk_context_case() {
  local repo base head output status fixture_key
  repo="$SELF_TEST_ROOT/inter-hunk-context"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  git -C "$repo" config diff.interHunkContext 20 || return 1
  fixture_key="TO"
  fixture_key="${fixture_key}KEN"
  printf '%s\n' \
    "old-one" "keep-2" "keep-3" "keep-4" "keep-5" \
    "keep-6" "keep-7" "keep-8" "keep-9" "old-ten" \
    > "$repo/config.txt" || return 1
  git -C "$repo" add -- config.txt || return 1
  git -C "$repo" commit -q -m "inter-hunk baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1

  printf '%s\n' \
    "new-one" "keep-2" "keep-3" "keep-4" "keep-5" \
    "keep-6" "keep-7" "keep-8" "keep-9" \
    "${fixture_key}=not-a-real-value" \
    > "$repo/config.txt" || return 1
  git -C "$repo" add -- config.txt || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in staged:"; then
    echo "SELF-TEST ERROR: retained inter-hunk context bypassed the staged scanner" >&2
    return 1
  fi

  git -C "$repo" commit -q -m "inter-hunk fixture" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in committed:"; then
    echo "SELF-TEST ERROR: retained inter-hunk context bypassed the committed scanner" >&2
    return 1
  fi
}

self_test_nested_cwd_diff_case() {
  local repo nested base head output status fixture_key
  repo="$SELF_TEST_ROOT/nested-cwd-diff"
  nested="$repo/nested/directory"
  mkdir -p "$nested" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  fixture_key="TO"
  fixture_key="${fixture_key}KEN"
  printf '%s\n' "baseline" > "$repo/baseline.md" || return 1
  git -C "$repo" add baseline.md || return 1
  git -C "$repo" commit -q -m "nested cwd baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1
  printf '%s\n' "${fixture_key}=not-a-real-value" > "$repo/root-credential.md" || return 1
  git -C "$repo" add root-credential.md || return 1
  git -C "$repo" commit -q -m "nested cwd fixture" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1

  output="$(cd "$nested" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in committed:"; then
    echo "SELF-TEST ERROR: nested CWD bypassed the committed scanner" >&2
    return 1
  fi
}

self_test_nested_cwd_staged_case() {
  local repo nested output status fixture_key
  repo="$SELF_TEST_ROOT/nested-cwd-staged"
  nested="$repo/nested/directory"
  mkdir -p "$nested" || return 1
  git -C "$repo" init -q || return 1
  fixture_key="TO"
  fixture_key="${fixture_key}KEN"
  printf '%s\n' "${fixture_key}=not-a-real-value" \
    > "$repo/root-credential.md" || return 1
  git -C "$repo" add -- root-credential.md || return 1

  output="$(cd "$nested" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in staged:"; then
    echo "SELF-TEST ERROR: nested CWD bypassed the staged scanner" >&2
    return 1
  fi
}

self_test_staged_and_committed_case() { # label path content expected-rule
  local label="$1" path="$2" content="$3" expected_rule="$4"
  self_test_case "${label}-staged" "$path" "$content" "$expected_rule" 1 || return 1
  self_test_diff_case "${label}-committed" "$path" "$content" "$expected_rule" 1
}

self_test_multiline_method_replacement_case() { # label before after
  local label="$1" before="$2" after="$3" repo base head output status
  repo="$SELF_TEST_ROOT/$label"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  printf '%s\n' "$before" > "$repo/config.kt" || return 1
  git -C "$repo" add -- config.kt || return 1
  git -C "$repo" commit -q -m "indirect baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1

  printf '%s\n' "$after" > "$repo/config.kt" || return 1
  git -C "$repo" add -- config.kt || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in staged:"; then
    echo "SELF-TEST ERROR: $label bypassed the staged scanner" >&2
    return 1
  fi

  git -C "$repo" commit -q -m "replace indirect method value" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in committed:"; then
    echo "SELF-TEST ERROR: $label bypassed the committed scanner" >&2
    return 1
  fi
}

self_test_multiline_replacement_case() { # label retained-context
  local label="$1" retained_context="$2"
  local repo base head output status key_name
  repo="$SELF_TEST_ROOT/$label"
  key_name="TO"
  key_name="${key_name}KEN"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  printf 'val %s =\n%s  getenv("TOKEN")\n' \
    "$key_name" "$retained_context" > "$repo/config.kt" || return 1
  git -C "$repo" add -- config.kt || return 1
  git -C "$repo" commit -q -m "baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1

  printf 'val %s =\n%s  "not-a-real-value"\n' \
    "$key_name" "$retained_context" > "$repo/config.kt" || return 1
  git -C "$repo" add -- config.kt || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in staged:"; then
    echo "SELF-TEST ERROR: multiline replacement bypassed the staged scanner" >&2
    return 1
  fi

  git -C "$repo" commit -q -m "replace indirect value" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in committed:"; then
    echo "SELF-TEST ERROR: multiline replacement bypassed the committed scanner" >&2
    return 1
  fi
}

self_test_default_git_failure() {
  local repo fake_bin output status
  repo="$SELF_TEST_ROOT/default-git-failure"
  fake_bin="$repo/fake-bin"
  mkdir -p "$fake_bin" || return 1
  printf '%s\n' '#!/usr/bin/env bash' 'exit 9' > "$fake_bin/git" || return 1
  chmod +x "$fake_bin/git" || return 1

  output="$(cd "$repo" && PATH="$fake_bin:$PATH" bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "ERROR: unable to determine repository state"; then
    echo "SELF-TEST ERROR: default Git failure did not fail closed" >&2
    return 1
  fi
}

self_test_nondiff_case() { # content
  local content="$1" repo base head output status
  repo="$SELF_TEST_ROOT/nondiff-attribute"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  printf '%s\n' "baseline" > "$repo/baseline.md" || return 1
  git -C "$repo" add -- baseline.md || return 1
  git -C "$repo" commit -q -m "baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1

  printf '%s\n' "credential.txt -diff" > "$repo/.gitattributes" || return 1
  printf '%s\n' "$content" > "$repo/credential.txt" || return 1
  git -C "$repo" add -- .gitattributes credential.txt || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in staged:"; then
    echo "SELF-TEST ERROR: -diff attribute bypassed the staged scanner" >&2
    return 1
  fi

  git -C "$repo" commit -q -m "non-diffable credential fixture" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in committed:"; then
    echo "SELF-TEST ERROR: -diff attribute bypassed the committed scanner" >&2
    return 1
  fi
}

self_test_type_change_case() { # content
  local content="$1" repo base head output status
  repo="$SELF_TEST_ROOT/type-change"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  ln -s "harmless-target" "$repo/credential.txt" || return 1
  git -C "$repo" add -- credential.txt || return 1
  git -C "$repo" commit -q -m "symlink baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1

  rm -f "$repo/credential.txt" || return 1
  printf '%s\n' "$content" > "$repo/credential.txt" || return 1
  git -C "$repo" add -- credential.txt || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in staged:"; then
    echo "SELF-TEST ERROR: type change bypassed the staged scanner" >&2
    return 1
  fi

  git -C "$repo" commit -q -m "regular credential fixture" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in committed:"; then
    echo "SELF-TEST ERROR: type change bypassed the committed scanner" >&2
    return 1
  fi
}

self_test_rename_case() { # label baseline post-image expected-rule expected-exit
  local label="$1" baseline="$2" post_image="$3" expected_rule="$4" expected_exit="$5"
  local repo base head output status old_path new_path
  repo="$SELF_TEST_ROOT/$label"
  old_path="original.md"
  new_path=$'renamed\npolicy.md'
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config core.quotePath true || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  printf '%s\n' "$baseline" > "$repo/$old_path" || return 1
  git -C "$repo" add -- "$old_path" || return 1
  git -C "$repo" commit -q -m "rename baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1

  git -C "$repo" mv -- "$old_path" "$new_path" || return 1
  printf '%s\n' "$post_image" > "$repo/$new_path" || return 1
  git -C "$repo" add -- "$new_path" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne "$expected_exit" ]; then
    echo "SELF-TEST ERROR: $label staged rename exited $status (expected $expected_exit)" >&2
    return 1
  fi
  if [ -n "$expected_rule" ] && ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($expected_rule) in staged:"; then
    echo "SELF-TEST ERROR: $label staged rename did not report $expected_rule" >&2
    return 1
  fi

  git -C "$repo" commit -q -m "rename fixture" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne "$expected_exit" ]; then
    echo "SELF-TEST ERROR: $label committed rename exited $status (expected $expected_exit)" >&2
    return 1
  fi
  if [ -n "$expected_rule" ] && ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($expected_rule) in committed:"; then
    echo "SELF-TEST ERROR: $label committed rename did not report $expected_rule" >&2
    return 1
  fi
}

self_test_nul_case() { # content secondary-rule
  local content="$1" secondary_rule="$2" repo base head output status
  repo="$SELF_TEST_ROOT/nul-byte"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  printf '%s\n' "baseline" > "$repo/baseline.md" || return 1
  git -C "$repo" add -- baseline.md || return 1
  git -C "$repo" commit -q -m "baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1

  printf 'prefix\0%s\n' "$content" > "$repo/credential.bin" || return 1
  git -C "$repo" add -- credential.bin || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in staged:"; then
    echo "SELF-TEST ERROR: NUL byte bypassed the staged scanner" >&2
    return 1
  fi
  if ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($secondary_rule) in staged:"; then
    echo "SELF-TEST ERROR: NUL byte bypassed the staged $secondary_rule rule" >&2
    return 1
  fi

  git -C "$repo" commit -q -m "binary credential fixture" || return 1
  head="$(git -C "$repo" rev-parse HEAD)" || return 1
  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$head" 2>&1)"
  status=$?
  if [ "$status" -ne 1 ] || ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret (secret) in committed:"; then
    echo "SELF-TEST ERROR: NUL byte bypassed the committed scanner" >&2
    return 1
  fi
  if ! printf '%s\n' "$output" | grep -Fq "Possible PII or secret ($secondary_rule) in committed:"; then
    echo "SELF-TEST ERROR: NUL byte bypassed the committed $secondary_rule rule" >&2
    return 1
  fi
}

self_test_unrelated_diff() {
  local repo base tree unrelated output status
  repo="$SELF_TEST_ROOT/unrelated-commits"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  git -C "$repo" config user.name "scanner self-test" || return 1
  git -C "$repo" config user.email "scanner-self-test@example.invalid" || return 1
  printf '%s\n' "baseline" > "$repo/baseline.md" || return 1
  git -C "$repo" add -- baseline.md || return 1
  git -C "$repo" commit -q -m "baseline" || return 1
  base="$(git -C "$repo" rev-parse HEAD)" || return 1
  tree="$(git -C "$repo" mktree < /dev/null)" || return 1
  unrelated="$(printf '%s\n' "unrelated" | git -C "$repo" commit-tree "$tree")" || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" --diff "$base" "$unrelated" 2>&1)"
  status=$?
  if [ "$status" -ne 2 ] || ! printf '%s\n' "$output" | grep -Fq "ERROR: committed diff has no merge base"; then
    echo "SELF-TEST ERROR: unrelated commits did not fail closed" >&2
    return 1
  fi
}

self_test_own_source() {
  local repo output status
  repo="$SELF_TEST_ROOT/scanner-source"
  mkdir -p "$repo" || return 1
  git -C "$repo" init -q || return 1
  cp "$SELF_PATH" "$repo/scanner-source.sh" || return 1
  git -C "$repo" add -- scanner-source.sh || return 1

  output="$(cd "$repo" && bash "$SELF_PATH" 2>&1)"
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "SELF-TEST ERROR: scanner source triggered its own rules" >&2
    return 1
  fi
}

self_test() {
  local github_fixture gho_fixture ghu_fixture ghs_fixture ghr_fixture
  local aws_fixture asia_fixture slack_fixture aws_placeholder uppercase_name other_name
  local pwd_label client_label oidc_label indirect_values identity_path
  local split_identity_path assignment_path double_quote encoded_key
  local identity_fixture sha256_fixture short_hex_identity
  local padded_sha1_identity padded_sha256_identity
  local quoted_json_indirect quoted_json_literal encoded_json_literal
  local identifier_quote private_label checkout_auth_label
  local multiline_put multiline_put_comment multiline_put_named
  local multiline_put_if_absent multiline_set_property multiline_set
  local multiline_of multiline_pair
  local replace_put_if_absent_before replace_put_if_absent_after
  local replace_put_named_before replace_put_named_after
  local replace_put_comment_before replace_put_comment_after
  local wrapper_assignment_before wrapper_assignment_after
  local rename_baseline rename_header_change rename_sensitive_change
  SELF_TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/scan-staged-sensitive-data.XXXXXX")" || return 1
  SELF_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  trap 'rm -rf "$SELF_TEST_ROOT"' EXIT

  # Build token-shaped fixtures at runtime: the scanner must not block the
  # source file that contains its own regression tests.
  github_fixture="ghp_"
  github_fixture="${github_fixture}ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  gho_fixture="gho_"
  gho_fixture="${gho_fixture}ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  ghu_fixture="ghu_"
  ghu_fixture="${ghu_fixture}ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  ghs_fixture="ghs_"
  ghs_fixture="${ghs_fixture}ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  ghr_fixture="ghr_"
  ghr_fixture="${ghr_fixture}ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  aws_fixture="AKIA"
  aws_fixture="${aws_fixture}1234567890ABCDEF"
  asia_fixture="ASIA"
  asia_fixture="${asia_fixture}1234567890ABCDEF"
  slack_fixture="xoxb-"
  slack_fixture="${slack_fixture}1234567890-1234567890-abcdefghijklmnopqrstuvwxyz123456"
  aws_placeholder="AKIAIOSFODNN"
  aws_placeholder="${aws_placeholder}7EXAMPLE"
  uppercase_name="API"
  uppercase_name="${uppercase_name}_KEY"
  other_name="TO"
  other_name="${other_name}KEN"
  pwd_label="PASS"
  pwd_label="${pwd_label}WORD"
  client_label="CLIENT_"
  client_label="${client_label}SECRET"
  oidc_label="id-"
  oidc_label="${oidc_label}token"
  double_quote='"'
  encoded_key="to"
  encoded_key="${encoded_key}\\u006ben"
  identifier_quote='`'
  private_label="private"
  private_label="${private_label}Key"
  checkout_auth_label="persist-cred"
  checkout_auth_label="${checkout_auth_label}entials"
  quoted_json_indirect="${double_quote}${other_name}${double_quote}: ${double_quote}\${API_TOKEN}${double_quote}"
  quoted_json_literal="${double_quote}${other_name}${double_quote}: ${double_quote}not-a-real-value${double_quote}"
  encoded_json_literal="${double_quote}${encoded_key}${double_quote}: ${double_quote}not-a-real-value${double_quote}"
  indirect_values="${uppercase_name}: \${API_KEY}"$'\n'
  indirect_values+="${pwd_label}: \${?DB_PASSWORD}"$'\n'
  indirect_values+="${other_name}=getenv(\"TOKEN\")"$'\n'
  indirect_values+="${pwd_label}: valueFrom"$'\n'
  indirect_values+="${client_label}: env.config.property(\"client.secret\").getString()"$'\n'
  indirect_values+="${oidc_label}: write"$'\n'
  indirect_values+="${checkout_auth_label}: false"$'\n'
  indirect_values+="{$quoted_json_indirect}"$'\n'
  indirect_values+="mapOf(${double_quote}${other_name}${double_quote} to getenv(${double_quote}TOKEN${double_quote}))"$'\n'
  indirect_values+="[${double_quote}${other_name}${double_quote}]: ${double_quote}\${API_TOKEN}${double_quote}"$'\n'
  indirect_values+="pairs.put(${double_quote}${other_name}${double_quote}, getenv(${double_quote}TOKEN${double_quote}))"$'\n'
  indirect_values+="mapOf(${double_quote}${other_name}${double_quote} /* key */ to getenv(${double_quote}TOKEN${double_quote}))"$'\n'
  indirect_values+="pairs.put(key = ${double_quote}${other_name}${double_quote}, value = getenv(${double_quote}TOKEN${double_quote}))"
  multiline_put="pairs.put("$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  multiline_put_comment="pairs.put("$'\n'"  /* retained (parentheses) */"$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  multiline_put_named="pairs.put("$'\n'"  key = ${double_quote}${other_name}${double_quote},"$'\n'"  value = ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  multiline_put_if_absent="pairs.putIfAbsent("$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  multiline_set_property="System.setProperty("$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'");"
  multiline_set="headers.set("$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  multiline_of="Map.of("$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  multiline_pair="Pair("$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  replace_put_if_absent_before="pairs.putIfAbsent("$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  getenv(${double_quote}TOKEN${double_quote})"$'\n'")"
  replace_put_if_absent_after="pairs.putIfAbsent("$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  replace_put_named_before="pairs.put("$'\n'"  key = ${double_quote}${other_name}${double_quote},"$'\n'"  value = getenv(${double_quote}TOKEN${double_quote})"$'\n'")"
  replace_put_named_after="pairs.put("$'\n'"  key = ${double_quote}${other_name}${double_quote},"$'\n'"  value = ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  replace_put_comment_before="pairs.put("$'\n'"  /* retained (parentheses) */"$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  getenv(${double_quote}TOKEN${double_quote})"$'\n'")"
  replace_put_comment_after="pairs.put("$'\n'"  /* retained (parentheses) */"$'\n'"  ${double_quote}${other_name}${double_quote},"$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  wrapper_assignment_before="val ${other_name} = decode("$'\n'"  getenv(${double_quote}${other_name}${double_quote})"$'\n'")"
  wrapper_assignment_after="val ${other_name} = decode("$'\n'"  ${double_quote}not-a-real-value${double_quote}"$'\n'")"
  rename_baseline=$'header: original\nsection: one\nsection: two\nsection: three\nsection: four\nsection: five\nsection: six\nsection: seven\nsection: eight\n'
  rename_baseline+="${other_name}=not-a-real-value"
  rename_header_change=$'header: renamed\nsection: one\nsection: two\nsection: three\nsection: four\nsection: five\nsection: six\nsection: seven\nsection: eight\n'
  rename_header_change+="${other_name}=not-a-real-value"
  rename_sensitive_change=$'header: renamed\nsection: one\nsection: two\nsection: three\nsection: four\nsection: five\nsection: six\nsection: seven\nsection: eight\n'
  rename_sensitive_change+="${other_name}=changed-not-a-real-value"
  identity_path="12345"
  identity_path="${identity_path}678901-sensitive.md"
  split_identity_path="12345"
  split_identity_path="${split_identity_path}"$'\n'"678901-sensitive.md"
  assignment_path="${uppercase_name}=\${SAFE} # literal.md"
  identity_fixture="12345"
  identity_fixture="${identity_fixture}678901"
  sha256_fixture="88f95534684957ec406db8ce1153058f9fc65c23e145ddec40454b6a6512b1cf"
  short_hex_identity="a${identity_fixture}f"
  padded_sha1_identity="$(printf 'a%.0s' {1..10})${identity_fixture}$(printf 'b%.0s' {1..19})"
  padded_sha256_identity="$(printf 'a%.0s' {1..20})${identity_fixture}$(printf 'b%.0s' {1..33})"

  self_test_case "standalone-identity-number" "identity.md" "$identity_fixture" "identity-number" 1 || return 1
  self_test_case "allowlisted-sha256-technical-identifier" "sha256.md" "$sha256_fixture" "" 0 || return 1
  self_test_case "short-hex-wrapped-identity" "short-hex.md" "$short_hex_identity" "identity-number" 1 || return 1
  self_test_case "sha1-padded-identity" "sha1.md" "$padded_sha1_identity" "identity-number" 1 || return 1
  self_test_case "sha256-padded-identity" "sha256-padded.md" "$padded_sha256_identity" "identity-number" 1 || return 1
  self_test_diff_case "committed-allowlisted-sha256-technical-identifier" "sha256.md" "$sha256_fixture" "" 0 || return 1
  self_test_diff_case "committed-sha1-padded-identity" "sha1.md" "$padded_sha1_identity" "identity-number" 1 || return 1
  self_test_diff_case "committed-sha256-padded-identity" "sha256-padded.md" "$padded_sha256_identity" "identity-number" 1 || return 1
  self_test_case "uppercase-secret" "UPPERCASE.md" "${uppercase_name}=not-a-real-secret" "secret" 1 || return 1
  self_test_case "environment-suffixed-password" "password-prod.md" "${pwd_label}_PROD=not-a-real-value" "secret" 1 || return 1
  self_test_case "environment-suffixed-token" "token-staging.md" "${other_name}_STAGING=not-a-real-value" "secret" 1 || return 1
  self_test_staged_and_committed_case "camel-environment-suffixed-password" "password-prod.kt" "${pwd_label}Prod=not-a-real-value" "secret" || return 1
  self_test_staged_and_committed_case "camel-environment-suffixed-token" "token-staging.kt" "${other_name}Staging=not-a-real-value" "secret" || return 1
  self_test_case "allowlist-word-in-comment" "comment.md" "${uppercase_name}=not-a-real-secret # vault migration" "secret" 1 || return 1
  self_test_case "patch-header-shaped-line" "patch-header.md" "++ ${other_name}=not-a-real-token" "secret" 1 || return 1
  self_test_case "indirect-secret-values" "indirect.md" "$indirect_values" "" 0 || return 1
  self_test_case "env-expansion-literal-suffix" "env-suffix.md" "${uppercase_name}: \${API_KEY}literal" "secret" 1 || return 1
  self_test_case "hocon-expansion-literal-suffix" "hocon-suffix.md" "${pwd_label}: \${?DB_PASSWORD}literal" "secret" 1 || return 1
  self_test_case "getenv-literal-suffix" "getenv-suffix.md" "${other_name}=getenv(\"TOKEN\")literal" "secret" 1 || return 1
  self_test_case "value-from-literal-suffix" "value-from-suffix.md" "${pwd_label}: valueFromLiteral" "secret" 1 || return 1
  self_test_case "property-literal-suffix" "property-suffix.md" "${client_label}: env.config.property(\"client.secret\").getString()literal" "secret" 1 || return 1
  self_test_case "vault-uri-is-not-exempt" "vault-suffix.md" "${client_label}: vault://team/service-literal" "secret" 1 || return 1
  self_test_case "oidc-literal-suffix" "oidc-suffix.md" "${oidc_label}: write-literal" "secret" 1 || return 1
  self_test_case "checkout-auth-enabled" "checkout-auth.yaml" "${checkout_auth_label}: true" "secret" 1 || return 1
  self_test_case "env-expansion-comment-suffix" "env-comment.md" "${uppercase_name}: \${API_KEY} # literal value" "secret" 1 || return 1
  self_test_case "hocon-comment-suffix" "hocon-comment.md" "${pwd_label}: \${?DB_PASSWORD} # literal value" "secret" 1 || return 1
  self_test_case "getenv-comment-suffix" "getenv-comment.md" "${other_name}=getenv(\"TOKEN\") // literal value" "secret" 1 || return 1
  self_test_case "value-from-comment-suffix" "value-from-comment.md" "${pwd_label}: valueFrom # literal value" "secret" 1 || return 1
  self_test_case "property-comment-suffix" "property-comment.md" "${client_label}: env.config.property(\"client.secret\").getString() // literal value" "secret" 1 || return 1
  self_test_case "oidc-comment-suffix" "oidc-comment.md" "${oidc_label}: write # literal value" "secret" 1 || return 1
  self_test_case "quoted-json-literal" "quoted-json.md" "{$quoted_json_literal}" "secret" 1 || return 1
  self_test_case "encoded-json-key" "encoded-json.md" "{$encoded_json_literal}" "secret" 1 || return 1
  self_test_case "oidc-lookalike-key" "oidc-lookalike.md" "invalid-${other_name}: write" "secret" 1 || return 1
  self_test_case "sensitive-value-suffix-key" "value-suffix.md" "${other_name}Value=not-a-real-value" "secret" 1 || return 1
  self_test_case "api-key-value-suffix" "api-key-value.md" "${uppercase_name}Value=not-a-real-value" "secret" 1 || return 1
  self_test_case "numeric-sensitive-key" "numeric-key.md" "${other_name}1=not-a-real-value" "secret" 1 || return 1
  self_test_case "suffixed-numeric-sensitive-key" "suffixed-numeric-key.md" "${other_name}_value_2=not-a-real-value" "secret" 1 || return 1
  self_test_case "private-key" "private-key.md" "${private_label}=not-a-real-value" "secret" 1 || return 1
  self_test_case "backtick-sensitive-key" "backtick-key.kt" "val ${identifier_quote}${other_name}${identifier_quote} = ${double_quote}not-a-real-value${double_quote}" "secret" 1 || return 1
  self_test_case "unquoted-encoded-key" "encoded-key.java" "${encoded_key}=not-a-real-value" "secret" 1 || return 1
  self_test_case "kotlin-map-entry" "map-entry.kt" "val pairs = mapOf(${double_quote}${other_name}${double_quote} to ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "multiline-kotlin-map-entry" "multiline-map-entry.kt" "val pairs = mapOf("$'\n'"  ${double_quote}${other_name}${double_quote} to ${double_quote}not-a-real-value${double_quote},"$'\n'")" "secret" 1 || return 1
  self_test_case "computed-js-key" "computed-key.js" "const pairs = {[${double_quote}${other_name}${double_quote}]: ${double_quote}not-a-real-value${double_quote}}" "secret" 1 || return 1
  self_test_case "kotlin-put-entry" "put-entry.kt" "pairs.put(${double_quote}${other_name}${double_quote}, ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "java-set-property-entry" "set-property.java" "System.setProperty(${double_quote}${other_name}${double_quote}, ${double_quote}not-a-real-value${double_quote});" "secret" 1 || return 1
  self_test_case "java-map-of-entry" "map-of.java" "Map.of(${double_quote}${other_name}${double_quote}, ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "kotlin-pair-entry" "pair-entry.kt" "Pair(${double_quote}${other_name}${double_quote}, ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "commented-kotlin-map-entry" "commented-map-entry.kt" "mapOf(${double_quote}${other_name}${double_quote} /* key */ to ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "commented-put-entry" "commented-put-entry.kt" "values.put(${double_quote}${other_name}${double_quote} /* key */, ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "comment-before-put-parenthesis" "comment-before-put-parenthesis.kt" "values.put /* note */ (${double_quote}${other_name}${double_quote}, ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "comment-inside-put-parenthesis" "comment-inside-put-parenthesis.kt" "values.put(/* retained */ ${double_quote}${other_name}${double_quote}, ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "comment-after-named-key-equals" "comment-after-named-key-equals.kt" "put(key = /* retained */ ${double_quote}${other_name}${double_quote}, value = ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "encoded-method-name" "encoded-method-name.java" "pairs.p\\u0075t(${double_quote}${other_name}${double_quote}, ${double_quote}not-a-real-value${double_quote});" "secret" 1 || return 1
  self_test_case "commented-set-property-entry" "commented-set-property.java" "System.setProperty(${double_quote}${other_name}${double_quote} /* key */, ${double_quote}not-a-real-value${double_quote});" "secret" 1 || return 1
  self_test_case "named-put-entry" "named-put-entry.kt" "put(key = ${double_quote}${other_name}${double_quote}, value = ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "headers-set-entry" "headers-set-entry.kt" "headers.set(${double_quote}${other_name}${double_quote}, ${double_quote}not-a-real-value${double_quote})" "secret" 1 || return 1
  self_test_case "augmented-assignment" "augmented.sh" "${other_name}+=not-a-real-value" "secret" 1 || return 1
  self_test_case "logical-kotlin-suffix" "logical.kt" "val ${other_name} = System.getenv(\"TOKEN\")"$'\n'"  + \"-literal-suffix\"" "secret" 1 || return 1
  self_test_case "logical-shell-suffix" "logical.sh" "${other_name}=\${API_KEY}\\"$'\n'"literal-suffix" "secret" 1 || return 1
  self_test_case "logical-yaml-suffix" "logical.yaml" "${other_name}: \${API_KEY}"$'\n'"  literal-suffix" "secret" 1 || return 1
  self_test_staged_and_committed_case "multiline-put" "multiline-put.kt" "$multiline_put" "secret" || return 1
  self_test_staged_and_committed_case "multiline-put-comment" "multiline-put-comment.kt" "$multiline_put_comment" "secret" || return 1
  self_test_staged_and_committed_case "multiline-put-named" "multiline-put-named.kt" "$multiline_put_named" "secret" || return 1
  self_test_staged_and_committed_case "multiline-put-if-absent" "multiline-put-if-absent.kt" "$multiline_put_if_absent" "secret" || return 1
  self_test_staged_and_committed_case "multiline-set-property" "multiline-set-property.java" "$multiline_set_property" "secret" || return 1
  self_test_staged_and_committed_case "multiline-set" "multiline-set.kt" "$multiline_set" "secret" || return 1
  self_test_staged_and_committed_case "multiline-of" "multiline-of.java" "$multiline_of" "secret" || return 1
  self_test_staged_and_committed_case "multiline-pair" "multiline-pair.kt" "$multiline_pair" "secret" || return 1
  self_test_multiline_method_replacement_case \
    "multiline-put-if-absent-replacement" \
    "$replace_put_if_absent_before" \
    "$replace_put_if_absent_after" || return 1
  self_test_multiline_method_replacement_case \
    "multiline-put-named-replacement" \
    "$replace_put_named_before" \
    "$replace_put_named_after" || return 1
  self_test_multiline_method_replacement_case \
    "multiline-put-comment-replacement" \
    "$replace_put_comment_before" \
    "$replace_put_comment_after" || return 1
  self_test_multiline_method_replacement_case \
    "multiline-wrapper-assignment-replacement" \
    "$wrapper_assignment_before" \
    "$wrapper_assignment_after" || return 1
  self_test_rename_case \
    "rename-unchanged-secret" \
    "$rename_baseline" \
    "$rename_header_change" \
    "" 0 || return 1
  self_test_rename_case \
    "rename-changed-secret" \
    "$rename_baseline" \
    "$rename_sensitive_change" \
    "secret" 1 || return 1
  self_test_path_case "identity-in-path" "$identity_path" "identity-number" || return 1
  self_test_path_case "split-identity-in-path" "$split_identity_path" "identity-number" || return 1
  self_test_path_case "indirect-secret-assignment-in-path" "$assignment_path" "secret" || return 1
  self_test_artifact_path_case "identity-in-binary-artifact-path" "$identity_path.bin" "identity-number" || return 1
  self_test_artifact_content_case "binary-artifact-content" "evidence.bin" "${other_name}=not-a-real-value" "secret" || return 1
  self_test_artifact_find_failure || return 1
  self_test_artifact_argument_failure || return 1
  self_test_artifact_symlink_failure || return 1
  self_test_unreadable_artifact_failure || return 1
  self_test_case "quoted-nordic-newline-path" $'sykmelding-på\nbruker.md' "${other_name}=not-a-real-token" "secret" 1 || return 1
  self_test_case "github-token" "github.md" "$github_fixture" "github-token" 1 || return 1
  self_test_case "github-oauth-token" "github-oauth.md" "$gho_fixture" "github-token" 1 || return 1
  self_test_case "github-user-token" "github-user.md" "$ghu_fixture" "github-token" 1 || return 1
  self_test_case "github-server-token" "github-server.md" "$ghs_fixture" "github-token" 1 || return 1
  self_test_case "github-refresh-token" "github-refresh.md" "$ghr_fixture" "github-token" 1 || return 1
  self_test_case "aws-access-key" "aws.md" "$aws_fixture" "aws-access-key-id" 1 || return 1
  self_test_case "aws-temporary-access-key" "aws-temporary.md" "$asia_fixture" "aws-access-key-id" 1 || return 1
  self_test_case "slack-token" "slack.md" "$slack_fixture" "slack-token" 1 || return 1
  self_test_case "documented-placeholders" "placeholders.md" $'ghp_placeholder\ngithub_pat_placeholder\n'"$aws_placeholder"$'\nxoxb-placeholder' "" 0 || return 1
  self_test_diff_case "committed-nordic-newline-path" $'sykmelding-på\nbruker.md' "${other_name}=not-a-real-token" "secret" 1 || return 1
  self_test_diff_case "committed-augmented-assignment" "augmented.sh" "${other_name}+=not-a-real-value" "secret" 1 || return 1
  self_test_multiline_replacement_case "multiline-replacement" "" || return 1
  self_test_multiline_replacement_case \
    "multiline-comment-replacement" \
    $'  /* retained comment */\n' || return 1
  self_test_multiline_replacement_case \
    "multiline-comment-block-replacement" \
    $'  /* retained start\n   * retained middle\n   */\n' || return 1
  self_test_default_git_failure || return 1
  self_test_nondiff_case "${other_name}=not-a-real-token" || return 1
  self_test_type_change_case "${other_name}=not-a-real-token" || return 1
  self_test_nul_case "${other_name}=not-a-real-token"$'\n'"$github_fixture" "github-token" || return 1
  self_test_hunk_context_case || return 1
  self_test_inter_hunk_context_case || return 1
  self_test_nested_cwd_diff_case || return 1
  self_test_nested_cwd_staged_case || return 1
  self_test_unrelated_diff || return 1
  self_test_own_source || return 1

  echo "OK   staged sensitive-data scanner self-test"
}

if [ "${1:-}" = "--self-test" ]; then
  if [ "$#" -ne 1 ]; then
    echo "Usage: scripts/scan-staged-sensitive-data.sh --self-test" >&2
    exit 2
  fi
  self_test
  exit $?
fi

if [ "${1:-}" = "--diff" ]; then
  if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "Usage: scripts/scan-staged-sensitive-data.sh --diff <base-sha> [head-sha]" >&2
    exit 2
  fi
  scan_committed_diff "$2" "${3:-HEAD}"
  scan_status=$?
  if [ "$scan_status" -ne 0 ]; then
    exit "$scan_status"
  fi
  if [ "$hits" -ne 0 ]; then
    echo ""
    echo "BLOCKED: possible PII or secret found. Remove or mask it before committing."
    exit 1
  fi
  exit 0
fi

if [ "$#" -gt 1 ]; then
  echo "Usage: scripts/scan-staged-sensitive-data.sh [artifact-directory]" >&2
  exit 2
fi

ARTIFACT_DIR="${1:-}"
if [ -n "$ARTIFACT_DIR" ] && [ ! -d "$ARTIFACT_DIR" ]; then
  echo "ERROR: artifact target must be an existing directory" >&2
  exit 2
fi
if [ -n "$ARTIFACT_DIR" ] && [ -L "$ARTIFACT_DIR" ]; then
  echo "ERROR: artifact symlinks are not permitted" >&2
  exit 2
fi

# 1) Optional explicit local artifact directory.
if [ -n "$ARTIFACT_DIR" ] && [ -d "$ARTIFACT_DIR" ]; then
  artifact_paths="$(mktemp)" || {
    echo "ERROR: unable to allocate a temporary artifact enumeration file" >&2
    exit 2
  }
  if ! find "$ARTIFACT_DIR" \( -type f -o -type l \) -print0 > "$artifact_paths" 2>/dev/null; then
    rm -f "$artifact_paths"
    echo "ERROR: unable to enumerate artifact files" >&2
    exit 2
  fi
  artifact_index=0
  artifact_status=0
  while IFS= read -r -d '' f; do
    if [ -n "$f" ]; then
      artifact_index=$((artifact_index + 1))
      artifact_label="artifact file #${artifact_index}"
      if [ -L "$f" ]; then
        artifact_status=2
        artifact_error="ERROR: artifact symlinks are not permitted"
        break
      fi
      if [ ! -r "$f" ]; then
        artifact_status=2
        artifact_error="ERROR: artifact file is not readable"
        break
      fi
      if ! scan_path "$artifact_label" "$f"; then
        artifact_status=2
        break
      fi
      if ! scan_file "$artifact_label" "$f"; then
        artifact_status=2
        break
      fi
    fi
  done < "$artifact_paths"
  rm -f "$artifact_paths"
  if [ "$artifact_status" -ne 0 ]; then
    if [ -n "${artifact_error:-}" ]; then
      echo "$artifact_error" >&2
    fi
    exit "$artifact_status"
  fi
fi

# 2) Scan lines added to the index. Existing baseline lines are not new
# exposure; one context line is used only to reconstruct split assignments.
git_status=127
if command -v git >/dev/null 2>&1; then
  git rev-parse --git-dir >/dev/null 2>&1
  git_status=$?
fi
if [ "$git_status" -eq 0 ]; then
  staged_repository_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "ERROR: unable to determine repository root for staged scanning" >&2
    exit 2
  }
  if ! cd "$staged_repository_root"; then
    echo "ERROR: unable to enter repository root for staged scanning" >&2
    exit 2
  fi
  # --name-only -z preserves non-ASCII and newline-containing filenames. Git's
  # default C-style quoting otherwise turns such a path into a different argv.
  if ! scan_git_diff "staged: " ":" --cached; then
    exit 2
  fi
elif [ -z "$ARTIFACT_DIR" ] ||
  { [ "$git_status" -ne 128 ] && [ "$git_status" -ne 127 ]; }; then
  echo "ERROR: unable to determine repository state for staged scanning" >&2
  exit 2
fi

if [ "$hits" -ne 0 ]; then
  echo ""
  echo "BLOCKED: possible PII or secret found. Remove or mask it before committing."
  echo "  Technical identifiers are allowed; personal data and secrets are not."
  echo "  False positive? Verify manually; this gate is intentionally strict."
  exit 1
fi
exit 0
