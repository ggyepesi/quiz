# Effective Class Explanation and Uniform Sampling

## Status

Accepted design. Milestone 1 is implemented as a read-only effective-class explanation
in the ModelBuilder **Explanation** tab. Milestone 2 has begun with Source-class
sampling through normal extraction and the shared Instances presentation. Statement
sampling now uses bounded subject discovery followed by the same statement/qualifier
acquisition and reification constructs as generation. Owned and Aggregate adapters
remain subsequent milestones.

## Problem

The current editors show individual declarations, but generation runs the effective
model assembled from imports, inheritance, class construction, fields, inverses and
classification rules. A reader can see each piece and still not answer:

- Where do this class's instances come from?
- Which model owns the declaration?
- Which fields are inherited or imported, and which are local?
- Which other fields or rules make instances appear as this class?
- What concrete objects will generation produce?

Imports make the gap unavoidable. An imported Person can be visible and read-only
while its role in OfficeHolding.source and Person.spouse remains hidden.

## One explanatory construct

The compiled model is the authority for the effective shape. A single explanation
derived from it answers five questions for every class kind:

1. **Declaration** — local, imported from a named model, or a local extension.
2. **Instances** — population query, statement construction, owner production,
   aggregation, reference/classification, or an explicit combination. The existing
   `MembershipPattern` is the single owner of this classification until compilation
   grows an equivalent explicit production description; the explanation delegates to
   it rather than reconstructing the cases.
3. **Fields** — the effective fields and where each declaration comes from. Asked as a
   difference rather than by re-walking bases: `CompiledClass` carries both
   `ownFields()` and `effectiveFields()`, so what is local and what arrived by
   inheritance or import is already decided by compilation.
4. **Uses** — fields and classification/representation rules that refer to the class.
   This is the one answer nothing computes today, and it is already spoken for:
   `ModelDeclarationGraph` in
   [Declaration Change Plans](declaration-change-plans.md) is the single index of
   declarations and their ownership and reference edges, built so Copy and Remove can
   show their closure. "Which fields and rules refer to this class" is that index read
   in the other direction. It is asked there, not derived again here — two reverse
   indexes over one model would be free to disagree.
5. **Sample** — bounded real instances produced by the same compiled plan as generation.

Authorship metadata such as importedFrom is read from declarations; effective names,
fields and construction semantics come from compilation. That split is forced rather
than stylistic: `CompiledClass` does not carry `importedFrom` at all, so compilation
cannot answer question 1 and the declarations cannot answer questions 2 and 3. A
reader who "simplifies" by looking for authorship on the compiled class will conclude
the design is confused; it is the only route available.

If the draft does not compile, the explanation says why instead of inventing a
fallback interpretation.

## Presentation

An **Explanation** tab sits beside **Configuration** and follows the model-tree
selection. It is explanatory and read-only. Existing editors remain the places where
explicit configuration changes are made. Selecting a class shows its complete
effective shape; selecting a field starts with a short field explanation and then
shows the owning class for context. This keeps explanation available without making
the everyday configuration form longer.

Example:

    Effective class: Person
      Declaration: imported from model Person
      Instances: evidence-derived kind (P31 = Q5)
      Fields: structuredName, dateOfBirth, dateOfDeath, image, citizenship
      Used by: OfficeHolding.source; Person.spouse; classification P31 = Q5

For a statement class, the same section complements the statement anatomy already
shown by its editor: one source statement becomes one instance; subject, value and
qualifier roles are explicit; an inverse such as Person.offices is a resulting list.

Inspection must not mutate the model or start network work.

## Uniform class sampling

The user action is always **Sample class instances**. Class kind selects the production
adapter, not a different user workflow:

- Source — sample its population plan.
- Statement — sample source statements and apply subject/value/qualifier mappings.
- Owned — sample producing owners and construct their components. The rule for which
  population that is already exists in `ModelSourceWorkbenchPanel.samplingClass()`: a
  component has no members to query, its instances being the owner's entities under the
  owner's QID, so the owner one hop up is sampled. The adapter asks that rule; it does
  not decide it a second time.
- Aggregate — sample source records and apply grouping.
- Imported or extended — use the effective compiled class in the importing project.
  Note what this cannot mean: population is **not** inherited and an import carries
  none, so an imported Person in Nobel has no population of its own to sample. It is
  sampled through the role that represents it — which is the reference/classification
  case below, and is why imports are the motivating example rather than an awkward
  edge of it.
- Reference/classification-derived — sample the bounded evidence population that can
  actually establish membership.

Every adapter returns the same result contract and uses the shared query runner,
logging, cancellation and limit. The result opens in a sample window containing the
existing **Instances** view; sampled instances are not a second presentation kind.
They therefore use the same cards/table, nested-field expansion, links and
search/sort/view configuration as generated instances. The separate window preserves
the currently generated domain result rather than silently replacing it with a bounded
sample.

The Instances view adds only sample-run context above the ordinary result:

- the sampled effective class;
- the requested limit;
- the production route used; and
- whether the bounded result is complete or truncated.

Sampling is inspection only.

## Milestones

1. Add the compiled, read-only effective-class explanation to ModelBuilder.
2. Define one class-sample request/result contract, adapt the existing Source sample,
   and present its result through the existing Instances view.
3. Add Statement sampling by reusing normal statement acquisition and construction.
   **Implemented.**
4. Add Owned and Aggregate adapters only when exercised by real configurations.
5. Reuse the explanation and sample result in Explorer and the generation diagram.
