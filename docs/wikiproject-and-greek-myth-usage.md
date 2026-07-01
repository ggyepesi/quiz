# WikiProject panel usage & getting Greek mythology data

Notes captured 2026-06-23.

## 1. Is "Filter" a search?

No — it's a **local, case-insensitive substring filter over the rows already
loaded**. It does *not* re-query Wikipedia/Wikidata; it just narrows what's
already in the table (matches against all columns). Use it to trim a big result
(e.g. after Loading "Mythology", type "Athena" to hide non-matching rows). To
fetch, use **Load WikiProject seeds**.

## 2. Getting from "Mythology" to Greek characters — cleaner than WikiProject

WikiProject "Mythology" is broad and mixed (every mythology, sorted by article
*quality*, not by being Greek), and there is **no Greek-mythology-specific
assessment category**. Wikidata already has clean membership types — found by
checking what Athena (Q37122) is `instance of` (P31):

| Type            | QID         | Instances |
|-----------------|-------------|-----------|
| **Greek deity** | **Q22989102** | **327**   |
| Olympian god    | Q113103481  | 12        |
| goddess (all)   | Q205985     | 564       |
| war deity       | Q41863069   | 87        |

**Recommended route for Greek characters — set the class's membership type, not
WikiProject:**

1. New domain → class e.g. `GreekDeity`.
2. In the **Class** panel, search the Wikidata type → pick **Greek deity
   (Q22989102)** → clean 327-instance set. (For the famous core: **Olympian god**
   = 12, or tick **Notable only**.)
3. Generate.

**"Follow Athena's QID?"** Good instinct, different purpose. Following an
*individual* is how you discover **what properties/types exist** (click her QID →
Wikidata; that's how "instance of Greek deity" was found). To get the *whole
set*, anchor on a **group** (Explore "Twelve Olympians" → 12 members) or use the
membership type above. The **Explore-by-example battery is tuned for
group→members** (has-part / member-of), so running it on a single deity won't
surface her scalar properties — use the **Discover** tab on the class for that,
or open her QID.

**Properties (fields) worth adding** (from Athena): `father` (P22), `mother`
(P25), `child` (P40), `part of` (P361), `worshipped by` (P1049), `residence`
(P551), `image` (P18). The genealogy trio (P22/P25/P40) is the best quiz
material. Add via the **Discover** tab or by hand.

**So:** WikiProject is a *fallback* (curated-by-quality articles when there's no
clean type); for Greek deities the **P31 type is primary**, and **Explore** is
for curated *sets* (Labours, Argonauts, Olympians) → Seed QIDs.

## 3. Page ID / Category / Assessment page

Provenance from the Wikipedia assessment read:

- **Assessment page** — the Talk-page title (e.g. `Talk:Athena`); the **Title**
  column is that with `Talk:` stripped.
- **Category** — which assessment category the row came from (e.g. `GA-Class
  Mythology articles`) → encodes the article's **quality class** (FA/GA/B/C…).
- **Page ID** — the Wikipedia page's internal numeric id.

**Actionable:** only **QID** (→ becomes the seed instance) and **Title** (label).
**Category** is a mild quality signal (prefer FA/GA). **Page ID** and
**Assessment page** are noise for our purposes (candidates to hide).
