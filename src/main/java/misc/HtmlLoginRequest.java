package misc;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A Java class to demonstrate making an HTML request to a site
 * that requires a login. This example uses HttpURLConnection and
 * CookieManager to handle session management.
 */
public class HtmlLoginRequest {

    // Use a static CookieManager to maintain cookies across requests.
    // This is crucial for session-based authentication.
    private static final CookieManager cookieManager = new CookieManager();
    private static boolean issueRequest = false;
    
    public static void main(String[] args) {
        // Replace with the actual URLs for the login page and the protected page
        String loginUrlString = "https://www.pubsculture.hu/Belepes/";
        String protectedUrlString = "https://www.pubsculture.hu/Asztalfoglalas/";

        // Login credentials and form data
        Map<String, String> formData = Map.of(
            "username", "Kálmán csapata",
            "password", "kk.klmn",
            // You may need to inspect the login form to find other required fields,
            // such as a CSRF token.
            "csrf_token", "example_csrf_token"
        );

        try {
            // Step 1: Perform the login request
            System.out.println("Attempting to log in...");
            String loginResponse = sendPostRequest(loginUrlString, formData);
            System.out.println("Login response headers (cookies should be set):");
            // Note: The response body may be a redirect, so we'll just print a confirmation.
            System.out.println("Login successful. Cookies should be stored.");
            System.out.println("Content of the login page:");
            System.out.println(loginResponse);

            if (!issueRequest) return;
            System.out.println("\n----------------------------------\n");

            // Step 2: Request the protected page
            System.out.println("Attempting to access the protected page...");
            String protectedPageContent = sendGetRequest(protectedUrlString);
            System.out.println("Content of the protected page:");
            System.out.println(protectedPageContent);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends a POST request to a specified URL with form data.
     * This is typically used for submitting a login form.
     *
     * @param urlString The URL to send the POST request to.
     * @param formData  A Map of form fields and their values.
     * @return The response body as a String.
     * @throws Exception if an I/O error occurs.
     */
    public static String sendPostRequest(String urlString, Map<String, String> formData) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        // Configure the connection for a POST request
        connection.setRequestMethod("POST");
        connection.setDoOutput(true); // Enable writing data to the output stream
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        // The CookieHandler automatically handles cookies for us.
        // It stores cookies from the response and sends them with subsequent requests.
        java.net.CookieHandler.setDefault(cookieManager);

        // Build the POST data string
        StringJoiner postData = new StringJoiner("&");
        for (Map.Entry<String, String> entry : formData.entrySet()) {
            postData.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) +
                         "=" +
                         URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        // Write the POST data to the connection's output stream
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = postData.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // Read the response from the connection
        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
        } finally {
            connection.disconnect();
        }

        return response.toString();
    }

    /**
     * Sends a GET request to a specified URL.
     * This is typically used for accessing a protected page after a successful login.
     *
     * @param urlString The URL to send the GET request to.
     * @return The response body as a String.
     * @throws Exception if an I/O error occurs.
     */
    public static String sendGetRequest(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        // The CookieHandler automatically includes the session cookies from the login request.
        java.net.CookieHandler.setDefault(cookieManager);

        connection.setRequestMethod("GET");
        
        // Check if the request was successful (HTTP status code 200)
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("Failed to get protected page: HTTP error code " + connection.getResponseCode());
        }

        // Read the response from the connection
        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine).append("\n");
            }
        } finally {
            connection.disconnect();
        }

        return response.toString();
    }
}
