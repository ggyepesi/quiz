package canonical;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Unambiguous process-independent encoding of an ordered key tuple. */
public final class StableKey {
    private StableKey() { }

    /** Frame the tuple count and every UTF-8 byte length before encoding it. */
    public static String encode(List<String> components) {
        List<String> values = components == null ? List.of() : components;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(values.size());
                for (String component : values) {
                    byte[] utf8 = (component == null ? "" : component)
                            .getBytes(StandardCharsets.UTF_8);
                    out.writeInt(utf8.length);
                    out.write(utf8);
                }
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("Could not frame canonical key", impossible);
        }
    }
}
