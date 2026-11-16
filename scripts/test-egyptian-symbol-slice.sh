#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

mvn -o -pl app -am test \
  -Dtest='EgyptianGodsSavedModelTest,WikipediaInfoboxAcquisitionTest,EgyptianSymbolAcceptanceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
npm --prefix web run test:e2e
