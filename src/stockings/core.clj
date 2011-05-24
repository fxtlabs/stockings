(ns stockings.core
  "Functions for getting stock quotes and other financial info."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (join)]
        [clojure.contrib.def :only (defvar defvar-)]
        [clojure.contrib.json :only (read-json)])
  (:require [clj-http.client :as client]))

(defvar- source-url "http://query.yahooapis.com/v1/public/yql")

(defn- parse-int [s]
  (if s
    (if-let [m (re-matches #"(?:\+|\-)?\d+" s)]
      (Integer/parseInt m 10))))

(defvar- multipliers
  {"B" 1.0e9
   "M" 1.0e6})

(defn- parse-float [s]
  (if s
    (if-let [m (re-matches #"((?:\+|\-)?\d+(?:\.\d*)?)(B|M)?" s)]
      (* (Float/parseFloat (nth m 1)) (get multipliers (nth m 2) 1.0)))))

(defn- parse-percent [s]
  (if s
    (if-let [m (re-matches #"((?:\+|\-)?\d+(?:\.\d*)?)%" s)]
      (/ (Float/parseFloat (second m)) 100.0))))

;;; FIXME: not implemented yet! They should return a DateTime object.
(defn- parse-date [s] s)
(defn- parse-time [s] s)

(defvar- parse-map
  {:symbol identity
   :Name identity
   :PERatio parse-float
   :EarningsShare parse-float
   :EPSEstimateNextYear parse-float
   :PercebtChangeFromYearHigh parse-float ; typo is actually from data source
   :ChangeFromTwoHundreddayMovingAverage parse-float
   :TwoHundreddayMovingAverage parse-float
   :ChangeinPercent parse-percent
   :Bid parse-float
   :DaysLow parse-float
   :DividendShare parse-float
   :ChangeFromYearLow parse-float
   :MarketCapitalization parse-float
   :YearLow parse-float
   :PriceSales parse-float
   :ShortRatio parse-float
   :PercentChange parse-percent
   :EPSEstimateCurrentYear parse-float
   :FiftydayMovingAverage parse-float
   :ChangeRealtime parse-float
   :PercentChangeFromFiftydayMovingAverage parse-percent
   :LastTradePriceOnly parse-float
   :ExDividendDate parse-date
   :DividendPayDate parse-date
   :Ask parse-float
   :AskRealtime parse-float
   :OneyrTargetPrice parse-float
   :DividendYield parse-percent
   :PercentChangeFromYearLow parse-percent
   :PriceBook parse-float
   :ChangeFromFiftydayMovingAverage parse-float
   :AverageDailyVolume parse-int
   :Open parse-float
   :PercentChangeFromTwoHundreddayMovingAverage parse-percent
   :BidRealTime parse-float
   :BookValue parse-float
   :LastTradeTime parse-time
   :LastTradeDate parse-date
   :Change parse-float
   :Volume parse-int
   :PEGRatio parse-float
   :StockExchange identity
   :ChangeFromYearHigh parse-float
   :DaysHigh parse-float
   :YearHigh parse-float
   :EBITDA parse-float
   :Symbol identity
   :PreviousClose parse-float
   :PERatioRealtime parse-float
   :PriceEPSEstimateNextYear parse-float
   :EPSEstimateNextQuarter parse-float
   :PriceEPSEstimateCurrentYear parse-float})

(defvar quote-keys (keys parse-map))

(defvar simple-key-map
  {:stock-symbol :symbol
   :name :Name
   :last-trade :LastTradePriceOnly
   :open :Open
   :previous-close :PreviousClose
   :day-high :DaysHigh
   :day-low :DaysLow
   :volume :Volume})

(defn- parse-record [key-map r]
  (if-not (:ErrorIndicationreturnedforsymbolchangedinvalid r)
    (apply hash-map
           (mapcat (fn [[k v]] [k ((get parse-map v) (get r v))]) key-map))))

(defn- parse-quote
  [key-map #^String s]
  (let [results (-> s read-json :query :results :quote)]
    (if (vector? results)
      (map (partial parse-record key-map) results)
      (parse-record key-map results))))

(defn- build-yql-query [stock-symbols]
  (let [ss (join "," (map (fn [s] (str "\"" s "\"")) stock-symbols))]
    (str "select * from yahoo.finance.quotes where symbol in (" ss ")")))

(defn get-quote [key-map & stock-symbols]
  (if stock-symbols
    (let [params {:q (build-yql-query stock-symbols)
                  :format "json"
                  :env "http://datatables.org/alltables.env"}
          request (client/get source-url {:query-params params})]
      (parse-quote key-map (:body request)))))

(defn get-simple-quote [& stock-symbols]
  (apply get-quote simple-key-map stock-symbols))

