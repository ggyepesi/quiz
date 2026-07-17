package nobel;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class OpenAIMotivationTopicExtractor implements MotivationTopicExtractor {
    private final String apiKey;
    private final String model;

    private static String aaiapiKey = "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";

    public static void main(String[] args) throws Exception {
        OpenAIMotivationTopicExtractor oai = new OpenAIMotivationTopicExtractor(aaiapiKey);
        String motivation = "for the discovery of macroscopic quantum mechanical tunnelling and energy quantisation in an electric circuit";
        List<String> topics = oai.extractTopics(motivation);
        for (String t : topics) {
            System.out.println(t);
        }
    }

    public OpenAIMotivationTopicExtractor(String apiKey) {
        this(apiKey, "gpt-4.1-mini");
    }

    public OpenAIMotivationTopicExtractor(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public List<String> extractTopics(String motivation) throws Exception {
        String prompt = """
                Extract 1-4 short topic phrases from this Nobel Prize motivation.

                Rules:
                - Return only topic phrases.
                - One phrase per line.
                - Use lowercase unless it is a proper noun.
                - Remove generic words like services, discovery, discoveries, work, contribution, research.
                - Prefer phrases like "theoretical physics", "structure of atoms", "radiation from atoms".
                - Do not explain.

                Motivation:
                %s
                """.formatted(motivation);

        String body = """
                {
                  "model": "%s",
                  "input": %s
                }
                """.formatted(model, jsonString(prompt));

        String json = post("https://api.openai.com/v1/responses", body);
        String text = extractOutputText(json);

        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            line = clean(line);
            if (!line.isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    private String post(String urlString, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(urlString).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Authorization", "Bearer " + apiKey);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        InputStream is = code >= 200 && code < 300
                ? con.getInputStream()
                : con.getErrorStream();

        String response = readAll(is);

        if (code < 200 || code >= 300) {
            throw new IOException("OpenAI API returned HTTP " + code + ":\n" + response);
        }

        return response;
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * Very small JSON extractor for Responses API output_text.
     * Avoids external JSON libraries.
     */
    private static String extractOutputText(String json) {
        String key = "\"output_text\"";
        int i = json.indexOf(key);
        if (i < 0) {
            // fallback for newer/alternative response shape
            key = "\"text\"";
            i = json.indexOf(key);
        }
        if (i < 0) return "";

        int colon = json.indexOf(':', i + key.length());
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return "";

        StringBuilder sb = new StringBuilder();
        boolean escaping = false;

        for (int p = start + 1; p < json.length(); p++) {
            char c = json.charAt(p);

            if (escaping) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    private static String jsonString(String s) {
        return "\"" + s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    private static String clean(String s) {
        return s == null ? "" : s
                                .replaceAll("^[-*•\\d.)\\s]+", "")
                                .replaceAll("[.;,]+$", "")
                                .trim();
    }
}