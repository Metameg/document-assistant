#!/usr/bin/env bash
set -euo pipefail

# Run this script from the project root.
base_url="https://www.postgresql.org/docs/17"
output_dir="data/raw"

pages=(
  tutorial-createdb
  tutorial-table
  tutorial-populate
  tutorial-select
  tutorial-join
  tutorial-agg
  tutorial-fk
  tutorial-transactions
)

mkdir -p "$output_dir"

for page in "${pages[@]}"; do
  url="$base_url/$page.html"
  destination="$output_dir/$page.html"

  echo "Downloading $url"

  curl \
    --fail \
    --location \
    --silent \
    --show-error \
    --retry 2 \
    --connect-timeout 10 \
    --max-time 60 \
    "$url" \
    --output "$destination.part"

  mv "$destination.part" "$destination"

  # Preserve the source URL for attribution and future citations.
  printf '%s\n' "$url" > "$destination.source-url"
done

echo "Downloaded ${#pages[@]} documents into $output_dir"
