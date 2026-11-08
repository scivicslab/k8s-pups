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
    if [ -z "$next" ]; then printf '%s\n' "$d"; else match_folders "$d" "$next"; fi
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
    case "$pattern" in
      "~/works") pattern="" ;;
      "~/works/"*) pattern="${pattern#\~/works/}" ;;
    esac
    check_pattern "$pattern" || continue
    # Two dots as a whole segment are the one way out of the storage that the characters above do not stop.
    case "/$pattern/" in */../*|*/./*) echo "portal: pattern not accepted (. or .. segment): $pattern" >&2; continue ;; esac
    matches="$(match_folders "$source" "$pattern")"
    projects=""
    if [ -n "$matches" ]; then
      projects="$(printf '%s\n' "$matches" | while IFS= read -r d; do
        if is_project "$d"; then printf '%s\n' "$d"; else echo "portal: $d matches $pattern but is not a Docusaurus project" >&2; fi
      done)"
    fi
    if [ -n "$projects" ]; then printf '%s\n' "$projects"; else echo "portal: no Docusaurus project matches $pattern" >&2; fi
  done
}
