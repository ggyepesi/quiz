package domain.datasource.discovery.ui;

import domain.datasource.api.SourceValueKind;
import domain.datasource.api.discovery.DiscoveredRecordField;
import domain.datasource.api.discovery.DiscoveredRelatedRecord;
import objectview.ViewableAdapter;
import objectview.demo.MultiView;
import objectview.field.DynamicFields;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.field.FieldRole;
import objectview.field.FieldSchema;
import objectview.media.MediaValueData;
import domain.runtime.WikidataDynamicObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ObjectView projection of one provider-neutral discovered source record. */
final class DiscoveredRecordView extends ViewableAdapter implements DynamicFields {
    static final String MATCHED = "matchedSelectedEntities";

    private final String id;
    private final String name;
    private final String typeName;
    private final Map<String, Object> values;
    private final FieldSchema schema;

    DiscoveredRecordView(DiscoveredRelatedRecord record, String typeName,
            List<DiscoveredRecordField> fields,
            List<WikidataDynamicObject> matchedSelectedEntities) {
        this.id = record.qualifiedId();
        this.name = record.displayName();
        this.typeName = typeName;
        LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
        mapped.put(MATCHED, List.copyOf(matchedSelectedEntities));
        for (DiscoveredRecordField field : fields) {
            List<String> raw = record.fields().getOrDefault(field.name(), List.of());
            if (raw.isEmpty()) continue;
            List<Object> converted = raw.stream().map(value -> convert(field, value)).toList();
            mapped.put(field.name(), field.collection() ? converted : converted.getFirst());
        }
        this.values = java.util.Collections.unmodifiableMap(mapped);
        List<FieldRef> declared = new ArrayList<>();
        declared.add(FieldRef.described(MATCHED, "Matched selected entities",
                FieldRole.NONE, FieldKind.COLLECTION, FieldKind.REFERENCE,
                "List", true, true, null, false, false,
                false, false, "", false));
        fields.stream().map(DiscoveredRecordView::field).forEach(declared::add);
        this.schema = () -> List.copyOf(declared);
    }

    @Override public String getIdentifier() { return id; }
    @Override public String getDisplayName() { return name; }
    @Override public String typeName() { return typeName; }
    @Override public Map<String, Object> dynamicFieldValues() { return values; }
    @Override public FieldSchema dynamicFieldSchema() { return schema; }

    /**
     * Adds configured source records through MultiView's ordinary DynamicFields entry
     * point. The first record is the schema sample used by Search/Sort/View config;
     * without it the carrier class exposes only the Viewable caption and an open card
     * has no configured body.
     */
    static void addSection(MultiView view, String title,
            List<DiscoveredRecordView> records) {
        view.addSection(title, DiscoveredRecordView.class, records,
                records.isEmpty() ? null : records.getFirst(),
                java.util.Set.of(), null);
    }

    private static Object convert(DiscoveredRecordField field, String value) {
        return switch (field.kind()) {
            case MEDIA -> new MediaValueData(field.label(), value,
                    wikidata.explore.CommonsMedia.isSvg(value));
            case QUANTITY -> number(value);
            default -> value;
        };
    }

    private static Object number(String value) {
        try { return new BigDecimal(value); }
        catch (NumberFormatException ignored) { return value; }
    }

    private static FieldRef field(DiscoveredRecordField field) {
        FieldKind valueKind = switch (field.kind()) {
            case MEDIA -> FieldKind.MEDIA;
            case QUANTITY, DATE_TIME -> FieldKind.ORDERED;
            default -> FieldKind.TEXT;
        };
        FieldKind kind = field.collection() ? FieldKind.COLLECTION : valueKind;
        String type = field.collection() ? "List<" + typeLabel(field.kind()) + ">"
                : typeLabel(field.kind());
        return FieldRef.described(field.name(), field.label(), FieldRole.NONE,
                kind, valueKind, type, false, field.collection(), null,
                false, false, field.kind() == SourceValueKind.MEDIA,
                field.kind() == SourceValueKind.URL, "", false);
    }

    private static String typeLabel(SourceValueKind kind) {
        return switch (kind) {
            case MEDIA -> "MediaValue";
            case QUANTITY -> "Number";
            case DATE_TIME -> "Date";
            default -> "String";
        };
    }
}
