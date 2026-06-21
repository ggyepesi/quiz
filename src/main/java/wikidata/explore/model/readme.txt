Per-edge membership override — done

  - EdgeMembershipMode {INHERIT, NONE} added to GeneratedFieldModel (carried in copy(), serialized, persists with the model).
  - RuleTreeCompiler applies the referenced class's membership only when INHERIT.
  - FieldSourcePanel has a new "Edge membership" combo (with a tooltip explaining it).
  - Verified end-to-end: Constellation→stars with NONE drops P31=Q523 from the child query; INHERIT keeps it.
    The root Star class keeps its Q523 independently.

  To use it: select the stars field → set Edge membership = NONE → Apply → regenerate.
  That lets bright named variable/double stars (Alpha Antliae, etc.) into constellations.


 The "Also include types" field needs a QID (Q4193029), but nothing tells them it exists or what it'd do. That's a real hole — and it's the same "make it discoverable in the UI"
  principle (#7). I filed it as #22 and here's the concrete answer.

  The fix: a "Discover types" helper (one validated query)

  For the current membership type, list its direct subclasses with how many new instances each would add (entities not already members) + examples. That answers both halves of your
  question — what's available and how the result changes — in one table. The query (which I already validated for the #15 scope):

  SELECT ?type ?typeLabel (COUNT(DISTINCT ?c) AS ?nNew)
         (GROUP_CONCAT(DISTINCT ?cl) AS ?examples) WHERE {
    ?c wdt:P31 ?type . ?type wdt:P279 wd:<base> .
    FILTER NOT EXISTS { ?c wdt:P31 wd:<base> }
    ?c rdfs:label ?cl FILTER(LANG(?cl)="en")
    ?type rdfs:label ?typeLabel FILTER(LANG(?typeLabel)="en")
  } GROUP BY ?type ?typeLabel ORDER BY DESC(?nNew)

  For Q8928 it produces exactly the decision table you'd want:

  ┌─────────────────────────┬──────┬──────────────────┐
  │        Subclass         │ adds │     examples     │
  ├─────────────────────────┼──────┼──────────────────┤
  │ zodiacal constellation  │ +2   │ Cancer, Aries ✅  │
  ├─────────────────────────┼──────┼──────────────────┤
  │ Chinese constellation   │ +130 │ (skip)          │
  ├─────────────────────────┼──────┼──────────────────┤
  │ former constellation    │ +39  │ (skip)           │
  ├─────────────────────────┼──────┼──────────────────┤
  │ gamma-ray constellation │ +21  │ (skip)           │
  └─────────────────────────┴──────┴──────────────────┘

  So you'd see at a glance that zodiacal = +2 real constellations while the rest are noise — pick it, skip them. Clicking a row would add its QID to the field. It reuses the existing
  Discover / SwingQueryRunner infrastructure (same as property discovery), so it's a natural fit next to the field.

  It's a self-contained UI piece (button → query → results table → click-to-add). Given it's what makes the multi-QID feature actually usable — and squarely your #7 principle — I'd
  recommend building #22 next. Want me to go ahead?

  Open issues now: #1–7, 18, 19, 22. This session's constellation/star pipeline is now genuinely end-to-end: timeout-free, labelled, depth-persisted, per-edge membership + brightness
  sort, multi-QID roots, and the quiz double-render fixed.

  Where we are

    Open: #1, 2–7, 18, 19. This session closed 15 issues and delivered, end-to-end: rule-tree readable UI, the Antlia/distinct-limit fix, star labels (+mul), depth default+persist, the
    child-query timeout rewrite, per-subquery logging, per-edge membership override, brightness sort, multi-QID membership + subtype discovery, and the quiz double-render fix.

    Natural next candidates, when you're ready: #19 (DBpedia/Wikipedia infobox — biggest new capability), #7 (the variable-star aggregate refinement for sorting), or smaller polish like #2
    (Apply-field feedback) / #4 (Save confirm-before-write). Want to pick one, or pause and regenerate to enjoy the result first?
