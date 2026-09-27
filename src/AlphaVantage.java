import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Thin Alpha Vantage client with an on-disk cache.
 *
 * The API key is read from the ALPHAVANTAGE_API_KEY environment variable
 * (never hard-code it). Every successful response is saved under cache/, so a
 * run that is interrupted by the rate limit can simply be restarted.
 */
final class AlphaVantage {
    private static final String BASE_URL = "https://www.alphavantage.co/query";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;
    private final Path cacheDir;
    private final long delayMs;

    AlphaVantage(String apiKey, Path cacheDir, long delayMs) throws IOException {
        this.apiKey = apiKey;
        this.cacheDir = Files.createDirectories(cacheDir);
        this.delayMs = delayMs;
    }

    /** Quarterly reports from INCOME_STATEMENT, BALANCE_SHEET or CASH_FLOW (newest first). */
    List<Object> quarterly(String function, String symbol) throws Exception {
        return list(fetch(function, symbol, ""), "quarterlyReports");
    }

    /** Company overview: ratios, analyst ratings, market cap, beta, ... */
    Map<String, Object> overview(String symbol) throws Exception {
        return fetch("OVERVIEW", symbol, "");
    }

    /** News articles about the symbol from the last six months. */
    List<Object> news(String symbol) throws Exception {
        String from = LocalDateTime.now().minusMonths(6)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm"));
        return list(fetch("NEWS_SENTIMENT", symbol, "&tickers=" + symbol + "&time_from=" + from), "feed");
    }

    /** Quarterly earnings with reported vs. estimated EPS. */
    List<Object> earnings(String symbol) throws Exception {
        return list(fetch("EARNINGS", symbol, ""), "quarterlyEarnings");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(String function, String symbol, String extra) throws Exception {
        Path cached = cacheDir.resolve(function + "_" + symbol + ".json");
        if (Files.exists(cached)) {
            return (Map<String, Object>) Json.parse(Files.readString(cached));
        }

        String query = function.equals("NEWS_SENTIMENT")
                ? "?function=" + function + extra
                : "?function=" + function + "&symbol=" + symbol + extra;
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + query + "&apikey=" + apiKey)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        Thread.sleep(delayMs);
        if (res.statusCode() != 200) {
            throw new IOException(function + " " + symbol + ": HTTP " + res.statusCode());
        }

        Map<String, Object> body = (Map<String, Object>) Json.parse(res.body());
        // Alpha Vantage signals rate limits / bad keys with HTTP 200 and a "Note" or "Information" field.
        for (String k : new String[]{"Note", "Information", "Error Message"}) {
            if (body.containsKey(k) && body.size() == 1) {
                throw new RateLimitException(function + " " + symbol + ": " + body.get(k));
            }
        }
        Files.writeString(cached, res.body());
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    static final class RateLimitException extends IOException {
        private static final long serialVersionUID = 1L;
        RateLimitException(String msg) { super(msg); }
    }
}
