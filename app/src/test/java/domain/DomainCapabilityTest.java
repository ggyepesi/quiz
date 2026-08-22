package domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A capability is found however deeply the domain holding it is wrapped.
 *
 * <p>Asking with {@code instanceof} can only ever see the outermost object, so a wrapper that
 * did not itself implement a capability hid its base's. The answer had been a forwarding shim
 * per capability per wrapper — {@code WorkingDomain} carried five, {@code CuratableDomain} one
 * — which works until the next capability, or the next wrapper, is written without them. There
 * were twenty-two such probes.
 *
 * <p>{@link DomainModel#capability} asks once and {@link DelegatingDomainModel} answers for a
 * whole chain, so the shims are gone. This checks the lookup composes, that a wrapper's own
 * capability still wins, and that no {@code instanceof} against a {@link DomainCapability} has
 * crept back into the sources.
 */
class DomainCapabilityTest {

    interface Promotable extends DomainCapability { String who(); }

    @Test void aCapabilityIsFoundThroughEveryLayerOfWrapping() {
        DomainModel backing = new Backing("the snapshot");
        DomainModel wrapped = new Wrapper(new Wrapper(new Wrapper(backing)));

        Promotable found = wrapped.capability(Promotable.class);

        assertSame(backing, found, "three wrappers deep is still the same capability");
        assertEquals("the snapshot", found.who());
    }

    @Test void aWrapperThatHasTheCapabilityItselfAnswersForIt() {
        DomainModel backing = new Backing("the snapshot");
        DomainModel wrapped = new PromotableWrapper(new Wrapper(backing));

        assertEquals("the wrapper", wrapped.capability(Promotable.class).who(),
                "the wrapper knows about whatever it changed; its base does not");
    }

    @Test void aCapabilityNobodyInTheChainHasIsAbsentRatherThanGuessed() {
        assertNull(new Wrapper(new Plain()).capability(Promotable.class));
        assertNull(new Plain().capability(Promotable.class));
        assertNull(new Wrapper(new Plain()).capability(null));
    }

    /** The rule is only worth having if it is the ONLY way capabilities are asked for. */
    @Test void nothingAsksForACapabilityWithInstanceof() throws Exception {
        Set<String> names = capabilityNames();
        assertTrue(names.size() >= 4, "expected the known capabilities, found " + names);
        Pattern probe = Pattern.compile(
                "instanceof\\s+(?:[\\w.]*\\.)?(" + String.join("|", names) + ")\\b");

        Set<String> offenders = new TreeSet<>();
        for (Path file : sources()) {
            String source = Files.readString(file)
                    .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
            Matcher matcher = probe.matcher(source);
            while (matcher.find()) {
                offenders.add(file.getFileName() + " → instanceof " + matcher.group(1));
            }
        }

        assertEquals(Set.of(), offenders,
                "ask domain.capability(X.class): instanceof sees only the outermost wrapper");
    }

    /** Read off the interfaces themselves rather than a list here, so a capability added
     *  later is covered without anyone remembering to add it. */
    private static Set<String> capabilityNames() throws IOException {
        Set<String> names = new TreeSet<>();
        Pattern declaration = Pattern.compile(
                "interface\\s+(\\w+)\\s+extends\\s+[\\w.]*DomainCapability\\b");
        for (Path file : sources()) {
            Matcher matcher = declaration.matcher(Files.readString(file));
            while (matcher.find()) names.add(matcher.group(1));
        }
        return names;
    }

    private static List<Path> sources() throws IOException {
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "run from the app module: " + root.toAbsolutePath());
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static class Plain implements DomainModel {
        @Override public List<String> types() { return List.of(); }
        @Override public objectview.field.FieldSchema fieldSchema(String type) { return List::of; }
        @Override public Collection<? extends objectview.Viewable> instances() { return List.of(); }
        @Override public Class<? extends objectview.Viewable> universe() {
            return objectview.Viewable.class;
        }
    }

    private static final class Backing extends Plain implements Promotable {
        private final String name;
        Backing(String name) { this.name = name; }
        @Override public String who() { return name; }
    }

    private static class Wrapper extends DelegatingDomainModel {
        Wrapper(DomainModel base) { super(base); }
    }

    private static final class PromotableWrapper extends Wrapper implements Promotable {
        PromotableWrapper(DomainModel base) { super(base); }
        @Override public String who() { return "the wrapper"; }
    }
}
