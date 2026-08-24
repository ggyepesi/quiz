package datasource.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SharedHttpTransportTest {
    @Test void decodesGzipWhenTheServerDeclaresIt() throws Exception {
        byte[] json = "{\"entities\":{\"Q1\":{\"id\":\"Q1\"}}}"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(encoded)) {
            gzip.write(json);
        }

        assertEquals(new String(json, StandardCharsets.UTF_8),
                new String(SharedHttpTransport.decode(encoded.toByteArray(), "gzip"),
                        StandardCharsets.UTF_8));
    }

    @Test void malformedDeclaredGzipFails() {
        byte[] plain = "{}".getBytes(StandardCharsets.UTF_8);
        assertThrows(java.util.zip.ZipException.class,
                () -> SharedHttpTransport.decode(plain, "gzip"));
    }

    @Test void leavesPlainResponsesUntouched() throws Exception {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        assertSame(json, SharedHttpTransport.decode(json, null));
    }

    @Test void standardTransportIsSharedAndPrefersHttp2() {
        assertSame(SharedHttpTransport.standard(), SharedHttpTransport.standard());
        assertEquals(HttpClient.Version.HTTP_2,
                SharedHttpTransport.standard().preferredVersion());
    }
}
