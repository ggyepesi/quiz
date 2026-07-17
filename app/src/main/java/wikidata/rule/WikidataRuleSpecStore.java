package wikidata.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class WikidataRuleSpecStore {
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private WikidataRuleSpecStore() {}

    public static List<WikidataRuleSpec> read(File file) throws Exception {
        if (file == null || !file.exists()) {
            return new ArrayList<>();
        }

        return MAPPER.readValue(
                file,
                new TypeReference<List<WikidataRuleSpec>>() {});
    }

    public static void write(File file, List<WikidataRuleSpec> specs)
            throws Exception {
        file.getParentFile().mkdirs();
        MAPPER.writeValue(file, specs);
    }
}