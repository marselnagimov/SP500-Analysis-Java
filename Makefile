.PHONY: build rank backtest report all clean

build:
	javac -d out src/*.java

# Needs ALPHAVANTAGE_API_KEY in the environment
rank: build
	java -cp out StockRanker data/sp500.txt 50

backtest: build
	java -cp out Backtest results/picks.csv report

report: backtest
	cd report && latexmk -pdf -interaction=nonstopmode report.tex

all: report

clean:
	rm -rf out
	cd report && latexmk -C report.tex
