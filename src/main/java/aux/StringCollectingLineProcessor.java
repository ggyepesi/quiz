package aux;

import java.net.URL;

public class StringCollectingLineProcessor implements UrlLineProcessor<String> {
    private final StringBuilder sb = new StringBuilder();

    @Override
    public URL processLine(String line) {
        sb.append(line).append('\n');
        return null;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public String done() {
        return sb.toString();
    }
}