package datasource.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceRuntimeServicesTest {
    @Test void sameServiceTypeCanBelongToDifferentProviders() {
        String first = new String("first");
        String second = new String("second");
        SourceRuntimeServices services = SourceRuntimeServices.builder()
                .put("one", String.class, first)
                .put("two", String.class, second)
                .build();

        assertEquals("first", services.require("one", String.class));
        assertEquals("second", services.require("two", String.class));
        assertNotSame(services.require("one", String.class),
                services.require("two", String.class));
    }

    @Test void duplicateProviderAndTypeIsRejected() {
        SourceRuntimeServices.Builder builder = SourceRuntimeServices.builder()
                .put("one", String.class, "first");

        assertThrows(IllegalArgumentException.class,
                () -> builder.put("one", String.class, "second"));
    }

    @Test void aMissingRequiredServiceNamesProviderAndType() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SourceRuntimeServices.empty().require("missing", String.class));

        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("missing"));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("String"));
    }
}
