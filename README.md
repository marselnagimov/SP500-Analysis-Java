# stock-ranker

A small Java screener that ranks S&P 500 companies on ~50 fundamental,
valuation, analyst, news-sentiment and earnings-surprise metrics from the
[Alpha Vantage](https://www.alphavantage.co/) API, plus a backtest of the top
50 picks and a short LaTeX report.

**Report:** [`report/report.pdf`](report/report.pdf)

## Result

The 50 top-ranked companies ([`results/picks.csv`](results/picks.csv)):

| | |
|---|---|
| Mean return (equal-weight) | **+17.4%** |
| Median return | +11.4% |
| Winners / losers | 35 / 15 (70%) |
| Best / worst | INTC +92.9% / UNH −42.6% |
| Ranks 1–10 vs. 11–50 | +19.6% vs. +16.9% |

## How the score works

For each metric the companies that report it are sorted best-first (ascending
for "lower is better" metrics like P/E, beta, liabilities or sell ratings).
The company in position *i* gets `weight × (N − i)` points, and a company's
score is the sum over all metrics. All metrics and weights are listed at the
top of [`src/StockRanker.java`](src/StockRanker.java), so changing the model
means editing one table.

| Source | Metrics | Aggregation |
|---|---|---|
| Income statement | 11 (revenue, net income, R&D, EBITDA, …) | mean of last 12 quarters |
| Balance sheet | 10 (assets, liabilities, equity, …) | mean of last 12 quarters |
| Cash flow | 8 (operating CF, capex, buybacks, …) | mean of last 12 quarters |
| Overview | 15 (P/E, P/B, beta, ROE, analyst ratings, …) | current value |
| Derived | gross/net margin, current ratio, debt/equity | from the averages |
| News sentiment | weight 6 | mean of ≤25 articles, last 6 months |
| Earnings surprise | weight 6 | mean of last 8 quarters |

## Run it

Requires Java 17+ (no other dependencies) and, for the report, a LaTeX
installation with `pgfplots`.

```bash
export ALPHAVANTAGE_API_KEY=your_key   # free key: alphavantage.co/support/#api-key
make rank        # fetch data, write results/ranking.csv, print the top 50
make report      # backtest results/picks.csv and build report/report.pdf
```

Each ticker needs 6 API calls, so the full list is ~3,000 calls — more than the
free tier's daily limit. Every response is cached in `cache/`; when the limit
is hit the program stops cleanly and the next run continues where it left off.

To backtest a new set of picks, put `symbol,entry,exit` prices in
`results/picks.csv` (in ranking order) and run `make report`.

## Project layout

```
src/StockRanker.java   metric table, data collection, scoring
src/AlphaVantage.java  API client with on-disk cache and rate-limit detection
src/Json.java          tiny JSON parser (no external jar needed)
src/Backtest.java      returns + summary stats -> files used by the report
data/sp500.txt         ticker universe
results/picks.csv      top-50 picks with entry/exit prices
report/report.tex      LaTeX report (report.pdf is the built version)
```

## Changes from the original version

- **~880 lines → ~300** for the ranker and API client. One `Metric` table replaces ~50 hand-written lists,
  four copies of merge sort (`List.sort`), and `Pair`/`PairDouble`;
  the unused `BinarySearchTree`, `Node` and `Main` classes are gone.
- **No hard-coded API key** — read from `ALPHAVANTAGE_API_KEY`.
- **No `org.json` jar** — a ~100-line parser in `Json.java`.
- **Bug fixes:**
  - Gross margin, net margin, current ratio and debt/equity used integer
    division, so they were almost always 0 and silently dropped.
  - Price-to-book was scored twice in opposite directions and cancelled out.
  - News used `symbol=` which `NEWS_SENTIMENT` ignores (it needs `tickers=`),
    so every company got the same general-market sentiment.
  - Balance-sheet items were *summed* over 12 quarters, penalising companies
    with shorter histories; all quarterly items are now averaged.
  - A delisted ticker (the list has many, e.g. `AET`, `YHOO`) returned
    `null` and crashed the whole run; it is now skipped.
  - Rate-limit responses are detected instead of being parsed as empty data.

*Student project, not investment advice.*
