package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * There is ONE way to offer a list of chosen things plus a chooser of what to add.
 *
 * <p>It was written three times — the identity key, an aggregate's grouping pairs, a
 * class's contextual representations — and all three carried the same two defects: a
 * plain click reconfigured the class, and Add acted on a combo entry nobody had picked.
 * Each was found separately, by clicking, in a panel written after the previous fix.
 *
 * <p>The tell is a panel holding, as FIELDS, a list model and a combo box <b>over the
 * same type</b>: the combo offers candidates FOR the list, which is what makes it a
 * chooser and the pair a chosen-set editor. Two other shapes look similar and are not
 * this one — a one-shot picker whose selection genuinely IS the answer builds its list
 * locally, and a composition form (a filter's operator beside the filters it composes)
 * pairs a list with a combo of a different type, an input rather than a candidate.
 */
class OneChoiceListConstructTest {

    private static final Pattern LIST_MODEL_FIELD =
            Pattern.compile("(?m)^\\s+(?:private|protected|public)[\\w\\s]*"
                    + "(?:DefaultListModel|ListModel)<([\\w.]+)>");

    private static final Pattern COMBO_FIELD =
            Pattern.compile("(?m)^\\s+(?:private|protected|public)[\\w\\s]*"
                    + "JComboBox<([\\w.]+)>");

    @Test void noPanelHandRollsAChosenListAndItsChooser() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (source.getFileName().toString().equals("OrderedChoiceList.java")) continue;
                String text = Files.readString(source);
                List<String> listed = typesIn(LIST_MODEL_FIELD, text);
                for (String offered : typesIn(COMBO_FIELD, text)) {
                    if (listed.contains(offered)) {
                        offenders.add(source.toString() + " (" + offered + ")");
                    }
                }
            }
        }

        assertEquals(List.of(), offenders,
                "a chosen list beside a chooser is OrderedChoiceList's job — three "
                        + "hand-written copies had the same two defects, and the third "
                        + "was written after the second was fixed");
    }

    private static List<String> typesIn(Pattern field, String text) {
        List<String> types = new ArrayList<>();
        Matcher matcher = field.matcher(text);
        while (matcher.find()) types.add(matcher.group(1));
        return types;
    }
}
