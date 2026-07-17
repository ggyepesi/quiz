package aux;

import objectview.utils.swing.SvgRasterizer;

import org.apache.batik.transcoder.ErrorHandler;
import org.apache.batik.transcoder.Transcoder;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.JPEGTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Batik-backed {@link SvgRasterizer}: transcodes SVG bytes to PNG (or JPEG)
 * raster bytes. The host registers it with objectview (see {@code QuizFactory})
 * so the library renders SVG images without carrying a Batik dependency itself.
 */
public final class BatikSvgRasterizer implements SvgRasterizer {

    @Override
    public byte[] rasterize(byte[] svg, boolean jpeg) throws Exception {
        TranscoderInput input =
                new TranscoderInput(new ByteArrayInputStream(svg));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TranscoderOutput output = new TranscoderOutput(out);

        Transcoder transcoder;

        if (jpeg) {
            transcoder = new JPEGTranscoder();
            transcoder.addTranscodingHint(JPEGTranscoder.KEY_QUALITY, 0.40f);
        } else {
            transcoder = new PNGTranscoder();
        }

        transcoder.setErrorHandler(ERROR_HANDLER);
        transcoder.transcode(input, output);

        return out.toByteArray();
    }

    private static final ErrorHandler ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void error(TranscoderException ex) throws TranscoderException {
            System.out.println("SVG transcode error: " + ex.getMessage());
        }

        @Override
        public void fatalError(TranscoderException ex) throws TranscoderException {
            System.out.println("SVG transcode fatal: " + ex.getMessage());
        }

        @Override
        public void warning(TranscoderException ex) throws TranscoderException {
            System.out.println("SVG transcode warning: " + ex.getMessage());
        }
    };
}
