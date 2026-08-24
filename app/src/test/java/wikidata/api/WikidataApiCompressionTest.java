package wikidata.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WikidataApiCompressionTest {

    @Test void decodesAGzipResponseWhenTheServerDeclaresIt() throws Exception {
        byte[] json = "{\"entities\":{\"Q1\":{\"id\":\"Q1\"}}}"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(encoded)) {
            gzip.write(json);
        }

        try (InputStream decoded = WikidataApiClient.decodedStream(
                new ByteArrayInputStream(encoded.toByteArray()), "gzip")) {
            assertEquals(new String(json, StandardCharsets.UTF_8),
                    new String(decoded.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * The reason the counting stream is a RESOURCE and is declared first: this throws
     * from a constructor, before the decoded stream exists to be closed. Built outside
     * the try, the connection's stream leaked here — once per attempt, and a body that
     * arrives truncated is exactly what gets retried.
     */
    @Test void aBodyThatIsNotGzipAfterAllFailsWhileTheStreamIsBeingBuilt() {
        byte[] notGzip = "{\"entities\":{}}".getBytes(StandardCharsets.UTF_8);

        assertThrows(java.util.zip.ZipException.class,
                () -> WikidataApiClient.decodedStream(
                        new ByteArrayInputStream(notGzip), "gzip"));
    }

    @Test void leavesAnUnencodedResponseUntouched() throws Exception {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        try (InputStream decoded = WikidataApiClient.decodedStream(
                new ByteArrayInputStream(json), null)) {
            assertEquals("{}", new String(decoded.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
