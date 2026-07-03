package wikidata.explore.transform;

import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a STATEMENT-reification class (a class with {@code statementSourceClass}
 * set) into a qualifier-load + reify, so the reified record is configured ON THE
 * MODEL with qualifier-sourced fields — not in a Transform file. E.g. a {@code
 * Nomination} class whose {@code statementSourceClass = Oscarnominations} and
 * relation {@code P1411}, with a value field {@code category} (the {@code ps:}
 * value) and qualifier fields {@code year} (P585), {@code forWork} (P1686),
 * {@code nominee} (P2453), becomes: load the P1411 statements of each
 * Oscarnominations with those qualifiers, then promote each to a {@code
 * Nomination{category, year, forWork, nominee}}. This is the qualifier field-source
 * realized via the existing {@link QualifierLoader} + {@link TransformEngine}.
 */
public final class ModelStatementReifications {

    private ModelStatementReifications() {}

    public record Reification(QualifierLoadConfig load, ReifyConstruct reify) {}

    public static List<Reification> derive(GeneratedProjectModel project) {
        List<Reification> out = new ArrayList<>();
        if (project == null) {
            return out;
        }
        for (GeneratedClassModel n : project.classes()) {
            if (n == null || !n.reifiesStatements()) {
                continue;
            }
            // Resolve to the actual class name (case-insensitively), so the
            // qualifier-load's entityType matches the pool's stamped typeName even
            // if the "Reifies statements of" label differs in case.
            GeneratedClassModel src = project.findClass(n.statementSourceClass());
            String stmtProp = clean(n.instanceMapping().propertyPid());
            if (src == null || !stmtProp.matches("P\\d+")) {
                continue;
            }
            String sourceClass = src.className();

            String valueField = null;
            String primaryListField = "";
            List<QualifierLoadConfig.Qualifier> quals = new ArrayList<>();
            List<ReifyConstruct.Role> roles = new ArrayList<>();
            List<String> dedup = new ArrayList<>();

            for (GeneratedFieldModel f : n.fields()) {
                if (f == null || f.isNameField()) {
                    continue;
                }
                // Derived fields (COMPANION_MATCH flags like `won`, INVERT
                // reverse-refs) are produced by later passes — NOT statement
                // qualifiers. `won` carries P1686 as its companion role qualifier,
                // which would otherwise be mis-loaded as a qualifier value AND put
                // in the dedup key, splitting the work/person copies at reify time
                // (won=null vs won=<work>) so they never collapse.
                if (f.mapping().productionKind()
                        != wikidata.explore.model.FieldProductionKind.AUTO) {
                    continue;
                }
                if (f.mapping().isQualifier()) {
                    QualifierLoadConfig.Kind kind = kindFor(f.type());
                    boolean multi = f.cardinality() != null
                            && f.cardinality().isCollection();
                    quals.add(new QualifierLoadConfig.Qualifier(
                            clean(f.mapping().qualifierPid()), f.name(), kind, multi));
                    // A DATE qualifier (the ceremony/event date) is an ATTRIBUTE of
                    // the statement, not part of its identity — and it's often
                    // missing on one denormalized copy, which would split otherwise
                    // identical work/person copies. Keep it as a field, not a key.
                    if (kind != QualifierLoadConfig.Kind.YEAR) {
                        dedup.add(f.name());
                    }
                    if (kind == QualifierLoadConfig.Kind.ENTITY) {
                        if (multi && primaryListField.isEmpty()) {
                            // A multi ENTITY qualifier (the shared-award nominee
                            // list) is the CANONICAL marker: the record that has it
                            // is the nomination; the loader fills it. Not a role.
                            primaryListField = f.name();
                        } else {
                            // A single ENTITY qualifier (e.g. forWork) is a role: its
                            // value, or the subject when absent — so the denormalized
                            // person-side copy resolves to the same work and is then
                            // dropped in favour of the canonical statement.
                            roles.add(new ReifyConstruct.Role(f.name(), f.name(), true));
                        }
                    }
                } else if (valueField == null
                        && stmtProp.equals(clean(f.mapping().propertyPid()))) {
                    valueField = f.name();      // the statement's ps: value
                }
            }
            if (valueField == null) {
                for (GeneratedFieldModel f : n.fields()) {
                    if (f != null && !f.isNameField() && !f.mapping().isQualifier()) {
                        valueField = f.name();
                        break;
                    }
                }
            }
            if (valueField == null) {
                valueField = "value";
            }
            dedup.add(0, valueField);

            // The class's sourceQid (if any) filters the statement value by P31 —
            // e.g. Q19020 keeps only Oscar-category statements.
            String valueTypeQid = clean(n.instanceMapping().sourceQid());

            QualifierLoadConfig load = new QualifierLoadConfig(
                    sourceClass, stmtProp, "__" + n.className(), n.className(),
                    valueField, valueTypeQid.matches("Q\\d+") ? valueTypeQid : "",
                    quals);
            ReifyConstruct reify = new ReifyConstruct(
                    sourceClass, "__" + n.className(), n.className(),
                    "source", "value", true, roles, dedup, primaryListField);
            out.add(new Reification(load, reify));
        }
        return out;
    }

    /**
     * Run the derived qualifier-loads + reifies (needs the client). {@code pool}
     * provides the source entities (whose statements get loaded); the created
     * records are RETURNED so the caller adds them to the canonical pool.
     */
    public static List<WikidataDynamicObject> apply(
            GeneratedProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {
        enrich(project, pool, client, log);
        return reify(project, pool, log);
    }

    /**
     * NETWORK stage: load each reification's qualifier values onto the statement
     * objects in {@code pool}. Split from {@link #reify} so a Remap can re-run the
     * pure reify on a cached copy of the enriched pool without re-fetching.
     */
    public static void enrich(
            GeneratedProjectModel project,
            List<WikidataDynamicObject> pool,
            WikidataSparqlClient client,
            GenerationLog log) {
        if (client == null) {
            return;
        }
        QualifierLoader loader = new QualifierLoader();
        for (Reification r : derive(project)) {
            loader.enrich(pool, r.load(), client, log);
        }
    }

    /**
     * PURE stage: reify the (already enriched) statements into records. No network,
     * so it can be re-run on a cached enriched pool.
     */
    public static List<WikidataDynamicObject> reify(
            GeneratedProjectModel project,
            List<WikidataDynamicObject> pool,
            GenerationLog log) {
        List<WikidataDynamicObject> created = new ArrayList<>();
        TransformEngine engine = new TransformEngine();
        for (Reification r : derive(project)) {
            List<WikidataDynamicObject> records = engine.applyReify(pool, r.reify());
            created.addAll(records);
            if (log != null) {
                log.message("Reify " + r.load().propertyPid() + " statements -> "
                        + r.reify().targetType() + ": " + records.size() + " records\n");
            }
        }
        return created;
    }

    private static QualifierLoadConfig.Kind kindFor(FieldType type) {
        if (type == FieldType.ENTITY) {
            return QualifierLoadConfig.Kind.ENTITY;
        }
        if (type == FieldType.DATE) {
            return QualifierLoadConfig.Kind.YEAR;
        }
        return QualifierLoadConfig.Kind.STRING;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
