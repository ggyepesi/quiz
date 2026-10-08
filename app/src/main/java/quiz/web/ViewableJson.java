package quiz.web;

import wikidata.explore.extract.SnapshotDomain;

import wikidata.explore.extract.WikidataDynamicObject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import objectview.media.ImageRef;
import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.field.FieldPath;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import objectview.field.FieldSet;
import objectview.plan.Disclosure;
import objectview.plan.DecisionRenderer;
import objectview.plan.PlanResolver;
import objectview.plan.RenderExecutor;
import objectview.plan.RenderSink;
import objectview.plan.Representation;
import objectview.plan.TypeShape;
import objectview.plan.ViewConfigDesugar;
import objectview.plan.ViewDefaults;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigJsonIO;

import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a {@link ViewableView} from any {@link Viewable}: the web sink of the shared
 * {@link RenderExecutor}. A card's config is its type's saved config, else the one
 * default, rewritten into literal ticks where it enters; the executor decides what is
 * shown, how and where it navigates, exactly as for the desktop card, and this class
 * only paints those decisions as JSON. Also the quiz's field and value readers.
 */
public final class ViewableJson {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ViewableJson() {}

    /** The card of {@code q} under its type's saved config, else the default. */
    public static ViewableView of(Viewable q) {
        return of(q, (ViewConfig) null);
    }

    /** The card of {@code q} under {@code config} instead of its saved one. */
    static ViewableView of(Viewable q, ViewConfig config) {
        return card(q, literalFor(q, config), q, FieldPath.ROOT, executor());
    }

    /** Test/adapter entry point that applies the same disclosure state as another
     * renderer. Production cards use {@link Disclosure#INITIAL}. */
    static ViewableView of(Viewable q, ViewConfig config, Disclosure disclosure) {
        return card(q, literalFor(q, config), q, FieldPath.ROOT, executor(disclosure));
    }

    /** A collection member fetched by its chip: the projection the collection's config
     * gives it, i.e. the child config at {@code path} of {@code root}'s card config. */
    public static ViewableView member(Viewable q, Viewable root, String path) {
        return member(q, root, null, path);
    }

    /** {@link #member} with {@code root}'s card under {@code rootConfig} instead of its
     * saved one. */
    static ViewableView member(Viewable q, Viewable root, ViewConfig rootConfig,
                               String path) {
        FieldPath at = path == null || path.isBlank() ? FieldPath.ROOT : FieldPath.parse(path);
        ViewConfig config = literalFor(root, rootConfig);
        for (String segment : at.segments()) {
            config = config == null ? null : config.getFieldConfig(segment);
            // An inherited field reads its ancestor's config (#368).
            if (config != null) config = config.effective();
        }
        return card(q, config == null ? ViewConfig.leaf() : config, root, at, executor());
    }

    public static String json(Viewable q) {
        try {
            return MAPPER.writeValueAsString(of(q));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize " + q, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Render-model for a (possibly dotted) field path of {@code owner}, or
     *  null. A path like {@code namedAfter.area} walks the reference(s) then
     *  reads the leaf field — so quizzes can use nested fields. */
    public static ViewableView.Field fieldOf(Viewable owner, String path) {
        int dot = path == null ? -1 : path.indexOf('.');
        if (dot < 0) {
            return fieldOfSingle(owner, path);
        }
        String seg = path.substring(0, dot);
        String rest = path.substring(dot + 1);

        // Fan a collection segment out to ALL members so e.g.
        // sharesBorderWith.chart yields every neighbour's chart (an image
        // strip), not just the first.
        List<Viewable> targets = stepIntoAll(owner, seg);
        if (targets.isEmpty()) {
            return null;
        }
        if (targets.size() == 1) {
            return fieldOf(targets.get(0), rest);
        }

        List<ViewableView.Field> leaves = new ArrayList<>();
        for (Viewable t : targets) {
            ViewableView.Field f = fieldOf(t, rest);
            if (f != null) {
                leaves.add(f);
            }
        }
        return combineLeaves(lastSegment(path), leaves);
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }

    // Combines the same leaf field gathered from several collection members:
    // images become one "images" strip; anything else becomes a "list" of each
    // member's value (so e.g. sharesBorderWith.name lists every neighbour name).
    private static ViewableView.Field combineLeaves(
            String name, List<ViewableView.Field> leaves) {
        if (leaves.isEmpty()) {
            return null;
        }
        boolean allImages = leaves.stream().allMatch(f -> "image".equals(f.kind()));
        if (allImages) {
            List<String> urls = new ArrayList<>();
            for (ViewableView.Field f : leaves) {
                if (f.url() != null) {
                    urls.add(f.url());
                }
            }
            return urls.isEmpty() ? null : ViewableView.Field.images(name, urls);
        }
        List<String> vals = new ArrayList<>();
        for (ViewableView.Field f : leaves) {
            String s = leafText(f);
            if (s != null && !s.isBlank()) {
                vals.add(s);
            }
        }
        return vals.isEmpty() ? null : ViewableView.Field.list(name, vals);
    }

    private static String leafText(ViewableView.Field f) {
        if (f.value() != null) {
            return f.value();
        }
        if (f.ref() != null) {
            return f.ref().name();
        }
        if (f.values() != null && !f.values().isEmpty()) {
            return String.join(", ", f.values());
        }
        if (f.refs() != null && !f.refs().isEmpty()) {
            List<String> ns = new ArrayList<>();
            for (ViewableView.Ref r : f.refs()) {
                ns.add(r.name());
            }
            return String.join(", ", ns);
        }
        return f.label();
    }

    /** The individual member values of a (possibly dotted) field path: a
     *  collection/map field yields one entry per member; a single field yields
     *  one entry. Lets a quiz ask about ONE member of a set (e.g. one of a
     *  constellation's bordering constellations) instead of the whole joined
     *  list. */
    public static List<String> stringValues(Viewable owner, String path) {
        int dot = path == null ? -1 : path.indexOf('.');
        if (dot < 0) {
            return stringValuesSingle(owner, path);
        }
        Viewable target = stepInto(owner, path.substring(0, dot));
        return target == null ? List.of() : stringValues(target, path.substring(dot + 1));
    }

    private static List<String> stringValuesSingle(Viewable owner, String fieldName) {
        List<String> out = new ArrayList<>();
        collectStrings(rawFieldValue(owner, fieldName), out);
        return out;
    }

    private static void collectStrings(Object v, List<String> out) {
        if (v == null) {
            return;
        }
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                collectStrings(o, out);
            }
            return;
        }
        if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                collectStrings(o, out);
            }
            return;
        }
        String s = asString(v);
        if (s != null && !s.isBlank()) {
            out.add(s);
        }
    }

    /** A plain string value of a (possibly dotted) field path. */
    public static String stringValue(Viewable owner, String path) {
        int dot = path == null ? -1 : path.indexOf('.');
        if (dot < 0) {
            return stringValueSingle(owner, path);
        }
        Viewable target = stepInto(owner, path.substring(0, dot));
        return target == null ? null : stringValue(target, path.substring(dot + 1));
    }

    /** An image strip for a collection image path (e.g. sharesBorderWith.chart)
     *  where each member's image is the name-BLURRING chart endpoint for that
     *  member — used when the member name isn't being revealed, so the chart
     *  doesn't give the answer away. Null if no member has such an image. */
    public static ViewableView.Field blurredImageStrip(Viewable owner, String path) {
        String leaf = lastSegment(path);
        String parent = path.contains(".")
                ? path.substring(0, path.lastIndexOf('.'))
                : "";
        List<String> urls = new ArrayList<>();
        for (Viewable m : resolveAll(owner, parent)) {
            ViewableView.Field lf = fieldOf(m, leaf);
            if (lf != null && "image".equals(lf.kind())) {
                urls.add(BlurredImageService.blurUrl(m.typeName(), m.getIdentifier(), leaf));
            }
        }
        if (urls.isEmpty()) {
            return null;
        }
        return urls.size() == 1
                ? ViewableView.Field.image(leaf, urls.get(0))
                : ViewableView.Field.images(leaf, urls);
    }

    // All objects reached by walking a dotted path, fanning out at every
    // collection segment (so the parent of a strip yields all members).
    private static List<Viewable> resolveAll(Viewable owner, String path) {
        List<Viewable> cur = new ArrayList<>();
        cur.add(owner);
        if (path == null || path.isBlank()) {
            return cur;
        }
        for (String seg : path.split("\\.")) {
            List<Viewable> next = new ArrayList<>();
            for (Viewable o : cur) {
                next.addAll(stepIntoAll(o, seg));
            }
            cur = next;
            if (cur.isEmpty()) {
                break;
            }
        }
        return cur;
    }

    /** The object reached by walking a dotted path (every segment a reference),
     *  or {@code owner} for an empty path, or null if a step has no target. */
    public static Viewable resolvePath(Viewable owner, String path) {
        if (path == null || path.isBlank()) {
            return owner;
        }
        Viewable cur = owner;
        for (String seg : path.split("\\.")) {
            if (cur == null) {
                return null;
            }
            cur = stepInto(cur, seg);
        }
        return cur;
    }

    // Resolves one path segment to a referenced Viewable (the first one, if the
    // field is a collection/map of references).
    private static Viewable stepInto(Viewable owner, String segment) {
        if (owner == null || segment == null) {
            return null;
        }
        Object v = rawFieldValue(owner, segment);
        if (v instanceof Viewable q) {
            return q;
        }
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof Viewable q) {
                    return q;
                }
            }
        }
        if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                if (o instanceof Viewable q) {
                    return q;
                }
            }
        }
        return null;
    }

    // All referenced Viewables for a path segment (every member of a
    // collection/map, or the single referenced object).
    private static List<Viewable> stepIntoAll(Viewable owner, String segment) {
        if (owner == null || segment == null) {
            return List.of();
        }
        Object v = rawFieldValue(owner, segment);
        List<Viewable> out = new ArrayList<>();
        if (v instanceof Viewable q) {
            out.add(q);
        } else if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof Viewable q) {
                    out.add(q);
                }
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                if (o instanceof Viewable q) {
                    out.add(q);
                }
            }
        }
        return out;
    }

    private static Object rawFieldValue(Viewable owner, String name) {
        // The ONE shared access path, including contract fields that are declared but
        // not stored. In particular @view:display reads getDisplayName(); reading the
        // backing FieldSet directly returned null even though /api/fields offered the
        // field, so a quiz using “Display label” produced zero questions.
        return objectview.field.FieldAccess.getPathValues(
                owner, objectview.field.FieldPath.parse(name));
    }

    /** Render-model for a single named field of {@code owner}, or null. */
    private static ViewableView.Field fieldOfSingle(Viewable owner, String fieldName) {
        // Resolve metadata from the FieldSet and the value through the shared path
        // reader, then use the common builder. No dynamic/declared fork.
        objectview.field.FieldSet fs = objectview.field.FieldSet.of(owner);
        objectview.field.FieldRef fr = fs.field(fieldName);
        if (fr == null) {
            return null;
        }
        Object value = rawFieldValue(owner, fieldName);
        if (!ViewableAdapter.isValidQuizValue(value)) {
            return null;
        }
        if (fr.role().renderedInHeader()) {
            return ViewableView.Field.text(fieldName, asString(value));
        }
        // The one field under the child config the card gives it, painted by the
        // same sink as the card.
        ViewConfig child = literalFor(owner, null).getFieldConfig(fieldName);
        ViewConfig one = ViewConfig.leaf();
        one.addField(fieldName, child == null ? ViewConfig.leaf() : child);
        return card(owner, one, owner, FieldPath.ROOT, executor()).fields().stream()
                .filter(field -> fieldName.equals(field.name()))
                .findFirst().orElse(null);
    }

    /**
     * A plain string value of a field for use as a quiz answer/option:
     * Viewable -> display name, collection/map -> joined items, else the
     * value's string. Null if empty.
     */
    private static String stringValueSingle(Viewable owner, String fieldName) {
        // Both backings and synthetic contract fields resolve through the same path
        // reader, then stringify. No dynamic/declared fork.
        String s = asString(rawFieldValue(owner, fieldName));
        return s == null || s.isBlank() ? null : s;
    }

    private static String asString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Viewable q) {
            return q.getDisplayName();
        }
        if (v instanceof Collection<?> c) {
            return joinItems(c);
        }
        if (v instanceof Map<?, ?> m) {
            return joinItems(m.values());
        }
        return String.valueOf(v);
    }

    private static String joinItems(Collection<?> items) {
        List<String> parts = new ArrayList<>();
        for (Object item : items) {
            String s = item instanceof Viewable q ? q.getDisplayName() : String.valueOf(item);
            if (s != null && !s.isBlank()) {
                parts.add(s);
            }
        }
        return String.join(", ", parts);
    }

    // ---- The card: a sink of the shared executor ----------------------------

    // Per-type saved view config, keyed by domain typeName so one config drives both
    // surfaces. A sentinel marks "looked up, none found" so we don't re-read.
    private static final ViewConfig NO_CONFIG = new ViewConfig();
    private static final Map<String, ViewConfig> CONFIG_CACHE = new ConcurrentHashMap<>();

    // Per-type STRUCTURAL fields — plumbing that isn't a user-facing attribute, e.g. a
    // reified statement class's "source" back-reference. Populated by the serving source
    // (which has the model) at register time. They reach the executor as the schema's
    // structural flag, so shorthand never includes them (rule 11) and a tick still shows
    // them, exactly as on the desktop.
    private static final Map<String, Set<String>> STRUCTURAL_BY_TYPE =
            new ConcurrentHashMap<>();

    // The schemas of the served types, by name: each served snapshot registers its field
    // graph. A nested field's target type is looked up here, never read off its value.
    private static final List<java.util.function.Function<String, FieldSchema>> TYPE_SCHEMAS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Registers a source of served type schemas (a snapshot's field graph). */
    public static void registerTypeSchemas(
            java.util.function.Function<String, FieldSchema> schemas) {
        if (schemas != null) TYPE_SCHEMAS.add(schemas);
    }

    private static FieldSchema typeSchema(String typeName) {
        if (typeName == null) return null;
        for (java.util.function.Function<String, FieldSchema> schemas : TYPE_SCHEMAS) {
            FieldSchema schema = schemas.apply(typeName);
            if (schema != null) return schema;
        }
        return null;
    }

    /** Registers the fields to hide for a served type (see {@link #STRUCTURAL_BY_TYPE}). */
    public static void registerStructural(String type, Set<String> fields) {
        if (type != null && !type.isBlank() && fields != null && !fields.isEmpty()) {
            STRUCTURAL_BY_TYPE.put(type, Set.copyOf(fields));
        }
    }

    private static ViewConfig savedConfig(String typeName) {
        if (typeName == null || typeName.isBlank()) return null;
        ViewConfig c = CONFIG_CACHE.computeIfAbsent(typeName, t -> {
            ViewConfig loaded = ViewConfigJsonIO.loadForType(t);
            return loaded == null ? NO_CONFIG : loaded;
        });
        return c == NO_CONFIG ? null : c;
    }

    /** The literal config {@code q}'s card renders under: {@code given}, else its type's
     * saved config, else the one default — rewritten here, where it enters the web. */
    static ViewConfig literalFor(Viewable q, ViewConfig given) {
        TypeShape shape = TypeShape.of(q, schema(q), ViewableJson::typeSchema);
        ViewConfig config = given != null ? given : savedConfig(q.typeName());
        return config == null ? ViewDefaults.newView(shape)
                : ViewConfigDesugar.preparedView(config, shape);
    }

    /** The schema the web renders {@code q} with: its type's served schema, else the one
     * a loaded object carries, with the registered structural fields and internal "__"
     * plumbing marked structural. Null for a declared class, whose reflected fields are
     * its schema. Never the keys an object happens to hold. */
    private static FieldSchema schema(Viewable q) {
        FieldSchema base = typeSchema(q.typeName());
        if (base == null) base = FieldSet.carriedSchema(q);
        if (base == null) return null;
        Set<String> structural = STRUCTURAL_BY_TYPE.getOrDefault(q.typeName(), Set.of());
        List<FieldRef> fields = new ArrayList<>();
        for (FieldRef field : base.fields()) {
            boolean plumbing = structural.contains(field.name())
                    || (field.name() != null && field.name().startsWith("__"));
            fields.add(plumbing && !field.structural()
                    ? FieldRef.withStructural(field, true) : field);
        }
        return () -> fields;
    }

    /** Whether {@code q} has a card of its own on the web: a pooled entity of a served
     * domain type, which a chip fetches by id. Rule 7 makes a scalar reference to it a
     * navigation chip. */
    private static boolean navigable(Viewable q) {
        return !isValueObject(q) && !isBlank(q.getIdentifier())
                && !q.typeName().equals(q.getClass().getSimpleName());
    }

    /** The fields of {@code instance}'s type, or of the type {@code path} leads to from
     * it: read from the schemas, never from the values any instance holds. */
    public static List<FieldRef> typeFields(Viewable instance, String path) {
        if (instance == null) return List.of();
        TypeShape shape = TypeShape.of(instance, schema(instance), ViewableJson::typeSchema);
        if (path != null && !path.isBlank()) {
            for (String segment : FieldPath.parse(path).segments()) {
                FieldRef field = shape == null ? null : shape.fields().stream()
                        .filter(f -> f.name().equals(segment)).findFirst().orElse(null);
                shape = field == null ? null : shape.nested(field);
            }
        }
        return shape == null ? List.of() : shape.fields();
    }

    /** The kind a field is painted as on the web, from its schema. */
    public static String webKind(FieldRef field) {
        boolean media = field.kind() == objectview.field.FieldKind.MEDIA
                || field.valueKind() == objectview.field.FieldKind.MEDIA;
        boolean collection = field.collection()
                || field.kind() == objectview.field.FieldKind.COLLECTION;
        if (media) return collection ? "images" : "image";
        if (field.reference() || field.annotatedReference()) return collection ? "refs" : "ref";
        if (field.link()) return "link";
        return collection ? "list" : "text";
    }

    private static RenderExecutor executor() {
        return executor(Disclosure.INITIAL);
    }

    /** The executor the web renders with; the disclosure is the client's to change. */
    static RenderExecutor executor(Disclosure disclosure) {
        return new RenderExecutor(new PlanResolver(), ViewableJson::schema,
                ViewableJson::navigable, disclosure);
    }

    /** Serializes the card of {@code q} under {@code literal}; config paths start at
     * {@code base}, and {@code root} is the card whose config that is. */
    private static ViewableView card(Viewable q, ViewConfig literal, Viewable root,
                                     FieldPath base, RenderExecutor executor) {
        RenderExecutor.Level level = executor.level(q, literal);
        Set<Viewable> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
        ancestors.add(q);
        return new Sink(executor, root).card(level,
                new RenderSink.Occurrence(base, "", ""), ancestors);
    }

    /** Paints executor decisions as JSON. It makes no selection, representation,
     * navigation or caption decision of its own. */
    private record Sink(RenderExecutor executor, Viewable root)
            implements DecisionRenderer<ViewableView.Field, Sink.Context> {

        private record Context(Viewable owner, Set<Viewable> ancestors) {}

        ViewableView card(RenderExecutor.Level level, RenderSink.Occurrence at,
                          Set<Viewable> ancestors) {
            Viewable q = level.target();
            List<ViewableView.Field> fields = new ArrayList<>();
            for (RenderExecutor.Decision decision : executor.fields(level, at, ancestors)) {
                ViewableView.Field field = field(q, decision, ancestors);
                if (field != null) fields.add(field);
            }
            return new ViewableView(q.getIdentifier(),
                    level.caption() == null ? "" : level.caption(), q.typeName(), fields);
        }

        private ViewableView.Field field(Viewable owner, RenderExecutor.Decision decision,
                                         Set<Viewable> ancestors) {
            return renderDecision(decision, new Context(owner, ancestors));
        }

        @Override public ViewableView.Field skip(RenderExecutor.Decision decision,
                                                 Context context) {
            return null;
        }

        @Override public ViewableView.Field leaf(RenderExecutor.Decision decision,
                                                 Context context) {
            return ViewableJson.leaf(
                    context.owner(), decision.field().field(), decision.value());
        }

        @Override public ViewableView.Field navigation(RenderExecutor.Decision decision,
                                                       Context context) {
            return ViewableView.Field.ref(decision.field().name(),
                    navigation(decision.object()));
        }

        @Override public ViewableView.Field backReference(RenderExecutor.Decision decision,
                                                          Context context) {
            return backReference(decision.field().name(), decision.object());
        }

        @Override public ViewableView.Field object(RenderExecutor.Decision decision,
                                                   Context context) {
            return object(decision.field().name(), decision, context.ancestors());
        }

        @Override public ViewableView.Field collection(RenderExecutor.Decision decision,
                                                       Context context) {
            return collection(context.owner(), decision.field().name(), decision,
                    context.ancestors());
        }

        private static ViewableView.Ref navigation(RenderExecutor.Level object) {
            Viewable target = object.target();
            return new ViewableView.Ref(target.getIdentifier(),
                    object.caption() == null
                            ? objectview.render.ReferenceRow.NAVIGATION_LABEL
                            : object.caption(),
                    target.typeName(), thumb(object), null);
        }

        private static ViewableView.Field backReference(String name,
                                                        RenderExecutor.Level object) {
            if (navigable(object.target())) {
                return ViewableView.Field.ref(name, navigation(object));
            }
            return ViewableView.Field.text(name,
                    object.caption() == null ? "↩" : object.caption());
        }

        /**
         * An object occurrence, as the desktop card paints it. With nothing ticked under
         * it besides its caption it is a value: its caption, or its field name alone
         * (rule 3). Otherwise it is open in place (rule 6), under its caption when ticked.
         */
        private ViewableView.Field object(String name, RenderExecutor.Decision decision,
                                          Set<Viewable> ancestors) {
            RenderExecutor.Level object = decision.object();
            if (!object.hasBody()) {
                if (object.caption() == null) return ViewableView.Field.empty(name);
                String external = externalUrl(object.target());
                return external == null
                        ? ViewableView.Field.text(name, object.caption())
                        : ViewableView.Field.link(name, object.caption(), external);
            }
            if (!decision.open()) {
                // A folded object (an inherited level, #368) is a chip the reader opens:
                // fetched under the config at its path, so each open reads one level.
                Viewable target = object.target();
                String chip = object.caption() != null ? object.caption() : name;
                if (navigable(target) && root != null) {
                    return ViewableView.Field.ref(name, new ViewableView.Ref(
                            target.getIdentifier(), chip, target.typeName(), thumb(object),
                            null, new ViewableView.Via(root.typeName(),
                                    root.getIdentifier(), decision.at().path().toString())));
                }
                // A value object has no pool address to fetch, but folding must not
                // discard its configured body. Carry the same projection inline; the
                // client still builds/paints it only when the chip is opened.
                return ViewableView.Field.ref(name, new ViewableView.Ref(
                        null, chip, target.typeName(), thumb(object),
                        nested(object, decision.at(), ancestors)));
            }
            return ViewableView.Field.inline(name,
                    List.of(nested(object, decision.at(), ancestors)));
        }

        private ViewableView nested(RenderExecutor.Level object, RenderSink.Occurrence at,
                                    Set<Viewable> ancestors) {
            ancestors.add(object.target());
            try {
                return card(object, at, ancestors);
            } finally {
                ancestors.remove(object.target());
            }
        }

        /** {@code name (size)}, always; its members, each decided by the executor. */
        private ViewableView.Field collection(Viewable owner, String name,
                                              RenderExecutor.Decision decision,
                                              Set<Viewable> ancestors) {
            Collection<?> items = RenderExecutor.members(decision.value());
            boolean open = decision.open();
            List<String> images = images(owner, name, items);
            if (!images.isEmpty()) {
                return ViewableView.Field.images(name, images).collection(items.size(), open);
            }

            List<ViewableView.Ref> refs = new ArrayList<>();
            List<String> values = new ArrayList<>();
            int index = 0;
            for (Object item : items) {
                RenderExecutor.Decision member = executor.member(decision.field(), item,
                        decision.at().member(index++), ancestors);
                switch (member.kind()) {
                    case OBJECT -> {
                        ViewableView.Ref ref = member(member, ancestors);
                        if (ref != null) refs.add(ref);
                    }
                    case NAVIGATION, BACK_REFERENCE -> refs.add(navigation(member.object()));
                    case LEAF -> {
                        String text = String.valueOf(member.value());
                        if (!text.isBlank()) values.add(text);
                    }
                    default -> { }
                }
            }
            ViewableView.Field field = !refs.isEmpty()
                    ? ViewableView.Field.refs(name, refs)
                    : ViewableView.Field.list(name, values);
            return field.collection(decision.size(), open);
        }

        /**
         * A collection member is the collection's projection (rule 7), built when the
         * reader opens it (rule 5). A pooled entity's chip fetches it by id under the
         * collection's config path; any other member carries its projection inline.
         * With nothing ticked below its caption it is the caption alone, and with no
         * caption either it shows nothing, as on the desktop.
         */
        private ViewableView.Ref member(RenderExecutor.Decision member,
                                        Set<Viewable> ancestors) {
            RenderExecutor.Level object = member.object();
            Viewable target = object.target();
            String caption = object.caption() == null ? "" : object.caption();
            if (!object.hasBody()) {
                return object.caption() == null ? null : new ViewableView.Ref(
                        null, caption, target.typeName(), thumb(object), null);
            }
            if (navigable(target) && root != null) {
                return new ViewableView.Ref(target.getIdentifier(), caption,
                        target.typeName(), thumb(object), null,
                        new ViewableView.Via(root.typeName(), root.getIdentifier(),
                                member.at().path().toString()));
            }
            return new ViewableView.Ref(null, caption, target.typeName(), thumb(object),
                    nested(object, member.at(), ancestors));
        }

        /** The avatar of a chip: its first ticked media field. An unticked field is
         * never read (rule 1). */
        private static String thumb(RenderExecutor.Level object) {
            Viewable target = object.target();
            for (objectview.plan.ObjectPlan.FieldPlan field : object.plan().body()) {
                if (field.representation() != Representation.MEDIA
                        && field.representation() != Representation.VALUE) continue;
                String url = mediaUrl(target, field.name(),
                        object.fields().read(field.name()));
                if (url != null) return url;
            }
            return null;
        }
    }

    // ---- Leaves -------------------------------------------------------------

    /** A text, link or media value: the shape decides, for declared and dynamic fields
     * alike. Scalar values, including both booleans, are rendered literally. */
    private static ViewableView.Field leaf(Viewable owner, FieldRef fr, Object value) {
        String name = fr.name();
        String media = mediaUrl(owner, name, value);
        if (media != null) return ViewableView.Field.image(name, media);
        if (fr.link() && value instanceof String s && !s.isBlank()) {
            return linkField(name, s, fr.linkText());
        }
        if (value instanceof String s && isHttp(s)) {
            // https so it isn't mixed-content-blocked on an https page.
            String url = httpsUrl(s);
            return isImageKey(name)
                    ? ViewableView.Field.image(name, url)       // e.g. a sky chart
                    : linkField(name, url, name);               // e.g. a wikidata link
        }
        return ViewableView.Field.text(name, String.valueOf(value));
    }

    /** The URL of a media value: http(s) media goes direct; a declared ImageRef or
     * local (file:/bundled) media is rendered by the server. Null for anything else. */
    private static String mediaUrl(Viewable owner, String field, Object value) {
        if (value instanceof ImageRef) {
            return imageApi(owner.typeName(), owner.getIdentifier(), field);
        }
        if (value instanceof objectview.media.MediaValue m
                && m.mediaUrl() != null && !m.mediaUrl().isBlank()) {
            return isHttp(m.mediaUrl()) ? httpsUrl(m.mediaUrl())
                    : imageApi(owner.typeName(), owner.getIdentifier(), field);
        }
        return null;
    }

    /** A collection of images (e.g. flag versions): one URL per media member, indexed
     * by position; empty when the collection holds anything else. */
    private static List<String> images(Viewable owner, String name, Collection<?> items) {
        List<String> urls = new ArrayList<>();
        int idx = 0;
        for (Object item : items) {
            if (item instanceof objectview.media.MediaValue m
                    && m.mediaUrl() != null && !m.mediaUrl().isBlank()
                    && isHttp(m.mediaUrl())) {
                urls.add(httpsUrl(m.mediaUrl()));        // browser-fetchable directly
            } else if (item instanceof ImageRef
                    || (item instanceof objectview.media.MediaValue m2
                            && m2.mediaUrl() != null && !m2.mediaUrl().isBlank())) {
                urls.add(imageApi(owner.typeName(), owner.getIdentifier(), name)
                        + "/" + idx);                    // server-rendered (file:/bundled)
            } else {
                return List.of();
            }
            idx++;
        }
        return urls;
    }

    // The value of the first non-blank @Link (URL) field on the object, if any
    // -- e.g. WikidataDynamicObject.wikidataUrl: where a caption-only object links to.
    private static String externalUrl(Viewable q) {
        for (Field f : ViewableAdapter.getAllFields(q.getClass())) {
            if (!ViewableAdapter.isLinkField(f)) {
                continue;
            }
            try {
                f.setAccessible(true);
                if (f.get(q) instanceof String s && isHttp(s)) {
                    return s;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean isImageKey(String name) {
        String n = name.toLowerCase();
        return n.contains("chart") || n.contains("image") || n.contains("img")
                || n.contains("photo") || n.contains("logo") || n.contains("portrait")
                || n.contains("flag");
    }

    private static boolean isHttp(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    // Upgrade http→https so an image/link isn't mixed-content-blocked when the
    // page itself is served over https.
    private static String httpsUrl(String s) {
        return s != null && s.startsWith("http://") ? "https://" + s.substring(7) : s;
    }

    private static ViewableView.Field linkField(String name, String rawValue, String annotationText) {
        String label = null;
        String url = rawValue;

        int bar = rawValue.indexOf('|');
        if (bar > 0 && bar < rawValue.length() - 1) {
            label = rawValue.substring(0, bar).trim();
            url = rawValue.substring(bar + 1).trim();
        }

        if (annotationText != null && !annotationText.isBlank()) {
            label = annotationText.trim();
        }

        return ViewableView.Field.link(name, label == null ? url : label, url);
    }

    /** A small render URL for {@code q}'s first media field (e.g. a Laureate's portrait),
     *  so a chip / list item can show an avatar — null if it has none. Http media goes
     *  direct; local (file:/bundled) media routes through the server render endpoint. */
    public static String thumbUrl(Viewable q) {
        objectview.field.FieldSet fs = objectview.field.FieldSet.of(q);
        for (objectview.field.FieldRef fr : fs.fields()) {
            Object v = fs.read(fr.name());
            if (v instanceof objectview.media.MediaValue m
                    && m.mediaUrl() != null && !m.mediaUrl().isBlank()) {
                return isHttp(m.mediaUrl())
                        ? httpsUrl(m.mediaUrl())
                        : imageApi(q.typeName(), q.getIdentifier(), fr.name());
            }
            if (v instanceof objectview.media.ImageRef) {
                return imageApi(q.typeName(), q.getIdentifier(), fr.name());
            }
        }
        return null;
    }

    private static String imageApi(String type, String id, String field) {
        return "/api/image/" + enc(type) + "/" + enc(id) + "/" + enc(field);
    }

    /** A value object either by its Java type (hand-written domain) or by the runtime
     *  flag carried on a snapshot-loaded {@link WikidataDynamicObject}. */
    private static boolean isValueObject(Viewable q) {
        return q instanceof quiz.ValueObject
                || (q instanceof wikidata.explore.extract.WikidataDynamicObject w
                        && w.isValueObject());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
