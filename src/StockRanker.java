import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks a list of tickers by a weighted "rank-sum" over ~50 fundamental,
 * valuation, analyst, sentiment and earnings-surprise metrics.
 *
 * For every metric the companies that have a value are sorted best-first,
 * and the company at position i (0-based) earns weight * (N - i) points,
 * where N is the number of tickers. Scores are summed across metrics.
 *
 * Usage:
 *   export ALPHAVANTAGE_API_KEY=...
 *   java -cp out StockRanker [tickers.txt] [topN]
 */
public class StockRanker {

    /** One ranked metric: its name, how much it counts, and which direction is good. */
    record Metric(String name, int weight, boolean higherIsBetter) {}

    private static Metric up(String name, int w)   { return new Metric(name, w, true); }
    private static Metric down(String name, int w) { return new Metric(name, w, false); }

    // Income statement (average of the last 12 quarters)
    static final List<Metric> INCOME = List.of(
            up("totalRevenue", 1), up("netIncome", 3), up("grossProfit", 1), up("operatingIncome", 1),
            up("researchAndDevelopment", 3), up("ebit", 1), up("ebitda", 2),
            up("sellingGeneralAndAdministrative", 1), down("costOfRevenue", 1),
            down("operatingExpenses", 1), down("interestAndDebtExpense", 1));

    // Balance sheet (average of the last 12 quarters)
    static final List<Metric> BALANCE = List.of(
            up("totalCurrentAssets", 1), up("cashAndCashEquivalentsAtCarryingValue", 1),
            down("totalCurrentLiabilities", 1), up("totalAssets", 1), down("totalLiabilities", 1),
            up("totalShareholderEquity", 1), up("retainedEarnings", 1), up("inventory", 1),
            up("currentNetReceivables", 1), up("totalNonCurrentAssets", 2));

    // Cash-flow statement (average of the last 12 quarters)
    static final List<Metric> CASH_FLOW = List.of(
            down("capitalExpenditures", 1), down("cashflowFromInvestment", 3),
            down("proceedsFromRepurchaseOfEquity", 1), up("operatingCashflow", 1), up("profitLoss", 2),
            up("cashflowFromFinancing", 1), up("proceedsFromIssuanceOfLongTermDebtAndCapitalSecuritiesNet", 3),
            down("depreciationDepletionAndAmortization", 1));

    // Company overview (current snapshot)
    static final List<Metric> OVERVIEW = List.of(
            down("PERatio", 2), down("PriceToBookRatio", 2), down("PriceToSalesRatioTTM", 2),
            up("DividendYield", 1), up("DividendPerShare", 1), up("OperatingMarginTTM", 1),
            up("ReturnOnEquityTTM", 1), up("BookValue", 2), up("QuarterlyEarningsGrowthYOY", 3),
            down("Beta", 3), up("MarketCapitalization", 1),
            up("AnalystRatingStrongBuy", 3), up("AnalystRatingBuy", 2),
            down("AnalystRatingSell", 2), down("AnalystRatingStrongSell", 2));

    // Derived ratios and the two heavily weighted signals
    static final List<Metric> DERIVED = List.of(
            up("grossMargin", 1), up("netMargin", 1), up("currentRatio", 2), down("debtToEquity", 2),
            up("newsSentiment", 6), up("earningsSurprise", 6));

    static final int QUARTERS = 12;

    public static void main(String[] args) throws Exception {
        Path tickerFile = Path.of(args.length > 0 ? args[0] : "data/sp500.txt");
        int topN = args.length > 1 ? Integer.parseInt(args[1]) : 50;

        String key = System.getenv("ALPHAVANTAGE_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("Set the ALPHAVANTAGE_API_KEY environment variable first.");
            System.exit(1);
        }
        long delay = Long.parseLong(System.getenv().getOrDefault("ALPHAVANTAGE_DELAY_MS", "800"));
        AlphaVantage api = new AlphaVantage(key, Path.of("cache"), delay);

        List<String> symbols = Files.readAllLines(tickerFile).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        // symbol -> (metric name -> value)
        Map<String, Map<String, Double>> data = new LinkedHashMap<>();
        for (String symbol : symbols) {
            System.out.printf("[%d/%d] %s%n", data.size() + 1, symbols.size(), symbol);
            try {
                data.put(symbol, collect(api, symbol));
            } catch (AlphaVantage.RateLimitException e) {
                System.err.println("Stopped by API limit: " + e.getMessage());
                System.err.println("Responses so far are cached - rerun later to continue.");
                System.exit(2);
            } catch (Exception e) {
                System.err.println("  skipped " + symbol + ": " + e.getMessage());
                data.put(symbol, Map.of());
            }
        }

        Map<String, Long> scores = score(data);
        List<Map.Entry<String, Long>> ranking = new ArrayList<>(scores.entrySet());
        ranking.sort(Map.Entry.<String, Long>comparingByValue().reversed());

        Files.createDirectories(Path.of("results"));
        StringBuilder csv = new StringBuilder("rank,symbol,score\n");
        for (int i = 0; i < ranking.size(); i++) {
            csv.append(i + 1).append(',').append(ranking.get(i).getKey()).append(',')
               .append(ranking.get(i).getValue()).append('\n');
        }
        Files.writeString(Path.of("results/ranking.csv"), csv);

        System.out.println("\nTop " + topN + ":");
        for (int i = 0; i < Math.min(topN, ranking.size()); i++) {
            System.out.printf("%3d. %-6s %d%n", i + 1, ranking.get(i).getKey(), ranking.get(i).getValue());
        }
        System.out.println("\nFull ranking written to results/ranking.csv");
    }

    /** Fetch everything for one company and flatten it into metric values. */
    @SuppressWarnings("unchecked")
    static Map<String, Double> collect(AlphaVantage api, String symbol) throws Exception {
        Map<String, Double> v = new HashMap<>();

        averageQuarters(api.quarterly("INCOME_STATEMENT", symbol), INCOME, v);
        averageQuarters(api.quarterly("BALANCE_SHEET", symbol), BALANCE, v);
        averageQuarters(api.quarterly("CASH_FLOW", symbol), CASH_FLOW, v);

        Map<String, Object> overview = api.overview(symbol);
        for (Metric m : OVERVIEW) {
            Double d = number(overview.get(m.name()));
            if (d != null) v.put(m.name(), d);
        }

        ratio(v, "grossMargin", "grossProfit", "totalRevenue");
        ratio(v, "netMargin", "netIncome", "totalRevenue");
        ratio(v, "currentRatio", "totalCurrentAssets", "totalCurrentLiabilities");
        ratio(v, "debtToEquity", "totalLiabilities", "totalShareholderEquity");

        // Average overall sentiment of up to 25 recent articles
        List<Object> news = api.news(symbol);
        average(news.subList(0, Math.min(25, news.size())), "overall_sentiment_score")
                .ifPresent(d -> v.put("newsSentiment", d));

        // Average EPS surprise (%) over the last 8 quarters
        List<Object> earnings = api.earnings(symbol);
        average(earnings.subList(0, Math.min(8, earnings.size())), "surprisePercentage")
                .ifPresent(d -> v.put("earningsSurprise", d));

        return v;
    }

    /** Average each metric over the most recent QUARTERS reports that have a value. */
    static void averageQuarters(List<Object> reports, List<Metric> metrics, Map<String, Double> out) {
        List<Object> recent = reports.subList(0, Math.min(QUARTERS, reports.size()));
        for (Metric m : metrics) {
            average(recent, m.name()).ifPresent(d -> out.put(m.name(), d));
        }
    }

    @SuppressWarnings("unchecked")
    static java.util.Optional<Double> average(List<Object> rows, String field) {
        double sum = 0;
        int n = 0;
        for (Object row : rows) {
            Double d = number(((Map<String, Object>) row).get(field));
            if (d != null) { sum += d; n++; }
        }
        return n == 0 ? java.util.Optional.empty() : java.util.Optional.of(sum / n);
    }

    static void ratio(Map<String, Double> v, String name, String num, String den) {
        Double a = v.get(num), b = v.get(den);
        if (a != null && b != null && b != 0) v.put(name, a / b);
    }

    /** Alpha Vantage sends numbers as strings and uses "None" / "-" for missing values. */
    static Double number(Object o) {
        if (o instanceof Double d) return d;
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /** Weighted rank-sum across all metrics. */
    static Map<String, Long> score(Map<String, Map<String, Double>> data) {
        int n = data.size();
        Map<String, Long> scores = new LinkedHashMap<>();
        data.keySet().forEach(s -> scores.put(s, 0L));

        List<Metric> all = new ArrayList<>();
        for (List<Metric> group : List.of(INCOME, BALANCE, CASH_FLOW, OVERVIEW, DERIVED)) all.addAll(group);

        for (Metric m : all) {
            List<String> have = new ArrayList<>(data.keySet().stream()
                    .filter(s -> data.get(s).containsKey(m.name())).toList());
            Comparator<String> byValue = Comparator.comparingDouble(s -> data.get(s).get(m.name()));
            have.sort(m.higherIsBetter() ? byValue.reversed() : byValue);
            for (int i = 0; i < have.size(); i++) {
                scores.merge(have.get(i), (long) m.weight() * (n - i), Long::sum);
            }
        }
        return scores;
    }
}
