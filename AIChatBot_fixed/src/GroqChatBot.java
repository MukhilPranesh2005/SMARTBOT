import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class GroqChatBot {

    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
    // Loaded at startup from a .env file (falls back to a real OS environment variable if present)
    private static final String API_KEY = loadApiKey();

    public static void main(String[] args) {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.out.println("ERROR: GROQ_API_KEY not found.");
            System.out.println("Create a .env file next to this program with a line like:");
            System.out.println("  GROQ_API_KEY=your_real_key_here");
            return; // fail fast instead of starting a server that can never answer
        }

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/chat", new ChatHandler());
            server.setExecutor(null);
            server.start();
            System.out.println("Server running at http://localhost:8080/chat");
        } catch (Exception e) {
            System.out.println("Error starting server: " + e.getMessage());
        }
    }

    /**
     * Reads GROQ_API_KEY from a local .env file (KEY=VALUE per line).
     * Java does NOT read .env files automatically -- this is why the original
     * project's System.getenv("GROQ_API_KEY") was always null unless the OS
     * environment variable was set manually in the shell.
     * Falls back to a real OS env var of the same name if no .env is found.
     */
    private static String loadApiKey() {
        Path envPath = Path.of(".env");
        if (Files.exists(envPath)) {
            try {
                for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq == -1) continue;
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    // strip optional surrounding quotes
                    if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\""))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (key.equals("GROQ_API_KEY")) {
                        return value;
                    }
                }
            } catch (IOException e) {
                System.out.println("Could not read .env file: " + e.getMessage());
            }
        }
        return System.getenv("GROQ_API_KEY");
    }

    private static String getGroqResponse(String userMessage) {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);

            String jsonBody = "{"
                    + "\"model\":\"llama-3.1-8b-instant\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}]"
                    + "}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String result = readAll(is);

            if (status >= 400) {
                System.out.println("Groq API error (" + status + "): " + result);
                return "Groq API error " + status + " -- check server console for details.";
            }

            String content = extractContent(result);
            return content != null ? content : "No response from AI (unexpected API response format).";

        } catch (Exception e) {
            System.out.println("Error calling Groq API: " + e.getMessage());
            return "Error contacting Groq API: " + e.getMessage();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Minimal, escape-aware extraction of the "content" field's value from the
     * Groq chat-completions JSON response, without needing an external JSON library.
     */
    private static String extractContent(String json) {
        String marker = "\"content\":\"";
        int start = json.indexOf(marker);
        if (start == -1) return null;
        start += marker.length();

        StringBuilder result = new StringBuilder();
        boolean escaping = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) {
                switch (c) {
                    case 'n': result.append('\n'); break;
                    case 't': result.append('\t'); break;
                    case 'r': result.append('\r'); break;
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    default: result.append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                return result.toString();
            } else {
                result.append(c);
            }
        }
        return result.toString(); // unterminated string, best effort
    }

    static class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // CORS: without these headers, a browser blocks the frontend's fetch()
            // whenever the page isn't served from the exact same origin as this
            // server -- this is what causes the "Server not running" message in the
            // UI even when the Java process is up and listening.
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1); // CORS preflight
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String userMessage = readAll(exchange.getRequestBody());
            String botReply = getGroqResponse(userMessage);

            byte[] bytes = botReply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
