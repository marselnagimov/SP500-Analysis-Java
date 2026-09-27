import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Computes the return of each pick between an entry and exit price and writes
 * the files the LaTeX report reads.
 *
 * Input : results/picks.csv   (symbol,entry,exit)  - in ranking order
 * Output: report/returns.csv  (rank,symbol,entry,exit,ret) for the chart
 *         report/table.tex    rows for the picks table
 *         report/summary.tex  \newcommand macros with the summary statistics
 *
 * Usage: java -cp out Backtest [picks.csv] [reportDir]
 */
public class Backtest {

    record Pick(int rank, String symbol, double entry, double exit) {
        double ret() { return (exit / entry - 1) * 100; }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        Path in = Path.of(args.length > 0 ? args[0] : "results/picks.csv");
        Path out = Files.createDirectories(Path.of(args.length > 1 ? args[1] : "report"));

        List<Pick> picks = new ArrayList<>();
        List<String> lines = Files.readAllLines(in);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] f = line.split(",");
            picks.add(new Pick(picks.size() + 1, f[0].trim(),
                    Double.parseDouble(f[1]), Double.parseDouble(f[2])));
        }
        int n = picks.size();

        double[] r = picks.stream().mapToDouble(Pick::ret).sorted().toArray();
        double mean = 0;
        for (double x : r) mean += x;
        mean /= n;
        double var = 0;
        for (double x : r) var += (x - mean) * (x - mean);
        double sd = Math.sqrt(var / (n - 1));
        double median = n % 2 == 1 ? r[n / 2] : (r[n / 2 - 1] + r[n / 2]) / 2;
        long winners = picks.stream().filter(p -> p.ret() > 0).count();
        Pick best = picks.stream().max(Comparator.comparingDouble(Pick::ret)).orElseThrow();
        Pick worst = picks.stream().min(Comparator.comparingDouble(Pick::ret)).orElseThrow();

        // Top-10 vs. rest: does a higher rank mean a higher return?
        double top10 = picks.stream().limit(10).mapToDouble(Pick::ret).average().orElse(0);
        double rest = picks.stream().skip(10).mapToDouble(Pick::ret).average().orElse(0);

        // CSV for the chart
        StringBuilder csv = new StringBuilder("rank,symbol,entry,exit,ret\n");
        for (Pick p : picks) {
            csv.append(String.format("%d,%s,%.2f,%.2f,%.2f%n", p.rank(), p.symbol(), p.entry(), p.exit(), p.ret()));
        }
        Files.writeString(out.resolve("returns.csv"), csv);

        // Two side-by-side halves of the picks table
        int half = (n + 1) / 2;
        StringBuilder table = new StringBuilder("\\begin{tabular}{rlrrr@{\\hspace{2em}}rlrrr}\n\\toprule\n"
                + "\\# & Ticker & Entry & Exit & Ret. & \\# & Ticker & Entry & Exit & Ret. \\\\\n\\midrule\n");
        for (int i = 0; i < half; i++) {
            table.append(row(picks.get(i))).append(" & ");
            table.append(i + half < n ? row(picks.get(i + half)) : " & & & & ").append(" \\\\\n");
        }
        table.append("\\bottomrule\n\\end{tabular}\n");
        Files.writeString(out.resolve("table.tex"), table);

        String summary = String.join("\n",
                macro("NPicks", String.valueOf(n)),
                macro("MeanRet", pct(mean)),
                macro("MedianRet", pct(median)),
                macro("SdRet", String.format("%.1f\\%%", sd)),
                macro("Winners", String.valueOf(winners)),
                macro("Losers", String.valueOf(n - winners)),
                macro("HitRate", String.format("%.0f\\%%", 100.0 * winners / n)),
                macro("BestSym", best.symbol()), macro("BestRet", pct(best.ret())),
                macro("WorstSym", worst.symbol()), macro("WorstRet", pct(worst.ret())),
                macro("TopTenRet", pct(top10)), macro("RestRet", pct(rest))) + "\n";
        Files.writeString(out.resolve("summary.tex"), summary);

        System.out.printf("%d picks | mean %.2f%% | median %.2f%% | sd %.2f%% | %d winners | best %s %.2f%% | worst %s %.2f%%%n",
                n, mean, median, sd, winners, best.symbol(), best.ret(), worst.symbol(), worst.ret());
        System.out.printf("top-10 mean %.2f%% vs ranks 11-%d mean %.2f%%%n", top10, n, rest);
    }

    private static String row(Pick p) {
        String color = p.ret() >= 0 ? "pos" : "neg";
        return String.format("%d & %s & %.2f & %.2f & \\textcolor{%s}{%+.1f}",
                p.rank(), p.symbol(), p.entry(), p.exit(), color, p.ret());
    }

    private static String pct(double x) { return String.format("%+.1f\\%%", x); }

    private static String macro(String name, String value) {
        return "\\newcommand{\\" + name + "}{" + value + "}";
    }
}
