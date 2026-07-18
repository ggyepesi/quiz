package aux;

import objectview.utils.swing.SvgRasterizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The Batik SVG rasterizer must be discovered via ServiceLoader from the
 * classpath service file, so SVG images render from any entry point (e.g. the
 * ModelBuilder) without a bootstrap class having called setActive.
 */
class SvgRasterizerServiceTest {

    @Test
    void batikRasterizerIsDiscoveredOnClasspath() {
        SvgRasterizer active = SvgRasterizer.active();
        assertNotSame(SvgRasterizer.NONE, active,
                "expected the Batik rasterizer to be found via META-INF/services");
        assertInstanceOf(BatikSvgRasterizer.class, active);
    }
}
