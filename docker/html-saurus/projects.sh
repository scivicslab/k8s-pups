#!/bin/sh
# Which folders under the user's storage the portal reads as Docusaurus projects. Sourced by entrypoint.sh.
#
# With no patterns, a project is found near the top of the storage (its configuration file up to three levels down). With patterns (a comma-separated list such as
# "~/works/doc_*,~/works/groups/lab/doc_*"), only the folders that match are read. The patterns are matched here, by find
# one path segment at a time, never by a shell: nothing the user typed is ever expanded or run. The controller refuses
# the same things beforehand (DocumentFolderPatterns); this refuses them again.

# A Docusaurus project has docs/ and docusaurus.config.js or docusaurus.config.ts (what html-saurus looks for too).
is_project() {
  [ -d "$1/docs" ] && { [ -f "$1/docusaurus.config.js" ] || [ -f "$1/docusaurus.config.ts" ]; }
}

# Prints the folders below $1 that match the path pattern $2 (segments separated by "/", "*" and "?" as in find -name).
# Hidden folders never match. Symbolic links are not followed, so a link cannot lead out of the storage.
match_folders() {
  base="$1"; rest="$2"
  seg="${rest%%/*}"
  if [ "$seg" = "$rest" ]; then next=""; else next="${rest#*/}"; fi
  find "$base" -mindepth 1 -maxdepth 1 -type d -name "$seg" ! -name '.*' 2>/dev/null | sort | while IFS= read -r d; do
    # A subshell, so that the recursion does not overwrite the variables of this level (sh has no local variables)
    if [ -z "$next" ]; then printf '%s\n' "$d"; else (match_folders "$d" "$next"); fi
  done
}

# Succeeds for a pattern below the storage made of plain segments; otherwise prints why and fails.
check_pattern() {
  case "$1" in
    ""|/*|~*|.*|*/.*|*//*) echo "portal: pattern not accepted (absolute, hidden, empty or relative to something else): $1" >&2; return 1 ;;
    *[\ \$\`\;\|\&\<\>\(\)\[\]\{\}\\\"\'\!\#\%\^\=\:]*) echo "portal: pattern not accepted (a character that is not allowed): $1" >&2; return 1 ;;
  esac
  return 0
}

# Says what the reader needs to know about a pattern: on stderr (the log of the Pod), and, when NOTICE_LINES_FILE names a
# file, as a line in it; entrypoint.sh turns those lines into the notice shown on the portal page. $1 is the pattern as the
# user wrote it, $2 the message (backticks around text make it code on the page).
say() {
  echo "portal: $2" | tr -d '`' >&2
  if [ -n "${NOTICE_LINES_FILE:-}" ]; then printf '%s\n' "$2" >> "$NOTICE_LINES_FILE"; fi
}

# Prints the project folders under $1. $2 is the patterns as the user wrote them (may be empty).
list_projects() {
  source="$1"; patterns="$2"
  if [ -z "$(printf '%s' "$patterns" | tr -d ' ,\n')" ]; then
    find "$source" -maxdepth 3 \( -name node_modules -o -name .git -o -name build \) -prune -o \
         -type f \( -name 'docusaurus.config.js' -o -name 'docusaurus.config.ts' \) -print 2>/dev/null | sort |
      while IFS= read -r config; do
        project="$(dirname "$config")"
        [ -d "$project/docs" ] && printf '%s\n' "$project"
      done
    return 0
  fi
  printf '%s\n' "$patterns" | tr ',' '\n' | while IFS= read -r pattern; do
    pattern="$(printf '%s' "$pattern" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s#/*$##')"
    [ -n "$pattern" ] || continue
    shown="$pattern"
    case "$pattern" in
      "~/works") pattern="" ;;
      "~/works/"*) pattern="${pattern#\~/works/}" ;;
    esac
    check_pattern "$pattern" || { say "$shown" "\`$shown\` is not accepted as a folder pattern."; continue; }
    # Two dots as a whole segment are the one way out of the storage that the characters above do not stop.
    case "/$pattern/" in */../*|*/./*) say "$shown" "\`$shown\` is not accepted as a folder pattern."; continue ;; esac
    matches="$(match_folders "$source" "$pattern")"
    projects=""
    if [ -n "$matches" ]; then
      projects="$(printf '%s\n' "$matches" | while IFS= read -r d; do
        if is_project "$d"; then printf '%s\n' "$d"; else echo "portal: $d matches $pattern but is not a Docusaurus project" >&2; fi
      done)"
    fi
    if [ -n "$projects" ]; then
      printf '%s\n' "$projects"
    elif [ -z "$matches" ]; then
      say "$shown" "No folder matches \`$shown\`."
    else
      say "$shown" "\`$shown\` matches folders, but none of them is a Docusaurus project (it needs docs/ and docusaurus.config.js or .ts)."
    fi
  done
}

# Writes the notice for the portal page to $3 from the lines list_projects left in $1 (one per pattern that found nothing)
# and the number of projects found, $2. Leaves $3 empty when there is nothing to say.
write_notice() {
  awk '!seen[$0]++' "$1" > "$3"
  if [ -s "$3" ]; then
    if [ "$2" -eq 0 ]; then
      echo "Start Documents again with another pattern." >> "$3"
    else
      echo "The other folders were read as usual. To change the folders, start Documents again with other patterns." >> "$3"
    fi
  elif [ "$2" -eq 0 ]; then
    echo "No Docusaurus project was found near the top of ~/works. Start Documents again and enter the folders that hold documents, for example \`~/works/doc_*\`." > "$3"
  fi
}
