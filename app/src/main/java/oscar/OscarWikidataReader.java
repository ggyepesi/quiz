package oscar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import wikidata.explore.extract.WikidataDynamicObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class OscarWikidataReader {
    public record AwardItem(String qid, String label) {}

    private static final String ENDPOINT = "https://query.wikidata.org/sparql";
    private static final int PAGE_SIZE = 50;
    private static final int REQUEST_SLEEP_MS = 1000;

    private final ObjectMapper mapper = new ObjectMapper();

    public List<OscarNomination> readAllWinners() throws Exception {
        return readAllByAward(true);
    }

    public List<OscarNomination> readAllNominations() throws Exception {
        return readAllByAward(false);
    }

    public List<AwardItem> readOscarAwards() throws Exception {
        String json = executeSparql(OscarWikidataQueryBuilder.allOscarAwards());

        List<AwardItem> out = new ArrayList<>();
        JsonNode bindings = mapper.readTree(json).path("results").path("bindings");

        for (JsonNode b : bindings) {
            String qid = qid(value(b, "award"));
            String label = value(b, "awardLabel");

            if (!qid.isBlank()) {
                out.add(new AwardItem(qid, label));
            }
        }

        System.out.println("Oscar awards found: " + out.size());
        for (AwardItem award : out) {
            System.out.println("  " + award.qid() + "  " + award.label());
        }

        return out;
    }

    private List<OscarNomination> readAllByAward(boolean winnersOnly) throws Exception {
        List<OscarNomination> all = new ArrayList<>();
        List<AwardItem> awards = readOscarAwards();
        System.out.println("Reading " + awards.size() + " awards");
        int num = 0;
        for (AwardItem award : awards) {
            String awardQid = award.qid();

            System.out.println("Reading Oscar "
                    + (winnersOnly ? "winners" : "nominations")
                    + " for " + award.qid()
                    + " / " + award.label());
            ++num;
            if (num > 100) break;
            for (int offset = 0; ; offset += PAGE_SIZE) {
                List<OscarNomination> page;

                try {
                    page = winnersOnly
                            ? readWinnerPageForAward(awardQid, PAGE_SIZE, offset)
                            : readNominationPageForAward(awardQid, PAGE_SIZE, offset);
                } catch (Exception e) {
                    System.out.println("Skipping award " + awardQid + ": " + e.getMessage());
                    break;
                }

                if (page.isEmpty()) {
                    break;
                }

                all.addAll(page);
                sleepBetweenRequests();
            }
        }

        return all;
    }

    public List<OscarNomination> readWinnerPageForAward(String awardQid,
                                                        int limit,
                                                        int offset) throws Exception {
        String sparql = OscarWikidataQueryBuilder.winnersForAward(
                awardQid,
                limit,
                offset);

        return parseNominations(executeSparql(sparql));
    }

    public List<OscarNomination> readNominationPageForAward(String awardQid,
                                                            int limit,
                                                            int offset) throws Exception {
        String sparql = OscarWikidataQueryBuilder.nominationsForAward(
                awardQid,
                limit,
                offset);

        return parseNominations(executeSparql(sparql));
    }

    private String executeSparql(String sparql) throws Exception {
        String url = ENDPOINT
                + "?format=json&query="
                + URLEncoder.encode(sparql, StandardCharsets.UTF_8);

        // UrlOpener is the one polite HTTP path: it retries 429 and transient 5xx,
        // self-throttles and sends a contact User-Agent, so this reader no longer
        // carries its own retry loop or HttpClient.
        try (java.io.InputStream in = objectview.utils.UrlOpener.open(url)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<OscarNomination> parseNominations(String json) throws Exception {
        List<OscarNomination> out = new ArrayList<>();

        JsonNode root = mapper.readTree(json);
        JsonNode bindings = root.path("results").path("bindings");

        for (JsonNode b : bindings) {
            String nominee = value(b, "nomineeLabel");
            String nomineeQid = qid(value(b, "nominee"));

            String award = value(b, "awardLabel");
            String awardQid = qid(value(b, "award"));

            String work = value(b, "workLabel");
            String workQid = qid(value(b, "work"));

            String pointInTime = value(b, "time");
            int ceremonyYear = parseYear(pointInTime);

            String releaseDate = value(b, "releaseDate");
            int filmYear = parseYear(releaseDate);

            boolean winner = Boolean.parseBoolean(value(b, "winner"));

            OscarNomination n = new OscarNomination();
            n.setNominee(entityOrNull(nominee, nomineeQid));
            n.setAward(entityOrNull(award, awardQid));
            n.setWork(entityOrNull(work, workQid));
            n.setName();
            n.ceremonyYear = ceremonyYear;
            n.filmYear = filmYear;
            n.winner = winner;

            out.add(n);
        }

        return out;
    }

    // A missing nominee/award/work just yields a null relation rather than
    // aborting the whole award (WikidataDynamicObject.canonical throws on blank id).
    private static WikidataDynamicObject entityOrNull(String label, String qid) {
        return qid == null || qid.isBlank()
                ? null
                : WikidataDynamicObject.canonical(label, qid);
    }

    private String value(JsonNode binding, String name) {
        JsonNode node = binding.path(name).path("value");
        return node.isMissingNode() ? "" : node.asText("");
    }

    private String qid(String uri) {
        if (uri == null || uri.isBlank()) {
            return "";
        }

        int i = uri.lastIndexOf('/');
        return i >= 0 ? uri.substring(i + 1) : uri;
    }

    private int parseYear(String date) {
        if (date == null || date.length() < 4) {
            return 0;
        }

        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (Exception e) {
            return 0;
        }
    }

    private void sleepBetweenRequests() {
        try {
            Thread.sleep(REQUEST_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}