# Discovering Entities and Naming Them as a Vocabulary

## Status

Worked example, current as of 2026-08-29. Every step below was performed against live
Wikidata while building the Nobel domain, and the counts are what the queries returned.

This guide covers ONE construct end to end: finding real entities in Explore and naming
the set as a **vocabulary** the rest of the model can refer to. It is the first thing you
do in a new domain, and everything else — statement classes, fields, generation — refers
back to the name you create here.

## What a vocabulary is, and what it is not

A **vocabulary** is a bounded, explicit set of value QIDs. You listed them; nothing joins
the set because it happens to match a rule.

A **population** is open: it is defined by a rule, and entities satisfying that rule
belong to it whether or not you have seen them.

The distinction decides what may be done to the set. A vocabulary can be grown by adding
QIDs; a population cannot, because adding a QID to a rule-defined set would silently mean
something other than the rule. The workbench enforces this — Add is offered only for a
vocabulary.

Use a vocabulary when the set is small, closed and known: the six Nobel categories, the
Oscar award categories. Use a population when membership is a fact about the world.

## The worked example

The Nobel domain needs the set of prize categories, because the Award class is the P166
statement *restricted to those values*. There are six.

### 1. Decide what belongs in the set — by measuring, not by assuming

Before collecting anything, ask the data what the candidates are and how much each one
carries. Explore's entity search will happily offer a dozen things called "Nobel Prize".

Physics, Chemistry, Physiology or Medicine, Literature and Peace are the five in Alfred
Nobel's will. The Economics prize (Q47170) is formally the *Prize in Economic Sciences in
Memory of Alfred Nobel* — not a Nobel Prize, and endowed separately. It carries **99
laureates**, so excluding it would silently drop a tenth of the domain. It belongs in.

`Q7191` "Nobel Prize" is the umbrella concept, and it is the interesting exclusion: **4
laureates** carry it directly rather than a category. Those four are data errors upstream,
not a seventh category. Including Q7191 to "catch" them would admit the umbrella as a
peer of the categories and make every count ambiguous; the four are better fixed as
curation.

The rule this illustrates: **a candidate's count tells you whether it is a category, an
umbrella or a mistake.** Measure before you collect.

### 2. Collect the entities in Explore

Open **Explorer tools** (button on the workbench toolbar) → **Wikidata** tab →
**Entity** tab, and search `Nobel Prize`.

The result list is multi-select. Select the rows you want — with the modifier key for
several at once — then open **Reusable selections** and press **Add selected entities**
on its Entities tab.

Reusable selections are window-scoped, typed, and shared by every tool in the workbench:
what you collect in the entity list is visible to the vocabulary editor, the field
editor and relation discovery. Two things follow from that:

- **A value is identified by its QID**, not by the label it happened to carry when you
  picked it. Picking the same entity from the entity list and again from a discovered
  graph selects it once; a later source may improve the label without duplicating it.
- **Entities and properties are separate collections.** The dialog has a tab for each,
  and a tool reads only the kind it needs.

You do not have to collect them in one pass. Search again, select more, add again — the
collection accumulates. Open the dialog at any point to see everything held, and remove
anything picked by mistake: removal is always available, in every tool that shows the
collection.

For Nobel this is six searches, ending with six entities held:

```
Q38104  Nobel Prize in Physics
Q44585  Nobel Prize in Chemistry
Q80061  Nobel Prize in Physiology or Medicine
Q37922  Nobel Prize in Literature
Q35637  Nobel Peace Prize
Q47170  Prize in Economic Sciences in Memory of Alfred Nobel
```

### 3. Create the vocabulary

Open **Vocabularies / populations** (button on the workbench toolbar).

**New** asks for a name and creates an empty vocabulary. Name it for what its members
ARE, not for where they came from: `Categories`, not `NobelPrizeList`. The name becomes a
model-level reference — fields and statement sources name it in the saved model — so it
is read far more often than it is typed.

The name is also the reason to think for a moment before choosing it. Selection names and
class names share ONE namespace, and **a class wins it**: if a class named `Categories`
exists, every field target reading "Categories" means the class, and the vocabulary
becomes unreachable. The editor refuses a rename onto an existing class name for that
reason, but it cannot refuse a class created later. Prefer a name no class will want.

### 4. Add the collected entities

With the vocabulary chosen, open **Reusable selections** from the vocabulary window,
select the entities in the dialog, and **Add selected entities**.

Values are added in the order picked and never twice, so re-adding a set you have already
added is safe and reports how many were new. The list then shows the six with their
labels — the labels you picked them by, not bare QIDs.

`Remove selected` drops rows from the vocabulary; it does not touch the reusable
collection, which is a different thing that happens to be open beside it.

### 5. Check it, then use it

The vocabulary window shows exactly what the model will refer to. Six rows, each with a
label you recognise. That is the check.

From here the name is what other constructs reference:

```
Award   statement source   propertyPid: P166   valueSelectionName: Categories
```

which reads as: *reify the P166 statement, but only where its value is one of the
Categories.* The Award class then carries 1033 statements — 231 Physics, 233 Medicine,
200 Chemistry, 148 Peace, 122 Literature, 99 Economics.

## Editing a vocabulary later

- **Rename** rewrites the model references that meant this vocabulary. It leaves alone
  any field target that names a CLASS of the same name, because such a field never
  referred to the vocabulary.
- **Delete** refuses while the vocabulary is referenced. Redirect the declarations that
  name it first; the refusal message says it is still referenced rather than deleting
  and leaving dangling names behind.
- **Add / Remove** change the members. Regenerating after a membership change is an
  explicit decision, not an automatic consequence — see the regenerate-vs-migrate rule.

## What this example deliberately leaves out

**Discovering a vocabulary from data** — taking a property's distinct values over sample
subjects as the value set — is a different construct with its own trade-offs, and it is
not needed when the set is six things you can name.

**The Nobel domain beyond Categories.** The prize/share/laureate structure needs a second
datasource, because Wikidata cannot express a prize shared under different motivations:
`P6208` is single-valued per award statement, but 76 of 633 prize-years carry more than
one motivation, and the prize-level motivation has nowhere to live. That is documented
where that work happens, not here.
