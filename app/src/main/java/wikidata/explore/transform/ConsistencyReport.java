package wikidata.explore.transform;

import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.RoleKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-generation data-quality audit (#99). Serving only the finest atom (e.g.
 * Nomination) means member-level anomalies no longer surface on a card, so
 * inconsistencies must be probed for EXPLICITLY rather than noticed by luck.
 *
 * <p>Current check — <b>surviving phantoms</b>: a fully self-referential reified
 * atom (every role fell back to its own subject) whose subject is referenced
 * through a {@link RoleKind#REFERENCE} role (e.g. {@code forWork}) by a real atom
 * in the SAME slot (the reified value, e.g. category). That's a witness that
 * should have dropped the atom but didn't — the exact shape of the "The Whale"
 * bug. After the witness fix this count is 0; any non-zero is a heuristic
 * regression, and it surfaces in the generation log independent of what's served.
 *
 * <p>Reads only the final pool structurally (by QID), so it never trusts the very
 * transform whose output it audits.
 */
public final class ConsistencyReport {

    private ConsistencyReport() {}

    /** Audits every reify class in {@code project} over {@code atoms}; returns the
     *  total number of suspected surviving phantoms (0 = clean). */
    public static int check(CompiledProjectModel project,
                            List<WikidataDynamicObject> atoms, GenerationLog log) {
        if (project == null || atoms == null || atoms.isEmpty()) {
            return 0;
        }
        int suspects = 0;
        for (ModelStatementReifications.Reification reif
                : ModelStatementReifications.derive(project)) {
            suspects += checkReify(reif, atoms, log);
        }
        return suspects;
    }

    static int checkReify(ModelStatementReifications.Reification reif,
                          List<WikidataDynamicObject> atoms,
                          GenerationLog log) {
        ReifyConstruct rc = reif.reify();
        if (rc == null || !rc.promote()) {
            return 0;
        }
        String type = rc.targetType();
        String sourceField = rc.sourceField() == null || rc.sourceField().isBlank()
                ? rc.sourceType().toLowerCase() : rc.sourceField();
        String valueField = reif.load() == null ? null : reif.load().valueField();

        List<String> roleFields = new ArrayList<>();
        List<String> refRoleFields = new ArrayList<>();
        for (ReifyConstruct.Role r : rc.roles()) {
            if (r == null || r.field() == null || r.field().isBlank()) {
                continue;
            }
            roleFields.add(r.field());
            if (r.kind() == RoleKind.REFERENCE) {
                refRoleFields.add(r.field());
            }
        }
        if (type == null || valueField == null || valueField.isBlank()
                || roleFields.isEmpty() || refRoleFields.isEmpty()) {
            return 0;   // nothing to key an identity/witness on
        }

        List<WikidataDynamicObject> mine = new ArrayList<>();
        for (WikidataDynamicObject a : atoms) {
            if (a != null && type.equals(a.typeName())) {
                mine.add(a);
            }
        }

        // Per slot (the reified value, e.g. category): the subject QIDs referenced
        // through a REAL reference role — a real atom's forWork that isn't its own
        // subject. Those are the witnesses.
        Map<String, Set<String>> witnessBySlot = new HashMap<>();
        for (WikidataDynamicObject a : mine) {
            String slot = qid(a.get(valueField));
            String src = qid(a.get(sourceField));
            if (slot == null) {
                continue;
            }
            for (String rf : refRoleFields) {
                String v = qid(a.get(rf));
                if (v != null && !v.equals(src)) {
                    witnessBySlot.computeIfAbsent(slot, k -> new HashSet<>()).add(v);
                }
            }
        }

        int selfReferential = 0;
        List<WikidataDynamicObject> suspects = new ArrayList<>();
        for (WikidataDynamicObject a : mine) {
            String slot = qid(a.get(valueField));
            String src = qid(a.get(sourceField));
            if (slot == null || src == null) {
                continue;
            }
            // Fully self-referential: every role value is the subject (or absent).
            boolean fully = true;
            for (String rf : roleFields) {
                String v = qid(a.get(rf));
                if (v != null && !v.equals(src)) {
                    fully = false;
                    break;
                }
            }
            if (!fully) {
                continue;
            }
            selfReferential++;
            Set<String> witnesses = witnessBySlot.get(slot);
            if (witnesses != null && witnesses.contains(src)) {
                suspects.add(a);
            }
        }

        if (log != null) {
            log.message("Consistency (#99) " + type + ": " + selfReferential
                    + " kept self-referential, " + suspects.size()
                    + " SUSPECTED surviving phantom(s) — self-ref with a same-slot "
                    + refRoleFields + " witness that should have dropped it.\n");
            int shown = Math.min(suspects.size(), 25);
            for (int i = 0; i < shown; i++) {
                WikidataDynamicObject a = suspects.get(i);
                log.message("  ! " + a.getIdentifier() + " \"" + a.getDisplayName() + "\"\n");
            }
            if (suspects.size() > shown) {
                log.message("  … and " + (suspects.size() - shown) + " more\n");
            }
        }
        return suspects.size();
    }

    private static String qid(Object v) {
        return v instanceof WikidataDynamicObject w ? w.getIdentifier() : null;
    }
}
