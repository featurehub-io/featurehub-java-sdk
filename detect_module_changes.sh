#!/bin/bash

TARGET_BRANCH="main" # Replace with your target branch name
SOURCE_BRANCH="HEAD" # Often the current branch or HEAD in CI environments

DIFF_MODE=false
if [[ "$1" == "--diff" ]]; then
  DIFF_MODE=true
fi

# 1. Get the list of files changed between the two branches
#    The three-dot syntax '...' compares the source branch with the merge-base of the two branches,
#    showing only the changes unique to the source branch.
CHANGED_FILES=$(git diff --name-only $TARGET_BRANCH...$SOURCE_BRANCH)

if [[ "$CHANGED_FILES" == "" ]]; then
  echo "nothing to process"
  exit 0
fi

# 2. Process the list to find the unique Maven project root directories
CHANGED_MODULES=()
for file in $CHANGED_FILES; do
  # A simple heuristic: if a file is in a Maven project structure (e.g., src/main, pom.xml),
  # the module root is the directory containing the project files.
  # This approach is heuristic and might need refinement for complex project structures.

  # Check if the file is a pom.xml
  if [[ "$file" == "pom.xml" ]]; then
    # Ignore the root pom.xml
    continue
  elif [[ "$file" =~ ^[^/]+/pom\.xml$ ]]; then
    # Ignore pom.xml files in direct subfolders of the root (aggregator POMs)
    continue
  elif [[ "$file" == */pom.xml ]]; then
    MODULE_DIR=$(dirname "$file")
  elif [[ "$file" == */src/main/* ]]; then
    # Only include changes under src/main/; find the nearest module root
    dir=$(dirname "$file")
    MODULE_DIR=""
    while [[ "$dir" != "." && "$dir" != "/" ]]; do
      if [[ -f "$dir/pom.xml" ]]; then
        MODULE_DIR="$dir"
        break
      fi
      dir=$(dirname "$dir")
    done
    if [[ -z "$MODULE_DIR" ]]; then
      continue
    fi
  else
    # Ignore all other files (test sources, resources, docs, etc.)
    continue
  fi

  # Add unique module directories to the list
  if [[ ! " ${CHANGED_MODULES[@]} " =~ " ${MODULE_DIR} " ]]; then
    CHANGED_MODULES+=("$MODULE_DIR")
  fi
done

# Helper: write or diff-check a file
# Usage: write_or_diff <filename> <content>
DIFF_FAILED=false
write_or_diff() {
  local file="$1"
  local content="$2"
  if $DIFF_MODE; then
    if [[ ! -f "$file" ]]; then
      echo "DIFF FAILURE: $file does not exist but would be created with content: $content"
      DIFF_FAILED=true
    elif [[ "$(cat "$file")" != "$content" ]]; then
      echo "DIFF FAILURE: $file is out of date"
      echo "  expected: $content"
      echo "  actual:   $(cat "$file")"
      DIFF_FAILED=true
    fi
  else
    printf "%s" "$content" > "$file"
    echo "List written to $file"
  fi
}

# 3. java17_changed.txt (only modules under v17-and-above, comma-separated)
JAVA17_FILE="v17-and-above/java17_changed.txt"
JAVA17_MODULES=()
for module in "${CHANGED_MODULES[@]}"; do
  if [[ "$module" == v17-and-above/* ]]; then
    JAVA17_MODULES+=("${module#v17-and-above/}")
  fi
done
JAVA17_CONTENT="$(IFS=,; echo "${JAVA17_MODULES[*]}")"

echo "Java 17 changed Maven projects ($JAVA17_FILE):"
echo "$JAVA17_CONTENT"
echo ""
write_or_diff "$JAVA17_FILE" "$JAVA17_CONTENT"

# 4. java11_changed.txt (excludes modules under v17-and-above)
JAVA11_FILE="java11_changed.txt"
JAVA11_MODULES=()
for module in "${CHANGED_MODULES[@]}"; do
  if [[ "$module" != v17-and-above* ]]; then
    JAVA11_MODULES+=("$module")
  fi
done
JAVA11_CONTENT="$(IFS=,; echo "${JAVA11_MODULES[*]}")"

echo "Java 11 changed Maven projects ($JAVA11_FILE):"
echo "$JAVA11_CONTENT"
echo ""
write_or_diff "$JAVA11_FILE" "$JAVA11_CONTENT"

# Helper: load, merge and write a cumulative release_modules.txt
# Usage: build_release_file <file> <v17:true|false> <modules...>
# Reads existing file, merges new modules (stripping v17 prefix when v17=true), writes result.
build_release_content() {
  local file="$1"
  local strip_v17="$2"
  shift 2
  local new_modules=("$@")
  local merged=()

  if [[ -f "$file" ]]; then
    IFS=',' read -ra existing <<< "$(cat "$file")"
    for m in "${existing[@]}"; do
      m="${m// /}"
      if [[ -n "$m" ]]; then
        merged+=("$m")
      fi
    done
  fi

  for module in "${new_modules[@]}"; do
    if [[ "$strip_v17" == "true" ]]; then
      module="${module#v17-and-above/}"
    fi
    if [[ ! " ${merged[@]} " =~ " ${module} " ]]; then
      merged+=("$module")
    fi
  done

  echo "$(IFS=,; echo "${merged[*]}")"
}

# 5a. release_modules.txt (Java 11 modules, excluding examples and v17-and-above)
RELEASE_FILE="release_modules.txt"
RELEASE11_NEW=()
for module in "${CHANGED_MODULES[@]}"; do
  if [[ "$module" != *examples* && "$module" != v17-and-above* ]]; then
    RELEASE11_NEW+=("$module")
  fi
done
RELEASE11_CONTENT=$(build_release_content "$RELEASE_FILE" "false" "${RELEASE11_NEW[@]}")

echo "Release modules ($RELEASE_FILE, Java 11, cumulative):"
echo "$RELEASE11_CONTENT"
echo ""
write_or_diff "$RELEASE_FILE" "$RELEASE11_CONTENT"

# 5b. v17-and-above/release_modules.txt (Java 17 modules, excluding examples, prefix stripped)
RELEASE17_FILE="v17-and-above/release_modules.txt"
RELEASE17_NEW=()
for module in "${CHANGED_MODULES[@]}"; do
  if [[ "$module" != *examples* && "$module" == v17-and-above/* ]]; then
    RELEASE17_NEW+=("$module")
  fi
done
RELEASE17_CONTENT=$(build_release_content "$RELEASE17_FILE" "true" "${RELEASE17_NEW[@]}")

echo "Release modules ($RELEASE17_FILE, Java 17, cumulative):"
echo "$RELEASE17_CONTENT"
echo ""
write_or_diff "$RELEASE17_FILE" "$RELEASE17_CONTENT"

if $DIFF_FAILED; then
  echo "ERROR: one or more output files are out of date. Re-run without --diff to update them."
  exit 1
fi

if ! $DIFF_MODE; then
  git add "$JAVA17_FILE" "$JAVA11_FILE" "$RELEASE_FILE" "$RELEASE17_FILE"
fi
