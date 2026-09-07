package quiz.source;

import objectview.Viewable;

/** Source-identity queries shared by validation, enrichment, and transform workflows. */
public final class SourceIdentities {
    private SourceIdentities() { }

    /**
     * The instance's own Wikidata source. A native Wikidata entity identifies directly
     * by QID; a modeled-key instance reads the provider-qualified identity retained by
     * canonicalization. Manual instances with neither return null — a curated identity
     * for them lives in curation history, which consumers read separately.
     */
    public static WikidataSource wikidata(Viewable viewable) {
        if (viewable instanceof WikidataSource wikidata) return wikidata;
        if (viewable == null) return null;
        WikidataSource declared = provenanceValues(viewable).stream()
                .filter(WikidataSource.class::isInstance)
                .map(WikidataSource.class::cast)
                .findFirst().orElse(null);
        if (declared != null) return declared;
        if (viewable instanceof GeneratedEntity generated) {
            String retained = generated.sourceIdentities().stream()
                    .filter(value -> value.startsWith("wikidata:"))
                    .map(value -> value.substring(9))
                    .filter(WikidataSource::isQid)
                    .findFirst().orElse(null);
            if (retained != null) {
                return new WikidataSource(retained, viewable.getDisplayName());
            }
        }
        String id = viewable.getIdentifier();
        return WikidataSource.isQid(id)
                ? new WikidataSource(id, viewable.getDisplayName()) : null;
    }

    public static String wikidataQid(Viewable viewable) {
        WikidataSource wikidata = wikidata(viewable);
        return wikidata != null && WikidataSource.isQid(wikidata.qid())
                ? wikidata.qid() : null;
    }

    /** The retained Wikidata statement occurrence, independent of modeled identity. */
    public static String wikidataStatementId(Viewable viewable) {
        java.util.List<WikidataStatementSource> structured =
                wikidataStatementSources(viewable);
        if (!structured.isEmpty()) return structured.getFirst().statement();
        if (viewable == null) return null;
        String id = viewable.getIdentifier();
        if (wikidata.WikidataIds.isStatementId(id)) return id;
        if (!(viewable instanceof GeneratedEntity generated)) return null;
        return generated.occurrenceIdentities().stream()
                .filter(value -> value.startsWith("wikidata:"))
                .map(value -> value.substring(9))
                .filter(wikidata.WikidataIds::isStatementId)
                .findFirst().orElse(null);
    }

    /** Full retained statement sources, including their subject/property/object triple. */
    public static java.util.List<WikidataStatementSource> wikidataStatementSources(
            Viewable viewable) {
        if (viewable instanceof WikidataStatementSource source) {
            return java.util.List.of(source);
        }
        return provenanceValues(viewable).stream()
                .filter(WikidataStatementSource.class::isInstance)
                .map(WikidataStatementSource.class::cast)
                .toList();
    }

    private static java.util.List<Object> provenanceValues(Viewable viewable) {
        if (viewable == null) return java.util.List.of();
        java.util.List<Object> result = new java.util.ArrayList<>();
        objectview.field.FieldSet fields = objectview.field.FieldSet.of(viewable);
        for (objectview.field.FieldRef field : fields.fields()) {
            if (field.role() != objectview.field.FieldRole.PROVENANCE) continue;
            addValues(result, fields.read(field.name()));
        }
        return result;
    }

    private static void addValues(java.util.List<Object> result, Object value) {
        if (value == null) return;
        if (value instanceof java.util.Collection<?> collection) {
            collection.forEach(item -> addValues(result, item));
        } else if (value instanceof java.util.Map<?, ?> map) {
            map.values().forEach(item -> addValues(result, item));
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addValues(result, java.lang.reflect.Array.get(value, i));
            }
        } else {
            result.add(value);
        }
    }

    /** Link to the source entity, including the entity containing a statement. */
    public static String wikidataUrl(Viewable viewable) {
        String qid = wikidataQid(viewable);
        if (qid == null) {
            return wikidataStatementUrl(wikidataStatementId(viewable));
        }
        return "https://www.wikidata.org/wiki/" + qid;
    }

    /** Link to the entity containing a retained Wikidata statement occurrence. */
    public static String wikidataStatementUrl(String statementId) {
        String qid = wikidata.WikidataIds.statementSubject(statementId);
        return qid == null ? "" : "https://www.wikidata.org/wiki/" + qid;
    }
}
