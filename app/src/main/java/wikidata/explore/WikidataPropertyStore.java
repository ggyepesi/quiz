package wikidata.explore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class WikidataPropertyStore {

    private final File file;

    public WikidataPropertyStore() {
        this.file = new File("data/wikidata/properties.tsv");
    }

    public WikidataPropertyStore(File file) {
        this.file = java.util.Objects.requireNonNull(file);
    }

    public File file() {
        return file;
    }

    public void write(List<WikidataProperty> properties) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(file),
                        StandardCharsets.UTF_8))) {

            for (WikidataProperty p : properties) {
                out.write(escape(p.pid()));
                out.write('\t');
                out.write(escape(p.label()));
                out.write('\t');
                out.write(escape(p.description()));
                out.write('\t');
                out.write(escape(p.datatype()));
                out.write('\t');
                out.write(escape(p.cardinality()));
                out.write('\t');
                out.write(escape(p.superpropertyPids()));
                out.write('\t');
                out.write(escape(p.inversePropertyPids()));
                out.write('\n');
            }
        }
        System.out.println("Saved "
                                   + properties.size()
                                   + " properties to "
                                   + file.getAbsolutePath());
    }

    public List<WikidataProperty> read() throws IOException {
        List<WikidataProperty> list = new ArrayList<>();

        if (!file.exists()) {
            return list;
        }

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8))) {

            String line;

            while ((line = in.readLine()) != null) {
                String[] parts = line.split("\t", -1);

                if (parts.length >= 2) {
                    list.add(new WikidataProperty(
                            unescape(parts[0]),
                            unescape(parts[1]),
                            parts.length >= 3 ? unescape(parts[2]) : "",
                            parts.length >= 4 ? unescape(parts[3]) : "",
                            parts.length >= 5 ? unescape(parts[4]) : "",
                            parts.length >= 6 ? unescape(parts[5]) : "",
                            parts.length >= 7 ? unescape(parts[6]) : ""));
                }
            }
        }
        System.out.println("Read "
                                   + list.size()
                                   + " properties from "
                                   + file.getAbsolutePath());

        return list;
    }

    /**
     * The catalogue's declared converse relation, ready for model consumers.
     *
     * <p>The TSV remains the one owner of the cached fact. Callers must not each
     * reinterpret the comma-separated P1696 column or disagree about PID case.
     */
    public Map<String, Set<String>> inverseProperties() throws IOException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (WikidataProperty property : read()) {
            String pid = cleanPid(property.pid());
            if (pid.isBlank()) continue;
            LinkedHashSet<String> inverses = new LinkedHashSet<>();
            for (String value : property.inversePropertyPids().split(",")) {
                String inverse = cleanPid(value);
                if (!inverse.isBlank()) inverses.add(inverse);
            }
            if (!inverses.isEmpty()) result.put(pid, Set.copyOf(inverses));
        }
        return Map.copyOf(result);
    }

    private static String cleanPid(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }

        return s.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unescape(String s) {
        if (s == null) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        boolean esc = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (esc) {
                switch (ch) {
                    case 't' -> out.append('\t');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case '\\' -> out.append('\\');
                    default -> out.append(ch);
                }
                esc = false;
            } else if (ch == '\\') {
                esc = true;
            } else {
                out.append(ch);
            }
        }

        if (esc) {
            out.append('\\');
        }

        return out.toString();
    }
}
