package flag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagCachedImageTest {

    @Test
    void sourceUrlUsesTheBundledFlagResource() throws Exception {
        FlagCachedImage image = new FlagCachedImage(
                "Flag of Christmas Island", null, true);

        String source = image.sourceUrl();
        assertTrue(source.startsWith("file:"), source);
        assertTrue(source.endsWith("/flag/svg/Flag%20of%20Christmas%20Island.svg")
                        || source.endsWith("/flag/svg/Flag of Christmas Island.svg"),
                source);
    }
}
