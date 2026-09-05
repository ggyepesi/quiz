package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class's membership has one home, and {@code sourceQid} and {@code additionalTypeQids}
 * are not it.
 *
 * <p>They survive on {@link FieldSourceMapping} because saved models still carry them and
 * the mapping is what reads a model file. Nothing else may touch them: no field uses
 * either — no code asks a field mapping for them, and no saved field carries one — so
 * every remaining reader was a class's membership, read where it used to live.
 *
 * <p>A guard rather than a review habit, because the mistake is invisible: the fields are
 * still there, the setters still work, and the model simply does not change. Six readers
 * survived the first sweep by going through a local — {@code var m =
 * clazz.effectiveInstanceMapping(project)}, then {@code m.sourceQid()} — which is why
 * this matches the accessor and not the shape of the expression before it. The class
 * explanation was showing every class a blank property and "?membershipTarget", the
 * graph cue read "members: WD: → ?", and the hint that says a class has nothing to
 * generate could never fire.
 *
 * <p>{@code propertyPid} is deliberately NOT guarded: a FIELD's property is spelled the
 * same way and is legitimate, and no text can tell the two apart. It travels with the
 * QIDs in practice — every membership read here touched both.
 */
class NothingWritesARetiredMembershipFieldTest {

    /** Where they may still be declared: the mapping itself, and its compiled form. */
    private static final List<String> DECLARING = List.of(
            "FieldSourceMapping.java", "CompiledFieldSource.java");

    private static final List<String> RETIRED =
            List.of("sourceQid(", "additionalTypeQids(");

    /** How a class mapping gets into a local, which is how six readers hid. */
    private static final java.util.regex.Pattern INTO_A_LOCAL =
            java.util.regex.Pattern.compile("\\b(\\w+)\\s*=\\s*[\\w.()]*\\b"
                    + "(?:effectiveInstanceMapping\\(|instanceMapping\\(\\))");

    /**
     * The third way in: a class mapping HANDED to a method, which is how one of the six
     * hid — {@code classExample(clazz, source)} took it as a parameter and built the
     * membership triple from it. Every name of this type is watched, because no field
     * reads either of the two accessors: nothing asks a field mapping for them, and no
     * saved field carries one.
     */
    private static final java.util.regex.Pattern NAMED_AS_A_MAPPING =
            java.util.regex.Pattern.compile("\\bFieldSourceMapping\\s+(\\w+)\\b");

    /** A chained read has no local receiver for {@link #INTO_A_LOCAL} to discover. */
    private static final java.util.regex.Pattern DIRECT_RETIRED =
            java.util.regex.Pattern.compile("(?:effectiveInstanceMapping|instanceMapping)"
                    + "\\([^;]*\\)\\.(?:sourceQid|additionalTypeQids)\\(");

    @Test void noProductionCodeUsesAClassMappingAsItsMembership() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) root = Path.of("app/src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (DECLARING.contains(file.getFileName().toString())) continue;
                List<String> lines = Files.readAllLines(file);
                List<String> receivers = new ArrayList<>();
                receivers.add("instanceMapping()");
                for (String line : lines) {
                    java.util.regex.Matcher into = INTO_A_LOCAL.matcher(line);
                    while (into.find()) receivers.add(into.group(1));
                    java.util.regex.Matcher named = NAMED_AS_A_MAPPING.matcher(line);
                    while (named.find()) receivers.add(named.group(1));
                }
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.stripLeading().startsWith("//")
                            || line.stripLeading().startsWith("*")) {
                        continue;
                    }
                    if (DIRECT_RETIRED.matcher(line).find()) {
                        offenders.add(file.getFileName() + ":" + (i + 1)
                                + "  " + line.strip());
                        continue;
                    }
                    for (String receiver : receivers) {
                        for (String retired : RETIRED) {
                            if (line.contains(receiver + "." + retired)) {
                                offenders.add(file.getFileName() + ":" + (i + 1)
                                        + "  " + line.strip());
                            }
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "a class's membership is GeneratedClassModel.membership(); these read or "
                        + "write the fields it replaced:\n" + String.join("\n", offenders));
    }

    @Test void guardRecognizesADirectChainedRead() {
        assertTrue(DIRECT_RETIRED.matcher(
                "clazz.effectiveInstanceMapping(project).sourceQid()").find());
    }

    /** And a mapping handed to a method, which is how one of the six hid. */
    @Test void guardRecognizesAMappingReceivedAsAParameter() {
        java.util.regex.Matcher named = NAMED_AS_A_MAPPING.matcher(
                "private static String classExample(GeneratedClassModel clazz, "
                        + "FieldSourceMapping source) {");
        assertTrue(named.find());
        assertEquals("source", named.group(1));
    }
}
