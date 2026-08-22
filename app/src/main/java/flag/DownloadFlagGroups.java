package flag;

import aux.Constants;
import aux.UrlLineProcessor;
import aux.UrlReader;
import quiz.group.GroupReader;
import quiz.group.ViewableGroup;

import java.io.BufferedReader;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadFlagGroups implements UrlLineProcessor<ViewableGroup> {
    private static final String COLOR_GROUP_URL =
            "https://en.wikipedia.org/wiki/List_of_flags_by_color_combination?action=raw";
    private static final Pattern CURRENCY_CITATION =
            Pattern.compile("\\[[^]]+]");

    private final GroupReader groupReader;
    private final Map<String, State> states;

    public static void downloadColorFlagroups(
            ViewableGroup parent,
            Map<String, State> states
    ) throws Exception {
        new DownloadFlagGroups(parent, "Flag colors", states)
                .download(COLOR_GROUP_URL);
    }

    public static void downloadDesignFlagroups(
            ViewableGroup parent,
            Map<String, State> states
    ) throws Exception {
        DesignGroupReader.readDesignGroups(parent, states);
    }

    public DownloadFlagGroups(
            ViewableGroup parent,
            String groupName,
            Map<String, State> states
    ) {
        this.groupReader = new GroupReader(parent, groupName);
        this.states = states;
    }

    public void download(String url) throws Exception {
        new UrlReader<>(this).read(url);
    }

    @Override
    public URL processLine(String line) throws Exception {
        if (groupReader.parseGroup(line)) {
            return null;
        }

        String country = FlagOfLineParser.parseCountry(line);

        if (country != null) {
            StateGroupAdder.addStateToGroup(
                    groupReader.getGroup(),
                    country,
                    states
            );
        }

        return null;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public ViewableGroup done() {
        return groupReader.getRoot();
    }

    public static void readCurrencyGroup(
            String filename,
            String separator,
            Map<String, State> states
    ) throws Exception {
        try (BufferedReader reader =
                     Constants.getBufferedReaderForResource(
                             Constants.flagDataDirectory + filename)) {
            readCurrencyGroup(reader, separator, states);
        }
    }

    static void readCurrencyGroup(
            BufferedReader reader,
            String separator,
            Map<String, State> states
    ) throws Exception {
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }

            String[] tags = line.split(separator);

            if (tags.length < 2) {
                continue;
            }

            String country =
                    PrefixAndState.canonicalStateName(tags[0].strip());

            // The source's trailing [A]/[F]/[5] markers are citations, not part
            // of a currency's identity. Without this normalization they create
            // parallel branches such as dollar and dollar[F].
            String currencyVersion = CURRENCY_CITATION
                    .matcher(tags[1].strip())
                    .replaceAll("")
                    .strip();
            if (currencyVersion.isEmpty()) {
                continue;
            }

            // This is an intentional denomination -> variant taxonomy:
            // "United States dollar" becomes dollar -> United States.
            int split = currencyVersion.lastIndexOf(" ");

            String currency;
            if (split == -1) {
                currency = currencyVersion;
            } else {
                currency = currencyVersion.substring(split + 1);
            }

            State state = states.get(country);

            if (state == null) {
                System.out.println(
                        "No state for " + country
                                + " for currency " + currency);
                continue;
            }

            state.getCurrencies().add(currency);
        }
    }

    public static void readCapitalsAndContinents(
            String filename,
            String separator,
            boolean parseContinent,
            ViewableGroup parent,
            Map<String, State> states
    ) throws Exception {
        System.out.println("Reading capitals from " + filename);

        BufferedReader reader =
                Constants.getBufferedReaderForResource(
                        Constants.flagDataDirectory + filename);

        ViewableGroup continentsGroup = parseContinent
                ? parent.getOrCreateChild("Continents") : null;

        int n = 0;

        final int capitalIndex = parseContinent ? 0 : 1;
        final int countryIndex = 1 - capitalIndex;

        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }

            String[] tags = line.split(separator);

            if (tags.length < 3) {
                continue;
            }

            String stateName =
                    PrefixAndState.canonicalStateName(
                            tags[countryIndex].strip());

            String capital =
                    tags[capitalIndex].strip();

            String continent =
                    tags[2].strip();

            State state = parseContinent
                    ? states.computeIfAbsent(stateName, State::new)
                    : states.get(stateName);

            if (state == null) {
                continue;
            }

            if (parseContinent) {
                ViewableGroup continentGroup =
                        continentsGroup.getOrCreateChild(continent);

                continentGroup.addMember(state);
                state.addGroup(continentGroup);
            }

            state.getCapitals().add(capital);

            ++n;
        }

        System.out.println(
                "Read capitals done " + filename
                        + ", " + n + " capitals read.");

        reader.close();
    }

}

final class StateGroupAdder {
    private StateGroupAdder() {
    }

    static void addStateToGroup(
            ViewableGroup group,
            String rawStateName,
            Map<String, State> states
    ) {
        if (group == null || rawStateName == null || states == null) {
            return;
        }

        String stateName =
                PrefixAndState.canonicalStateName(rawStateName.strip());

        State state = states.get(stateName);

        if (state == null) {
            System.out.println(
                    "No state for [" + rawStateName
                            + "], canonical [" + stateName
                            + "], group [" + group.getFullName() + "]");
            return;
        }

        group.addMember(state);
        state.addGroup(group);
    }
}

final class FlagOfLineParser {
    private FlagOfLineParser() {
    }

    private static final Pattern[] COUNTRY_PATTERNS = {
            Pattern.compile("\\|name\\=(?<country>[^|=]+)\\|",
                    Pattern.CASE_INSENSITIVE),

            Pattern.compile("\\=yes\\|link\\=(?<country>[^|}=]+)\\|",
                    Pattern.CASE_INSENSITIVE),

            Pattern.compile("\\=yes\\|(?<country>[^|}=]+)\\|",
                    Pattern.CASE_INSENSITIVE),

            Pattern.compile("\\=yes\\|(?<country>[^|}=]+)}}",
                    Pattern.CASE_INSENSITIVE),

            Pattern.compile("\\|(?<country>[^|=}]+)}}",
                    Pattern.CASE_INSENSITIVE),

            Pattern.compile("\\|(?<country>[^|=}]+)\\|",
                    Pattern.CASE_INSENSITIVE)
    };

    static String parseCountry(String line) {
        if (line == null) {
            return null;
        }

        String lower = line.toLowerCase();

        if (!lower.startsWith("* {{flagof|")
                && !lower.startsWith("*{{flagof|")) {
            return null;
        }

        for (Pattern pattern : COUNTRY_PATTERNS) {
            Matcher m = pattern.matcher(line);

            if (m.find()) {
                return cleanupCountry(m.group("country"));
            }
        }

        return null;
    }

    private static String cleanupCountry(String country) {
        if (country == null) {
            return null;
        }

        country = country.strip();

        int pipe = country.indexOf('|');

        if (pipe >= 0) {
            country = country.substring(0, pipe).strip();
        }

        return country.isEmpty() ? null : country;
    }
}

class DesignGroupReader implements UrlLineProcessor<ViewableGroup> {
    private static final String DESIGN_GROUP_URL =
            Constants.wiki + "List_of_national_flags_by_design?action=raw";

    private static final Pattern COUNTRY_PATTERN =
            Pattern.compile("\\*\\{\\{flagof\\|(?<country>[^}]+)}}(?<info>.*)");

    private static final Pattern INFO_PATTERN =
            Pattern.compile("\\[\\[(?<info>[^]]+)]]");

    private final GroupReader groupReader;
    private final Map<String, State> states;

    static void readDesignGroups(
            ViewableGroup parent,
            Map<String, State> states
    ) throws Exception {
        boolean debug = false;

        if (debug) {
            BufferedReader reader =
                    Constants.getBufferedReaderForResource(
                            Constants.flagDataDirectory + "gtest.txt");

            DesignGroupReader processor =
                    new DesignGroupReader(parent, states);

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }

                processor.processLine(line);
            }

            reader.close();
            processor.done();
        } else {
            new UrlReader<>(
                    new DesignGroupReader(parent, states))
                    .read(DESIGN_GROUP_URL);
        }
    }

    public DesignGroupReader(
            ViewableGroup parent,
            Map<String, State> states
    ) {
        this.groupReader = new GroupReader(parent, "Flag design");
        this.states = states;
    }

    @Override
    public URL processLine(String line) throws Exception {
        if (!groupReader.parseGroup(line)) {
            parseFlag(line);
        }

        return null;
    }

    private void parseFlag(String line) {
        Matcher matcher = COUNTRY_PATTERN.matcher(line);

        if (!matcher.matches()) {
            if (line.contains("{{flagof}}")) {
                System.out.println(
                        "Should match byDesign flagof pattern: " + line);
            }

            return;
        }

        String country =
                FlagOfLineParser.parseCountry("*{{flagof|" + matcher.group("country") + "}}");

        if (country == null) {
            country = matcher.group("country");
        }

        StateGroupAdder.addStateToGroup(
                groupReader.getGroup(),
                country,
                states
        );

        String info = matcher.group("info");

        Matcher infoMatcher = INFO_PATTERN.matcher(info);

        while (infoMatcher.find()) {
            String designInfo = infoMatcher.group("info");

            // Currently not stored, but this is the clean extension point
            // if later State or ViewableGroup should remember design tags.
            // System.out.println("INFO " + country + ": " + designInfo);
        }
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public ViewableGroup done() {
        return groupReader.getRoot();
    }
}
