package aux;

import objectview.utils.UrlOpener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;

public class UrlReader<T> {
    private final UrlLineProcessor<T> lineProcessor;

    public UrlReader(UrlLineProcessor<T> lineProcessor) {
        this.lineProcessor = lineProcessor;
    }

    public T read(String url) throws Exception {
        return read(URI.create(url).toURL());
    }
   
    public T read(URL url) throws Exception {
        if (lineProcessor.isDone()) {
            return lineProcessor.done();
        } 
        BufferedReader reader = new BufferedReader(new InputStreamReader(UrlOpener.open(url)));
        System.out.println("UrlReader reading " + url);
        String line;
        while (true) {
            line = reader.readLine();
            if (line == null) break;
            URL redirectUrl = lineProcessor.processLine(line);
            if (redirectUrl != null) {
                System.out.println("UrlReader redirected to [" + redirectUrl + "]");
                reader.close();
                return new UrlReader<T>(lineProcessor).read(redirectUrl);
            }
            if (lineProcessor.isDone()) break;
        }
        reader.close();
        return lineProcessor.done();
    }   
}
