package wikidata.explore.model;

import datasource.EntityRef;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** The one collision-safe identity rule for a configured aggregate key tuple. */
public final class AggregateIdentity {
    private AggregateIdentity() {}

    public static String identifier(String className, List<String> keyValues) {
        List<String> framed = new ArrayList<>();
        framed.add(clean(className));
        if (keyValues != null) framed.addAll(keyValues.stream().map(AggregateIdentity::clean).toList());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(framed.size());
                for (String value : framed) {
                    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(utf8.length);
                    out.write(utf8);
                }
            }
            return new EntityRef("domain.aggregate", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes.toByteArray())).qualifiedId();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("Could not frame aggregate identity", impossible);
        }
    }

    private static String clean(String value) { return value == null ? "" : value; }
}
