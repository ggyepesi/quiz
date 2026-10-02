package wikidata.explore.generation;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One per-class census of a saved domain, appended per save so runs can be diffed.
 *
 * <p>It counts what the SNAPSHOT holds. The previous version counted the run's top-level
 * objects by {@code qid()}, which quietly answered a different question: a reified record
 * is keyed by a statement id rather than a QID, so Nomination — 14,905 of the 20,000
 * objects — never appeared in a single row, and a referent reachable only inside another
 * record was counted only when it happened to sit at top level. A drift log that omits
 * the class most likely to drift cannot do its job.</p>
 *
 * <p>Identity is the pool's own ⟨typeKey, id⟩, and a class counts every instance that
 * CLAIMS it. Roles overlap by design — one entity can be both a Nominee and a ForWork —
 * so the columns may add up to more than the total, which counts distinct entities.</p>
 */
public final class DomainCounts {

    /** Marks the row format whose columns are membership-based and include statements. */
    public static final String FORMAT_NOTE =
            "# counts every saved entity by class membership (ids include statement ids); "
                    + "classes overlap, so columns may exceed total";

    private DomainCounts() { }

    /** The tab-separated row body: {@code total=N} then one {@code Class=N} per class,
     *  largest first. The caller supplies the timestamp, so this stays pure.
     *
     *  <p>It counts what the save WROTE. Walking the pool instead counted objects the
     *  write does not keep: absorbing a copy of one ⟨type, qid⟩ drops a base class in
     *  favour of the concrete subtype, so a row claimed Position 357 times beside a
     *  snapshot whose every office claimed only PositionWithHolders. A drift log that
     *  names a class its own file does not contain cannot answer whether the numbers
     *  moved. */
    public static String row(
            Collection<WikidataDynamicObjectJsonStore.PersistedMember> members) {
        Map<String, Set<String>> byClass = new TreeMap<>();
        Set<String> distinct = new LinkedHashSet<>();
        for (WikidataDynamicObjectJsonStore.PersistedMember member : members == null
                ? List.<WikidataDynamicObjectJsonStore.PersistedMember>of() : members) {
            if (member == null || member.id() == null || member.id().isBlank()) {
                continue;
            }
            // An entity claiming no class is a bare reference target: it is in the pool
            // so a reference resolves, and carries no class to count.
            if (member.classNames() == null || member.classNames().isEmpty()) {
                continue;
            }
            String identity = member.typeKey() + ' ' + member.id();
            distinct.add(identity);
            for (String className : member.classNames()) {
                if (className == null || className.isBlank()
                        || WikidataDynamicObject.isInternalClassName(className)) {
                    continue;
                }
                byClass.computeIfAbsent(className, key -> new HashSet<>()).add(identity);
            }
        }
        List<Map.Entry<String, Set<String>>> sorted = new ArrayList<>(byClass.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        StringBuilder row = new StringBuilder("total=").append(distinct.size());
        for (Map.Entry<String, Set<String>> entry : sorted) {
            row.append('\t').append(entry.getKey()).append('=').append(entry.getValue().size());
        }
        return row.toString();
    }
}
