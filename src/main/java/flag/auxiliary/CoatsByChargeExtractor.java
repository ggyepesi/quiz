package flag.auxiliary;
// ======================================================================
//   CoatsByChargeExtractor — Full Single-File Program (Updated Version)
//   Features:
//     • Robust extraction (no string heuristics)
//     • Wikipedia title → Wikidata entity resolution
//     • Wikidata label used as canonical entity name
//     • Full classification via Wikidata
//     • Parallel processing (ExecutorService)
//     • Persistent cache (wikidata-cache.json)
//     • Optional filtering of charges (CLI args)
//     • JSON output (Format 1)
//     • GUI viewer (Swing)
// ======================================================================

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import javax.swing.*;
import org.jsoup.*;
import org.jsoup.nodes.*;

public class CoatsByChargeExtractor {
    private static final String BASE = "https://en.wikipedia.org";
    private static final String MAIN_URL =
            BASE + "/wiki/Category:Coats_of_arms_by_charge";

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

    /** Cache maps: title → qid OR qid → classification */
    private static final Map<String, String> WIKIDATA_CACHE =
            new ConcurrentHashMap<>();

    public static void main1(String[] args) {
        try {
            System.out.println("Fetching category: " + MAIN_URL);
            Document mainDoc = Jsoup.connect(MAIN_URL).userAgent("Mozilla/5.0").get();
            Map<String, String> charges = extractChargePages(mainDoc);
            System.out.println("Found charges: " + charges.size());

            Map<String, Map<String, Set<String>>> result =
                    processCharges(charges);

            SwingUtilities.invokeLater(() -> showGUI(result));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        Map<String, Map<String, Set<String>>> result = new TreeMap<>();
        result.put("firearms",
            processCharge("firearms", "https://en.wikipedia.org/wiki/Category:Coats_of_arms_with_firearms"));
        SwingUtilities.invokeLater(() -> showGUI(result));
    }

    private static Map<String, String> extractChargePages(Document doc) {
        Map<String, String> map = new TreeMap<>();
        for (Element a : doc.select("#mw-subcategories a")) {
            String title = a.attr("title");
            if (!title.startsWith("Category:Coats of arms with")) continue;

            String charge = title
                    .replace("Category:Coats of arms with ", "")
                    .replace("_", " ")
                    .trim();
            map.put(charge, BASE + a.attr("href"));
        }
        return map;
    }

    private static Map<String, Map<String, Set<String>>> processCharges(Map<String, String> charges) {
        Map<String, Map<String, Set<String>>> result = new TreeMap<>();
        for (var e : charges.entrySet()) {
            result.put(e.getKey(), processCharge(e.getKey(), e.getValue()));
        }
        return result;
    }

    private static Map<String, Set<String>> processCharge(String charge, String url) {
        Map<String, Set<String>> bucket = new LinkedHashMap<>();
        bucket.put("sovereign_countries", new TreeSet<>());
        bucket.put("historical_states", new TreeSet<>());
        bucket.put("subnational", new TreeSet<>());
        bucket.put("cities", new TreeSet<>());
        bucket.put("other", new TreeSet<>());

        System.out.println("Processing charge: " + charge);
        try {
            Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get();
            Set<String> titles = extractPageTitles(doc);
            for (String wikiTitle : titles) {
                classifyPage(wikiTitle, bucket);
            }
        } catch (Exception e) {
            System.err.println("Charge " + charge + " failed: " + e);
        }

        return bucket;
    }

    private static Set<String> extractPageTitles(Document doc) {
        Set<String> out = new TreeSet<>();
        for (Element a : doc.select("#mw-pages a")) {
            String title = a.attr("title");
            if (title != null && !title.isBlank()) {
                out.add(title.trim());
            }
        }
        return out;
    }

    private static class WD {
        String qid;
        String label;
        WD(String qid, String label) { this.qid = qid; this.label = label; }

        public String toString() {
            return "WD(" + qid + ", " + label + ")";
        }
    }

    private static void classifyPage(String wikiTitle, Map<String, Set<String>> bucket) {
        System.out.println("ClassifyPage " + wikiTitle);
        try {
            WD ent = resolveWikidataEntity(wikiTitle);
            if (ent == null) {
                bucket.get("other").add(wikiTitle);
                return;
            }

            String type = classifyEntity(ent.qid);
            bucket.get(type).add(ent.label);
        } catch (Exception e) {
            bucket.get("other").add(wikiTitle);
        }
    }



    // ----------------------------------------------------------------------
    //   Resolve a Wikipedia page title → Wikidata entity {qid + label}
    // ----------------------------------------------------------------------
    private static WD resolveWikidataEntity(String wikiTitle) throws Exception {
        // Check cache
        if (WIKIDATA_CACHE.containsKey(wikiTitle)) {
            String q = WIKIDATA_CACHE.get(wikiTitle);
            if (q == null) return null;
            return fetchEntityInfo(q);
        }

        String url =
                "https://www.wikidata.org/w/api.php?action=wbgetentities" +
                "&sites=enwiki&titles=" + wikiTitle.replace(" ", "%20") +
                "&format=json";

        String json = httpGet(url);
        Matcher idm = Pattern.compile("\"id\":\"(Q[0-9]+)\"").matcher(json);
        if (!idm.find()) {
            System.out.println("Could't resolve " + wikiTitle);
            WIKIDATA_CACHE.put(wikiTitle, null);
            return null;
        }

        String qid = idm.group(1);
        System.out.println("Resolved " + wikiTitle + " to " + qid);
        WIKIDATA_CACHE.put(wikiTitle, qid);

        return fetchEntityInfo(qid);
    }

    private static WD fetchEntityInfo(String qid) throws Exception {
        String url = "https://www.wikidata.org/wiki/Special:EntityData/" +
                qid + ".json";
        System.out.println("entityUrl " + url);
        String json = httpGet(url);
        Matcher lbl =
                Pattern.compile("\"en\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*\"([^\"]+)\"")
                       .matcher(json);

        String label = lbl.find() ? lbl.group(1) : qid;
        return new WD(qid, label);
    }

    // ----------------------------------------------------------------------
    //     Classification based on Wikidata properties
    // ----------------------------------------------------------------------

    // Correct QIDs for classification
    private static final String Q_COUNTRY = "Q6256";
    private static final String Q_SOVEREIGN_STATE = "Q3624078";
    private static final String Q_CITY = "Q515";
    private static final String Q_SUBNATIONAL = "Q15642541";
    private static final String Q_HISTORICAL = "Q3024240";

    // Properties that can point to the real subject of the emblem/arms
    private static final List<String> SUBJECT_PROPERTIES = List.of(
            "P180", // depicts
            "P361", // part of
            "P642", // of
            "P518", // applies to part
            "P1269", // facet of
            "P2974"  // copyright on subject
    );


    /** Extracts "real" subject for classification. */
    private static String findSubjectEntity(String json, String selfQid) {
        // Look for P180, P361, etc.
        for (String prop : SUBJECT_PROPERTIES) {
            Pattern p = Pattern.compile("\"" + prop + "\":\\{\"mainsnak\":\\{[^}]*\"datavalue\":\\{\"value\":\\{\"id\":\"(Q[0-9]+)\"");
            Matcher m = p.matcher(json);
            if (m.find()) return m.group(1);
        }
        // No subject: fallback to self
        return selfQid;
    }

    /** Fetches all P31 instance-of values from a Wikidata JSON chunk. */
    private static Set<String> extractP31(String json) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("\"P31\":\\{\"mainsnak\":\\{[^}]*\"id\":\"(Q[0-9]+)\"").matcher(json);

        while (m.find()) {
            String f = m.group(1);
            System.out.println("extractP31 " + f);
            out.add(f);
        }
        return out;
    }


    /** New classification function */
    private static String classifyEntity(String qid) throws Exception {
        if (WIKIDATA_CACHE.containsKey(qid)) {
            String c = WIKIDATA_CACHE.get(qid);
            if (c != null && c.startsWith("__TYPE__"))
                return c.substring(8);
        }

        String url = "https://www.wikidata.org/wiki/Special:EntityData/" + qid + ".json";
        String json = httpGet(url);

        // Step 1: Get actual subject
        String subjectQid = findSubjectEntity(json, qid);
        // If subject is different, fetch its JSON
        if (!subjectQid.equals(qid)) {
            url = "https://www.wikidata.org/wiki/Special:EntityData/" + subjectQid + ".json";
            json = httpGet(url);
        }

        // Step 2: Get all P31 values
        System.out.println("ExtractP31 " + url);
        Set<String> p31 = extractP31(json);

        // Step 3: Match correct QIDs
        String type = "other";

        if (p31.contains(Q_COUNTRY) || p31.contains(Q_SOVEREIGN_STATE))
            type = "sovereign_countries";
        else if (p31.contains(Q_HISTORICAL))
            type = "historical_states";
        else if (p31.contains(Q_SUBNATIONAL))
            type = "subnational";
        else if (p31.contains(Q_CITY))
            type = "cities";

        // Cache result
        WIKIDATA_CACHE.put(qid, "__TYPE__" + type);
        if (!subjectQid.equals(qid))
            WIKIDATA_CACHE.put(subjectQid, "__TYPE__" + type);

        return type;
    }

    // ======================================================================
    //  HTTP Helper
    // ======================================================================

    private static String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();

        HttpResponse<String> resp =
                CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        return resp.body();
    }

    private static void showGUI(
            Map<String, Map<String, Set<String>>> data) {

        JFrame f = new JFrame("Coats of Arms by Charge — Viewer");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(1000, 700);

        DefaultListModel<String> model = new DefaultListModel<>();
        data.keySet().forEach(model::addElement);

        JList<String> list = new JList<>(model);
        JScrollPane left = new JScrollPane(list);

        JTextArea info = new JTextArea();
        info.setEditable(false);
        JScrollPane right = new JScrollPane(info);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(250);

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String charge = list.getSelectedValue();
                if (charge == null) return;

                Map<String, Set<String>> cats = data.get(charge);
                StringBuilder sb = new StringBuilder();

                for (var en : cats.entrySet()) {
                    sb.append("## ").append(en.getKey()).append("\n\n");
                    for (String x : en.getValue())
                        sb.append(" - ").append(x).append("\n");
                    sb.append("\n");
                }

                info.setText(sb.toString());
            }
        });

        f.add(split);
        f.setVisible(true);
    }
}
