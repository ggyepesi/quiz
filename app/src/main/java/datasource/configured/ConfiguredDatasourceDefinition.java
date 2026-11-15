package datasource.configured;

import datasource.api.SourceValueKind;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persisted grammar for configured bounded JSON REST datasource operations. */
public record ConfiguredDatasourceDefinition(
        String id,
        String displayName,
        List<RestOperation> operations) {

    private static final Set<SourceValueKind> CONFIGURED_RECORD_KINDS = EnumSet.of(
            SourceValueKind.TEXT,
            SourceValueKind.LANGUAGE_TEXT,
            SourceValueKind.DATE_TIME,
            SourceValueKind.QUANTITY,
            SourceValueKind.MEDIA,
            SourceValueKind.URL);

    public ConfiguredDatasourceDefinition {
        id = required(id, "Configured datasource id is required");
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        operations = operations == null ? List.of() : List.copyOf(operations);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Configured datasource '" + id + "' declares no operations");
        }
        if (operations.size() != operations.stream().map(RestOperation::id).distinct().count()) {
            throw new IllegalArgumentException(
                    "Configured datasource '" + id + "' repeats an operation id");
        }
    }

    public record RestOperation(
            String id,
            String displayName,
            String inputNamespace,
            int maximumSeeds,
            int maximumRecordsPerSeed,
            int parallelism,
            Search search,
            Detail detail,
            Match match,
            Record record) {
        public RestOperation {
            id = required(id, "Configured operation id is required");
            displayName = displayName == null || displayName.isBlank()
                    ? id : displayName.trim();
            inputNamespace = required(inputNamespace,
                    "Configured operation input namespace is required");
            if (maximumSeeds < 1) throw new IllegalArgumentException(
                    displayName + " maximumSeeds must be at least 1");
            if (maximumRecordsPerSeed < 1) throw new IllegalArgumentException(
                    displayName + " maximumRecordsPerSeed must be at least 1");
            if (parallelism < 1) throw new IllegalArgumentException(
                    displayName + " parallelism must be at least 1");
            if (search == null || detail == null || match == null || record == null) {
                throw new IllegalArgumentException(displayName
                        + " needs search, detail, match and record configuration");
            }
        }
    }

    public record Search(
            String url,
            Map<String, String> query,
            String idsPath,
            String totalPath) {
        public Search {
            url = httpUrl(url, "Search URL");
            query = ordered(query);
            idsPath = JsonFieldPath.require(idsPath, "Search result IDs path");
            totalPath = totalPath == null || totalPath.isBlank() ? ""
                    : JsonFieldPath.require(totalPath, "Search total path");
        }
    }

    public record Detail(String url) {
        public Detail {
            url = httpUrl(url, "Detail URL");
            if (!url.contains("${result.id}")) {
                throw new IllegalArgumentException(
                        "Detail URL must contain ${result.id}");
            }
        }
    }

    public record Match(String path, ValueFormat valueFormat) {
        public Match {
            path = JsonFieldPath.require(path, "Match evidence path");
            valueFormat = valueFormat == null ? ValueFormat.TEXT : valueFormat;
        }
    }

    public enum ValueFormat { TEXT, WIKIDATA_URL }

    public record Record(
            String typeName,
            String namespace,
            String idPath,
            String displayPath,
            List<Field> fields) {
        public Record {
            typeName = required(typeName, "Record type name is required");
            namespace = required(namespace, "Record namespace is required");
            idPath = JsonFieldPath.require(idPath, "Record id path");
            displayPath = JsonFieldPath.require(displayPath, "Record display path");
            fields = fields == null ? List.of() : List.copyOf(fields);
            if (fields.size() != fields.stream().map(Field::name).distinct().count()) {
                throw new IllegalArgumentException(
                        "Configured record '" + typeName + "' repeats a field name");
            }
        }
    }

    public record Field(
            String name,
            String label,
            String path,
            SourceValueKind kind,
            boolean collection) {
        public Field {
            name = required(name, "Record field name is required");
            label = label == null || label.isBlank() ? name : label.trim();
            path = JsonFieldPath.require(path, "Record field path");
            kind = kind == null ? SourceValueKind.TEXT : kind;
            if (!CONFIGURED_RECORD_KINDS.contains(kind)) {
                throw new IllegalArgumentException(
                        "Record field '" + name + "' has unsupported configured kind " + kind
                                + "; use text, language text, date/time, quantity, media or URL");
            }
        }
    }

    private static Map<String, String> ordered(Map<String, String> values) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> {
            String clean = required(key, "Query parameter name is required");
            copy.put(clean, value == null ? "" : value);
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static String httpUrl(String value, String what) {
        String clean = required(value, what + " is required");
        java.net.URI uri;
        try { uri = java.net.URI.create(clean.replace("${result.id}", "1")); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(what + " is invalid: " + clean, invalid);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(what + " must use HTTP or HTTPS: " + clean);
        }
        return clean;
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
