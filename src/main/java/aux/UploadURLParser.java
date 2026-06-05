package aux;

import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UploadURLParser implements UrlLineProcessor<String> {
    static final Pattern uploadCommonsRegex =
        Pattern.compile("\\/\\/upload\\.wikimedia\\.org\\/wikipedia\\/commons\\/.\\/.+\\/.+\\.png");
    static final Pattern uploadEnRegex =
        Pattern.compile("\\/\\/upload\\.wikimedia\\.org\\/wikipedia\\/en\\/.\\/.+\\/.+\\.png");

    static final String commons = "\\/\\/upload\\.wikimedia\\.org\\/wikipedia\\/commons\\/.\\/.+\\/.+\\.";
    static final String en = "\\/\\/upload\\.wikimedia\\.org\\/wikipedia\\/en\\/.\\/.+\\/.+\\.";

    static final List<Pattern> patterns = List.of(
        Pattern.compile(commons + "png"),
        Pattern.compile(commons + "jpg"),
        Pattern.compile(commons + "svg"),
        Pattern.compile(commons + "webp"),
        Pattern.compile(en + "png"),
        Pattern.compile(en + "jpg"),
        Pattern.compile(en + "svg"),
        Pattern.compile(en + "webp")
    );
 
    static final List<String> ends = List.of(".png", ".svg", ".jpg", ".webp");

    private String uploadUrl = null;

    @Override
    public URL processLine(String line) {
        uploadUrl = parseUploadURL(line);
        return null;
    }

    @Override
    public boolean isDone() {
        return uploadUrl != null;
    }

    @Override
    public String done() {
        return uploadUrl;
    }

    private String cut(String s) {
        int end = -1;
        int len = 0;
        for (String e : ends) {
            int i = s.indexOf(e);
            if (i > 0 && (i < end || end == -1)) {
                end = i;
                len = e.length();
            }
        }
        return s.substring(0, end + len);
    }

    private String parseUploadURL(String line) {
        for (Pattern p : patterns) {
            Matcher matcher = p.matcher(line);
            if (matcher.find()) {
                String urlString = line.substring(matcher.start(), matcher.end());
                return "https:" + cut(urlString);
            }
        }
        return null;
    }
}
