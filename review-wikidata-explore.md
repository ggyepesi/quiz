# Code Review: `wikidata/explore`

**Date:** 2026-06-07  
**Scope:** `src/main/java/wikidata/explore/` — uncommitted working-tree changes  
**Effort:** high (7 finder angles × up to 6 candidates, 1-vote verify)

---

## Findings (ranked by severity)

### 1. Constructor ignores the `name` parameter — label always set to QID
**File:** `src/main/java/wikidata/explore/tree/WikidataDynamicObject.java:35`

`this.name = this.qid;` is active; the real assignment is commented out:
```java
// this.name = name == null || name.isBlank() ? this.qid : name;
```
Every `WikidataDynamicObject` is constructed with `name == qid` regardless of the Wikidata label passed in. `displayLabel()`, `toString()`, and `getName()` all return the raw QID (e.g. `Q42`) instead of `Douglas Adams`. The entire quiz UI shows QID strings for every entity label.

---

### 2. `name()` setter ignores its argument — assigns `qid` field instead of `name` parameter
**File:** `src/main/java/wikidata/explore/tree/WikidataDynamicObject.java:62`

```java
public void name(String name) {
    this.name = qid; // name == null ? "" : name;
}
```
Calling `obj.name("Orion")` silently sets `this.name = this.qid`. Any post-construction label enrichment is discarded. Even if finding #1 is fixed, any code path calling the setter (deserialization, label updates) will still clobber the name with the QID.

---

### 3. `qid()` setter passes `null` through `normalizeQid` → NullPointerException
**File:** `src/main/java/wikidata/explore/tree/WikidataDynamicObject.java:54`

```java
public void qid(String qid) {
    this.qid = normalizeQid(qid);          // returns null when qid == null
    this.wikidataUrl = this.qid.isBlank()  // NPE here
            ? "" : "https://.../" + this.qid;
}
private static String normalizeQid(String qid) {
    return qid == null ? null : qid.strip().trim();
}
```
The constructor defensively converts `null` to `""`, making the two code paths inconsistent. Any call to `obj.qid(null)` — from deserialization or defensive clearing — throws a `NullPointerException`.

---

### 4. `displayLabel()` always returns QID (consequence of #1)
**File:** `src/main/java/wikidata/explore/tree/WikidataDynamicObject.java:47`

Because `name == qid` (non-blank) for every valid object, the `name.isBlank()` guard is never true, so `displayLabel()` returns `name` which equals `qid`. There is no code path that yields a human-readable label. `toString()` produces `Q123 (Q123)` instead of `Orion (Q123)`.

---

### 5. Blank-QID objects collide on key `""` in `GeneratedKnowledgeSet`
**File:** `src/main/java/wikidata/explore/tree/GeneratedKnowledgeSet.java:37`

When a loaded dataset contains any blank-QID object, all such objects map to key `""` (because `getName()` now returns `qid`, also blank) and overwrite each other in the `LinkedHashMap`. Only the last one survives; all others are silently dropped from the `QuizableGroup`.

---

### 6. `getOrCreate1()` dead debug method bypasses the canonical registry
**File:** `src/main/java/wikidata/explore/tree/WikidataObjectRegistry.java:18`

```java
public WikidataDynamicObject getOrCreate1(String qid, String label) {
    return new WikidataDynamicObject(qid, label);  // never touches byQid
}
```
One character from `getOrCreate`. Any accidental call returns a non-canonical instance. `GeneratedQuizableMapper`'s `IdentityHashMap` misses the cache hit and creates a duplicate generated object for the same QID; for circular entity graphs this causes infinite recursion. Should be deleted.

---

### 7. `useProperty()` fires `afterApplyField` without calling `apply()` — unsaved edits discarded
**File:** `src/main/java/wikidata/explore/tree/FieldSourcePanel.java:94`

`useProperty()` updates only property-related fields in the model, then fires `afterApplyField`. The registered handler in `ModelSourceWorkbenchPanel` immediately calls `fieldSourcePanel.edit(f)`, which re-reads the stale model back into the form. Any unsaved field-name or limit edits the user had typed are silently overwritten.

---

### 8. `sourceWorkbench.edit(f)` called twice on every field apply
**File:** `src/main/java/wikidata/explore/tree/ModelBuilderFrame.java:139`

```java
sourceWorkbench.afterApplyField(f -> {
    classModelPanel.refresh();
    classModelPanel.selectField(f);   // fires TreeSelectionListener synchronously → edit() call #1
    sourceWorkbench.edit(f);          // edit() call #2
});
classModelPanel.addTreeSelectionListener(e ->
    sourceWorkbench.edit(classModelPanel.selectedUserObject()));
```
`selectField` calls `tree.setSelectionPath()`, which synchronously fires the `TreeSelectionListener`, which calls `sourceWorkbench.edit(...)`. The lambda then calls `sourceWorkbench.edit(f)` a second time. Any stateful side-effect in `edit()` (async sample fetch, spinner reset) runs twice; the first call's result is clobbered.

---

## Summary

| # | File | Line | Severity | Kind |
|---|------|------|----------|------|
| 1 | `WikidataDynamicObject.java` | 35 | Critical | Bug (commented-out logic) |
| 2 | `WikidataDynamicObject.java` | 62 | Critical | Bug (commented-out logic) |
| 3 | `WikidataDynamicObject.java` | 54 | High | Bug (NPE) |
| 4 | `WikidataDynamicObject.java` | 47 | High | Consequence of #1 |
| 5 | `GeneratedKnowledgeSet.java` | 37 | High | Bug (silent data loss) |
| 6 | `WikidataObjectRegistry.java` | 18 | Medium | Dead debug code |
| 7 | `FieldSourcePanel.java` | 94 | Medium | Bug (stale state) |
| 8 | `ModelBuilderFrame.java` | 139 | Low | Redundant double-call |

**Root cause of findings 1–5:** `WikidataDynamicObject` constructor and `name()` setter have their original label-assignment logic commented out and replaced with `this.name = this.qid`. These look like in-progress debugging changes that were never reverted. Fixing lines 35 and 62 resolves findings 1, 2, and 4; finding 5 resolves as a side-effect.
