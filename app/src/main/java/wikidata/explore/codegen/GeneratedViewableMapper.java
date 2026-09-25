package wikidata.explore.codegen;

import wikidata.WikidataIds;

import wikidata.explore.extract.WikidataDynamicObject;
import objectview.Viewable;
import objectview.media.ImagePane;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.Canonicalizer;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GeneratedViewableMapper {
    private final GeneratedViewableRuntime runtime;
    private final datasource.api.DatasourceRegistry datasourceRegistry;
    private final Map<WikidataDynamicObject, Object> generatedByDynamic =
            new IdentityHashMap<>();
    // Real entities (Q\d+) are identified by modeled type + QID: copies of one
    // Position map to ONE Position, while the same QID deliberately represented as a
    // PositionDiscoveryStart remains a separate typed instance. A bare-QID cache handed
    // the latter object to the Position mapper and reflection then read Position fields
    // from a PositionDiscoveryStart Java object.
    private final Map<ModeledEntityKey, Object> generatedByQid =
            new java.util.HashMap<>();
    // A generated class's model/schema and Java fields do not change while this
    // runtime is alive. Resolve that bridge once per class, not once per instance:
    // History otherwise repeated field-name regex work, effective-field traversal,
    // datasource discovery and reflection for tens of thousands of records on load.
    private final Map<String, List<ModeledFieldBinding>> modeledFieldBindings =
            new java.util.HashMap<>();
    private final Map<String, List<DatasourceFieldBinding>> datasourceFieldBindings =
            new java.util.HashMap<>();
    /**
     * Field population is deliberately iterative. A valid domain graph can contain a
     * reference chain far deeper than the JVM stack (History reached this with 75,408
     * objects). Object allocation therefore schedules population instead of recursively
     * populating every referenced object on the caller's stack.
     */
    private final Deque<Population> pendingPopulation = new ArrayDeque<>();

    private record Population(
            GeneratedViewableRuntime.ClassRuntime runtime,
            Object target,
            WikidataDynamicObject source,
            boolean merge) { }

    private record ModeledEntityKey(String type, String qid) { }
    /**
     * References dropped because the field's class cannot hold any class the entity is
     * configured as, counted per field and set of classes.
     *
     * <p>Dropping is right — a Person-valued qualifier holding a position would put an
     * office where the reader expects a person — but it is a well-formed object being
     * discarded, not a malformed value. Silently, nothing distinguishes two of them from
     * ten thousand caused by a mis-stamping, and a domain missing its references looks
     * exactly like one that never had any.
     */
    private final Map<String, Integer> refusedReferences = new LinkedHashMap<>();

    /** What {@link #mapRoots} refused to store, for the run that asked for it to report. */
    public Map<String, Integer> refusedReferences() {
        return Map.copyOf(refusedReferences);
    }
    private record ModeledFieldBinding(GeneratedFieldModel model, Field field) { }
    private record DatasourceFieldBinding(
            datasource.api.DatasourceInstanceField declaration, Field field) { }
    /** Source candidates are partitioned by the same neutral engine as statements and
     * owned components before Java objects are materialized. Indexed per modeled class,
     * because one source object may be represented through more than one class role. */
    private final Map<String, Map<WikidataDynamicObject, WikidataDynamicObject>>
            canonicalSourceByType = new java.util.LinkedHashMap<>();
    private final Map<String, Map<WikidataDynamicObject, String>>
            canonicalSourceKeyByType = new java.util.LinkedHashMap<>();
    private final Map<String, Map<WikidataDynamicObject, List<String>>>
            canonicalSourceIdentitiesByType = new java.util.LinkedHashMap<>();
    private final Map<String, Map<WikidataDynamicObject, List<String>>>
            canonicalOccurrenceIdentitiesByType = new java.util.LinkedHashMap<>();
    // Classes produced once per owning instance. Such a component BORROWS its owner's
    // QID (one Name per Person, carrying the person's identifier), so it is not "the
    // same entity arriving twice": unifying it by QID would hand the owner's instance
    // back for the component's field — and then apply the component class's fields to
    // it. Its identity is the production site, which the pool keeps in the type key.
    private final java.util.Set<String> ownedComponentTypes;

    public GeneratedViewableMapper(GeneratedViewableRuntime runtime) {
        this(runtime, datasource.Datasources.standard());
    }

    public GeneratedViewableMapper(
            GeneratedViewableRuntime runtime,
            datasource.api.DatasourceRegistry datasourceRegistry) {
        this.runtime = runtime;
        this.datasourceRegistry = java.util.Objects.requireNonNull(
                datasourceRegistry, "datasourceRegistry");
        this.ownedComponentTypes = ownedComponentTypes(runtime);
    }

    public List<Viewable> mapRoots(List<WikidataDynamicObject> roots) throws Exception {
        List<Viewable> out = new ArrayList<>();
        if (roots == null) return out;
        prepareSourceCanonicalization(roots);
        // Distinct instances: two roots that are the same entity (same qid, e.g. a
        // work + its inline-reference copy) map to ONE instance — add it once.
        java.util.Set<Object> added =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (WikidataDynamicObject root : roots) {
            Object mapped = mapObject(root);
            if (mapped instanceof Viewable q && added.add(q)) {
                out.add(q);
            }
        }
        // Population and naming are two passes, in opposite orders, because they need
        // opposite orders. Populating a parent is what discovers its children, so the
        // queue fills parents-first. A composed name QUOTES the names of the objects a
        // class references — Canonicalizer reads a referenced Viewable through
        // getDisplayName — so a parent must be named AFTER them, or it quotes the label
        // the child arrived with instead of the name the child ends up with. The old
        // recursion got this for free by unwinding child-first; an iterative pass has to
        // say so.
        List<Population> naming = new ArrayList<>();
        while (!pendingPopulation.isEmpty()) {
            Population population = pendingPopulation.removeFirst();
            populate(population);
            if (!population.merge()) naming.add(population);
        }
        for (int i = naming.size() - 1; i >= 0; i--) {
            canonicalName(naming.get(i));
        }
        return out;
    }

    private void populate(Population population) throws Exception {
        applyFields(population.runtime(), population.target(), population.source(),
                population.merge());
        applyDatasourceFields(population.runtime(), population.target(), population.source(),
                population.merge());
    }

    private void canonicalName(Population population) {
        Object target = population.target();
        WikidataDynamicObject source = population.source();
        GeneratedViewableRuntime.ClassRuntime cr = population.runtime();
        Canonicalizer.FieldReader reader = fieldName -> {
            Field jf = findField(cr.generatedClass(),
                    GeneratedViewableSourceGenerator.sanitizeFieldName(fieldName));
            if (jf != null) {
                try {
                    jf.setAccessible(true);
                    Object v = jf.get(target);
                    if (v != null) return v;
                } catch (IllegalAccessException ignored) {
                    // fall back to the source value
                }
            }
            return source.get(fieldName);
        };
        String override = canonicalDisplayNameOverride(
                cr.model(), reader, source.getDisplayName());
        if (override != null && target instanceof quiz.source.GeneratedEntity ge) {
            ge.label(override);
        }
    }

    private void prepareSourceCanonicalization(List<WikidataDynamicObject> roots) {
        canonicalSourceByType.clear();
        canonicalSourceKeyByType.clear();
        canonicalSourceIdentitiesByType.clear();
        canonicalOccurrenceIdentitiesByType.clear();
        List<WikidataDynamicObject> reachable =
                wikidata.explore.extract.WikidataObjectGraph.reachable(roots);
        for (GeneratedViewableRuntime.ClassRuntime classRuntime : runtime.byType().values()) {
            GeneratedClassModel model = classRuntime.model();
            if (model == null || model.classKind()
                    != wikidata.explore.model.ClassKind.SOURCE) continue;
            List<WikidataDynamicObject> candidates = reachable.stream()
                    .filter(value -> value != null && !value.isPart()
                            && value.directClassNames().contains(model.className()))
                    .toList();
            if (candidates.isEmpty()) continue;
            wikidata.explore.transform.WikidataCanonicalization.Result result =
                    wikidata.explore.transform.WikidataCanonicalization.apply(
                            wikidata.explore.compiled.CanonicalizationPlans.of(model),
                            candidates, null);
            canonicalSourceByType.put(model.className(), result.canonicalByCandidate());
            canonicalSourceKeyByType.put(model.className(), result.keyByCandidate());
            canonicalSourceIdentitiesByType.put(
                    model.className(), result.sourceIdentitiesByCandidate());
            canonicalOccurrenceIdentitiesByType.put(
                    model.className(), result.occurrenceIdentitiesByCandidate());
        }
    }

    private Object mapObject(WikidataDynamicObject source) throws Exception {
        return mapObject(source, null);
    }

    /** The classes some field produces as an owned component. Read from the compiled
     *  classes' own models — the production site is declared on the OWNING field, so
     *  the component class itself says nothing about being owned. */
    private static java.util.Set<String> ownedComponentTypes(
            GeneratedViewableRuntime runtime) {
        java.util.Set<String> owned = new java.util.LinkedHashSet<>();
        if (runtime == null || runtime.byType() == null) return owned;
        for (GeneratedViewableRuntime.ClassRuntime cr : runtime.byType().values()) {
            if (cr == null || cr.model() == null) continue;
            for (GeneratedFieldModel field : cr.model().fields()) {
                if (field == null || field.type() != FieldType.ENTITY
                        || field.mapping().productionKind()
                                != wikidata.explore.model.FieldProductionKind
                                        .OWNED_COMPONENT) {
                    continue;
                }
                String target = field.entityClassName();
                if (target != null && !target.isBlank()) owned.add(target.trim());
            }
        }
        return owned;
    }

    private Object mapObject(WikidataDynamicObject source, String preferredType)
            throws Exception {
        if (source == null) return null;

        boolean typedRequested = preferredType != null && !preferredType.isBlank()
                && runtime.forType(preferredType) != null;

        // Resolve the final carrier BEFORE consulting the instance cache. A field can
        // declare a base class while the object has a modeled subclass. Testing the
        // cached subclass against the base's independent (flattened) Java class misses
        // the cache on every reference and queues the same merge again; History grew
        // more than 25 million pending populations that way. The cache must answer for
        // the carrier we will actually use.
        String sourceType = source.typeName();
        boolean finalModeledType = typedRequested
                && sourceType != null && !sourceType.isBlank()
                && runtime.forType(sourceType) != null
                && !sourceType.equals(preferredType)
                && !source.directClassNames().contains(preferredType);
        String type = finalModeledType ? sourceType
                : typedRequested ? preferredType : sourceType;
        GeneratedViewableRuntime.ClassRuntime selectedRuntime = runtime.forType(type);

        Object existing = generatedByDynamic.get(source);
        boolean existingFitsRequestedType = selectedRuntime == null || existing == null
                || selectedRuntime.generatedClass().isInstance(existing);
        if (existing != null && existingFitsRequestedType
                && (!typedRequested || !(existing instanceof WikidataDynamicObject))) {
            return existing;
        }
        // If we reach here with a cached entry, it's a RAW bare-reference object
        // (the source's own type has no generated class, so an earlier untyped map
        // — e.g. a root pass — cached it raw), but now a typed reference needs the
        // declared class. Re-map to that class and replace the cache, so the typed
        // field gets a typed instance instead of dropping it on the type-mismatch
        // (this is what made P2453-only nominees like a co-writer never render).

        // Choose the target class: prefer the referencing field's declared class
        // (its "Of class") over the object's stamped typeName, so a reference
        // whose target wasn't stamped (typeName "WikidataDynamicObject") still
        // maps to the typed class the field declares — keeping cross-references
        // typed instead of raw. Falls back to the stamped type for roots.
        // A declared field target such as Nominee can be a ROLE carrier. Once
        // evidence classification has assigned a genuine modeled kind (Person),
        // use that kind; retain the declared role only for unknown entities.
        // The normalized source object is only a candidate. Canonicalization may map
        // several projections to one carrier; follow that decision before consulting
        // mapper caches, so QID-based materialization cannot become a second reducer.
        WikidataDynamicObject originalSource = source;
        source = canonicalSourceByType.getOrDefault(type, Map.of())
                .getOrDefault(source, source);
        if (source != originalSource) {
            Object canonicalExisting = generatedByDynamic.get(source);
            if (canonicalExisting != null) {
                generatedByDynamic.put(originalSource, canonicalExisting);
                return canonicalExisting;
            }
        }

        // Map each object to its generated class (e.g. a constellation's child
        // stars -> Star). An object whose type has no generated class (a true
        // bare leaf reference) is kept as-is (renders as a link).
        GeneratedViewableRuntime.ClassRuntime cr = selectedRuntime;
        if (cr == null) {
            generatedByDynamic.put(source, source);
            return source;
        }

        // QID identity for real entities: the same entity arriving as several WDO
        // copies (a root + inline-field references) maps to ONE typed instance —
        // merging any fields this copy adds. This is what collapses the same
        // category / nominee referenced from many places instead of duplicating it.
        String entityQid = source.qid();
        boolean realEntity = entityQid != null && WikidataIds.isQid(entityQid)
                && !ownedComponentTypes.contains(type);
        ModeledEntityKey entityKey = new ModeledEntityKey(type, entityQid);
        if (realEntity) {
            Object byQid = generatedByQid.get(entityKey);
            if (byQid != null && !(byQid instanceof WikidataDynamicObject)) {
                generatedByDynamic.put(source, byQid);
                // Merge later on the same iterative worklist. Doing it here recursively
                // follows arbitrarily long entity-reference chains on the JVM stack.
                pendingPopulation.addLast(new Population(cr, byQid, source, true));
                return byQid;
            }
        }

        Object target = cr.generatedClass().getDeclaredConstructor().newInstance();
        generatedByDynamic.put(source, target);
        if (source != originalSource) generatedByDynamic.put(originalSource, target);
        if (realEntity) {
            generatedByQid.put(entityKey, target);
        }

        // Stable identity + display come from the source object at creation. Hidden
        // identities retain canonicalization bookkeeping; ordinary datasource fields
        // are populated through their provider declarations below.
        if (target instanceof quiz.source.GeneratedEntity ge) {
            String canonicalKey = canonicalSourceKeyByType.getOrDefault(type, Map.of())
                    .get(originalSource);
            boolean contentKey = cr.model().classKind()
                    == wikidata.explore.model.ClassKind.SOURCE
                    && !cr.model().canonical().keyFields().isEmpty();
            ge.identifier(contentKey && canonicalKey != null
                    ? canonicalKey : source.getIdentifier());
            ge.label(source.getDisplayName());
            ge.sourceIdentities(canonicalSourceIdentitiesByType
                    .getOrDefault(type, Map.of()).getOrDefault(originalSource,
                            source.qid().isBlank() ? List.of()
                                    : List.of("wikidata:" + source.qid())));
            ge.occurrenceIdentities(canonicalOccurrenceIdentitiesByType
                    .getOrDefault(type, Map.of()).getOrDefault(originalSource,
                            wikidata.WikidataIds.isStatementId(source.getIdentifier())
                                    ? List.of("wikidata:" + source.getIdentifier())
                                    : List.of()));
            if (wikidata.explore.model.ClassSourceBindings
                    .aliasesEnabled(cr.model())) {
                assignAliases(target, source.aliases());
            }
            ge.part(source.isPart());
        }

        pendingPopulation.addLast(new Population(cr, target, source, false));

        return target;
    }

    /** Populates the conditionally generated alias field without putting it back on
     *  the universal carrier, where it would become part of every class's schema. */
    private static void assignAliases(Object target, java.util.Collection<String> values)
            throws IllegalAccessException {
        Field field = findField(target.getClass(), "alternateNames");
        if (field == null) return;
        field.setAccessible(true);
        Object existing = field.get(target);
        if (existing instanceof java.util.Collection<?> collection) {
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> writable =
                    (java.util.Collection<Object>) collection;
            writable.clear();
            if (values != null) writable.addAll(values);
        }
    }

    /**
     * Copies {@code source}'s field values into {@code target} per the class model.
     * With {@code onlyIfNull} it fills only fields not yet set — used to MERGE a
     * second WDO copy of the same entity into its existing typed instance without
     * clobbering already-mapped values.
     */
    private void applyFields(GeneratedViewableRuntime.ClassRuntime cr, Object target,
                             WikidataDynamicObject source, boolean onlyIfNull)
            throws Exception {
        // Generated subclasses are flattened: their Java class declares the base
        // fields too. Populate that same effective field set. Iterating only the
        // subclass's own declarations left those compiled inherited fields at their
        // empty defaults even though search/sort/view configuration correctly listed
        // them from the effective schema.
        for (ModeledFieldBinding binding : modeledFields(cr)) {
            GeneratedFieldModel fieldModel = binding.model();
            Field javaField = binding.field();

            boolean collection =
                    fieldModel.cardinality() == FieldCardinality.COLLECTION;
            Object existing = javaField.get(target);

            // On MERGE (onlyIfNull), a scalar that's already set stays. A
            // collection, however, is UNIONED: the same entity arrives as several
            // WDO copies, each carrying only its own subset (e.g. one category per
            // nomination), so first-wins would drop the rest of the targets.
            if (onlyIfNull && existing != null && !collection) continue;

            Object raw = source.get(fieldModel.name());
            if (raw == null) continue;

            Object mapped = mapFieldValue(fieldModel, raw);
            if (mapped == null) continue;

            if (onlyIfNull && collection
                    && existing instanceof List<?> && mapped instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<Object> existingList = (List<Object>) existing;
                for (Object item : (List<?>) mapped) {
                    if (!existingList.contains(item)) existingList.add(item);
                }
                continue;
            }

            try {
                javaField.set(target, mapped);
            } catch (IllegalArgumentException typeMismatch) {
                // The extracted value doesn't fit the generated field's type
                // (e.g. an "unknown value"/genid for an entity field that
                // came through as text). Skip it rather than abort the run.
            }
        }
    }

    /** Populates the ordinary fields contributed by this class's datasource config. */
    private void applyDatasourceFields(
            GeneratedViewableRuntime.ClassRuntime cr,
            Object target,
            WikidataDynamicObject source,
            boolean merge) throws IllegalAccessException {
        for (DatasourceFieldBinding binding : datasourceFields(cr)) {
            datasource.api.DatasourceInstanceField declaration = binding.declaration();
            Field javaField = binding.field();
            Object value = declaration.value(source);
            if (value == null) continue;
            // Provider declarations are allowed to return immutable collection
            // values (the Wikidata source field deliberately uses List.of/copyOf).
            // A later projection of the same QID must union its provider values into
            // the already materialized instance, so never install the provider's
            // collection itself as the generated field's mutable accumulator.
            if (declaration.valueSchema().collection()
                    && value instanceof java.util.Collection<?> values) {
                value = new java.util.ArrayList<>(values);
            }
            Object existing = javaField.get(target);
            if (merge && existing instanceof java.util.Collection<?> oldValues
                    && value instanceof java.util.Collection<?> newValues) {
                @SuppressWarnings("unchecked")
                java.util.Collection<Object> writable =
                        (java.util.Collection<Object>) oldValues;
                for (Object item : newValues) {
                    if (item != null && !writable.contains(item)) writable.add(item);
                }
            } else if (!merge || existing == null) {
                javaField.set(target, value);
            }
        }
    }

    private List<ModeledFieldBinding> modeledFields(
            GeneratedViewableRuntime.ClassRuntime cr) {
        return modeledFieldBindings.computeIfAbsent(cr.model().className(), ignored -> {
            List<GeneratedFieldModel> models = runtime.project() == null
                    ? cr.model().fields()
                    : cr.model().effectiveFields(runtime.project());
            List<ModeledFieldBinding> bindings = new ArrayList<>();
            for (GeneratedFieldModel model : models) {
                if (model == null || model.isNameField()) continue;
                Field field = findField(cr.generatedClass(),
                        GeneratedViewableSourceGenerator.sanitizeFieldName(model.name()));
                if (field != null) bindings.add(new ModeledFieldBinding(model, field));
            }
            return List.copyOf(bindings);
        });
    }

    private List<DatasourceFieldBinding> datasourceFields(
            GeneratedViewableRuntime.ClassRuntime cr) {
        return datasourceFieldBindings.computeIfAbsent(cr.model().className(), ignored -> {
            List<DatasourceFieldBinding> bindings = new ArrayList<>();
            for (wikidata.explore.model.ConfiguredInstanceFields.Field configured
                    : wikidata.explore.model.ConfiguredInstanceFields.of(
                            cr.model(), runtime.project(), datasourceRegistry)) {
                datasource.api.DatasourceInstanceField declaration =
                        configured.declaration();
                Field field = findField(cr.generatedClass(),
                        GeneratedViewableSourceGenerator.sanitizeFieldName(
                                declaration.name()));
                if (field != null) {
                    bindings.add(new DatasourceFieldBinding(declaration, field));
                }
            }
            return List.copyOf(bindings);
        });
    }


    /** The displayName to force onto a materialized object from its class's
     *  {@link CanonicalSpec}, or null to leave the default (the loaded label). Only
     *  an explicit FIELD/TEMPLATE displayName overrides; identity kind is
     *  independent from display policy. */
    static String canonicalDisplayNameOverride(
            GeneratedClassModel model,
            Canonicalizer.FieldReader reader,
            String fallbackLabel) {

        if (model == null) {
            return null;
        }
        CanonicalSpec spec = model.canonical();
        if (spec.displayNameMode() == CanonicalSpec.DisplayNameMode.LABEL) {
            return null;
        }
        String dn = Canonicalizer.displayName(spec, reader, fallbackLabel);
        return dn == null || dn.isBlank() ? null : dn;
    }

    private Object mapFieldValue(GeneratedFieldModel fieldModel, Object raw) throws Exception {
        if (fieldModel.cardinality() == FieldCardinality.COLLECTION) {
            List<Object> list = new ArrayList<>();
            if (raw instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    Object mapped = mapSingleValue(fieldModel, item);
                    if (mapped != null) list.add(mapped);
                }
            } else {
                Object mapped = mapSingleValue(fieldModel, raw);
                if (mapped != null) list.add(mapped);
            }
            return list;
        }

        // Single-valued field, but Wikidata can return several values for a
        // property (e.g. a constellation "named after" multiple figures), which
        // the extractor merges into a List. The generated field holds one
        // value, so map the first mappable element and drop the rest rather
        // than crashing on a List -> scalar assignment.
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Object mapped = mapSingleValue(fieldModel, item);
                if (mapped != null) {
                    return mapped;
                }
            }
            return null;
        }
        return mapSingleValue(fieldModel, raw);
    }

    private Object mapSingleValue(GeneratedFieldModel fieldModel, Object raw) throws Exception {
        if (raw == null) return null;
        if (effectiveType(fieldModel) == FieldType.IMAGE) {
            if (raw instanceof ImagePane) {
                return raw;
            }

            if (raw instanceof objectview.media.MediaValue media) {
                return toImagePane(media);
            }

            return null;
        }

        if (fieldModel.type() == FieldType.ENTITY) {
            // An entity field must hold a Viewable. A non-entity value (e.g. a
            // P61 "unknown value"/genid that arrived as text) can't go into a
            // objectview.Viewable field — drop it rather than crash the run.
            if (raw instanceof WikidataDynamicObject dyn) {
                String expected = fieldModel.entityClassName();
                if (dyn.hasTypeStamp() && expected != null && !expected.isBlank()
                        && runtime.project() != null
                        && runtime.project().findClass(expected) != null
                        && !wikidata.explore.model.EntityRepresentations.fieldAccepts(
                                runtime.project(), expected, dyn.directClassNames())) {
                    refusedReferences.merge(
                            fieldModel.name() + ": " + expected + " cannot hold "
                                    + dyn.directClassNames(), 1, Integer::sum);
                    return null;
                }
                return mapObject(dyn, fieldModel.entityClassName());
            }
            return raw instanceof Viewable ? raw : null;
        }
        if (raw instanceof WikidataDynamicObject dyn) {
            return mapObject(dyn, fieldModel.entityClassName());
        }
        if (fieldModel.type() == FieldType.NUMBER) {
            String unit = fieldModel.unit();   // resolved once per field
            if (raw instanceof quiz.Quantity q) return q;
            if (raw instanceof Number n) return new quiz.Quantity(n.doubleValue(), unit);
            try { return new quiz.Quantity(Double.parseDouble(String.valueOf(raw)), unit); }
            catch (Exception ignored) { return null; }
        }
        if (effectiveType(fieldModel) == FieldType.DATE) {
            return formatWikidataDate(String.valueOf(raw));
        }

        return raw;
    }

    // Wikidata times are [+-]YYYY-MM-DDThh:mm:ssZ; the truthy value loses the
    // precision, so collapse the common year-precision form (…-01-01T…) to just
    // the year ("1875", "5000 BC") and otherwise drop the time-of-day.
    private static final java.util.regex.Pattern WD_TIME =
            java.util.regex.Pattern.compile("^([+-]?)0*(\\d+)-(\\d{2})-(\\d{2})T");

    static String formatWikidataDate(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = WD_TIME.matcher(s);
        if (!m.find()) {
            return s;
        }
        boolean bc = "-".equals(m.group(1));
        String year = m.group(2);
        String mm = m.group(3);
        String dd = m.group(4);
        String body = (mm.equals("01") && dd.equals("01"))
                ? year                                   // year precision
                : year + "-" + mm + (dd.equals("01") ? "" : "-" + dd);
        return bc ? body + " BC" : body;
    }

    private FieldType effectiveType(GeneratedFieldModel field) {
        String pid =
                field.mapping() == null ? "" : field.mapping().propertyPid();

        if ("P18".equals(pid) || "P242".equals(pid)) {
            return FieldType.IMAGE;
        }

        return field.type();
    }

    private ImagePane toImagePane(objectview.media.MediaValue media) {
        try {
            return new ImagePane(
                    media.mediaLabel(),
                    media.mediaUrl(),
                    null,
                    false,
                    media.mediaSvg(),
                    false);   // loadThumbnailImmediately = false

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> fields = new java.util.HashMap<>();
            for (Class<?> current = type; current != null;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    field.setAccessible(true);
                    fields.putIfAbsent(field.getName(), field);
                }
            }
            return Map.copyOf(fields);
        }
    };

    private static Field findField(Class<?> cls, String name) {
        return cls == null || name == null ? null : FIELDS.get(cls).get(name);
    }
}
