#!/usr/bin/env bash
#
# Seeds the open ModelBuilder/web issues into GitHub Issues so you can track
# them in IntelliJ (Settings -> Tools -> Tasks -> Servers -> GitHub).
#
# Prereq (one time):
#   brew install gh
#   gh auth login            # pick this repo's account (ggyepesi)
#
# Then:
#   ./scripts/seed-github-issues.sh
#
# Re-running creates duplicates — run once. Edit/remove lines you don't want.

set -euo pipefail

# Labels (ignore "already exists" errors).
gh label create bug         --color d73a4a --description "Something is broken"        2>/dev/null || true
gh label create enhancement --color a2eeef --description "New feature or improvement"  2>/dev/null || true
gh label create question    --color d876e3 --description "Design question / decision"  2>/dev/null || true
gh label create modelbuilder --color 0e8a16 --description "Wikidata Model Builder"     2>/dev/null || true
gh label create webclient   --color 1d76db --description "Web quiz client"             2>/dev/null || true

issue() { gh issue create --title "$1" --label "$2" --body "$3"; }

issue "Discover: incoming-property query targets the class item, not its instances" \
  "bug,modelbuilder" \
  "discoverIncomingPropertiesToClassQid builds '?subject ?p wd:<classQid>', i.e. properties pointing at the CLASS item (Q8928), not at instances. So a star's P59 (which points at a specific constellation) never appears as an incoming property of Constellation. Fix: sample instances and find properties pointing at them (mirror discoverOutgoingProperties)."

issue "'Apply field source' gives no clear visual feedback" \
  "enhancement,modelbuilder" \
  "Applying a field edit only updates the tree label (and only for type/shape/render/required). Add a brief status confirmation so it's obvious the edit took."

issue "Antlia returns only ~2 stars (constellation has ~42)" \
  "bug,modelbuilder" \
  "The stars child edge returns far fewer stars than the constellation actually has. Investigate: how many Antlia stars have P31=Q523 AND P1215 (magnitude) in Wikidata vs. the per-parent LIMIT / value filter. Likely a data-coverage issue (few stars have a magnitude item), not a query bug — confirm."

issue "Abbreviation field renders with an empty value" \
  "bug,webclient" \
  "The abbreviation (P1813) field shows up with no value in the client. Check QuizableJson rendering / the snapshot value for abbreviation."

issue "Discover: 'properties between class A and class B' query" \
  "enhancement,modelbuilder" \
  "Add an action that finds only the properties connecting two classes, auto-using the field's object-type QID:
SELECT ?p (COUNT(*) AS ?n) WHERE {
  ?a wdt:P31 wd:<A> . ?a ?pd ?b . ?b wdt:P31 wd:<B> .
  ?p wikibase:directClaim ?pd .
} GROUP BY ?p ORDER BY DESC(?n)
This pinpoints e.g. P59 for Star->Constellation."

issue "Rule tree: readable / explanatory UI instead of raw JSON" \
  "enhancement,modelbuilder" \
  "Show rule tree currently dumps RuleTreeConfig JSON. Render it as a readable tree (membership, included fields, child edges, direction, filters) with short explanations."

issue "Save everything: confirm in a dialog BEFORE saving" \
  "enhancement,modelbuilder" \
  "The confirmation dialog appears AFTER the files are written. Show the planned paths and confirm before writing (so Escape actually cancels)."

issue "Configurable inverse-relation generation (1:1 / 1:N / N:N)" \
  "question,modelbuilder" \
  "Design question: when a relation like Constellation->stars (P59 incoming) is added, should the inverse (Star->constellation) field be generated automatically? Make it configurable per relation: one-to-one / one-to-many / many-to-many."

issue "Query log: make logged queries directly runnable + fully copyable" \
  "enhancement,modelbuilder" \
  "1) The logged request has a leading comment and sometimes multiple SELECTs, so it isn't pasteable into WDQS as-is. 2) The whole log entry isn't copyable as a block. Make each logged query a single runnable SELECT and add copy-the-whole-entry."

issue "Per-edge (relationship-scoped) field filters & membership, not only per-class" \
  "enhancement,modelbuilder" \
  "Today a value filter / membership lives on the CLASS field (e.g. Star.apparentMagnitude, Star.instanceMapping P31=Q523), so it is applied wherever that class is produced — both as a child edge (Constellation->stars) AND as a root Star class — from one definition. compileFieldIntoNode attaches a field's filter to the node that owns the field, so the only correct place for 'bright stars' today is the Star class itself.

Limitation: you cannot express 'bright stars UNDER constellations, but all stars when browsing the Star class' — both share the Star definition. A filter on the Constellation->stars EDGE field instead attaches to the Constellation node (filtering constellations by magnitude, which is nonsensical).

Feature: allow a filter/membership to be scoped to a specific relationship (edge), overriding the referenced class's defaults for that edge only. E.g. Constellation.stars could request apparentMagnitude<=6 + ORDER BY brightness while the Star root class stays unfiltered. Likely needs: edge-level filter/override fields on the stars field model, and RuleTreeCompiler applying them to the child node (not the parent)."

echo "Done. Open them in IntelliJ via Tools -> Tasks & Contexts -> Open Task."
