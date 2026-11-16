#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

echo "Generating Egyptian Gods from data/wikidata/egyptiangods/egyptiangods.model.json."
echo "Generated instances stay in the test run; the graph cache uses a new temporary directory."
echo "No file under data/wikidata/egyptiangods is written."
QUIZ_LIVE_EGYPTIAN_SYMBOL=true mvn -o -pl app -am test \
  -Dtest='EgyptianSymbolLiveAcceptanceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
