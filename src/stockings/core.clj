(ns stockings.core
  "Get current and historical stock quotes, current currency exchange rates,
   and info on industry sectors, industries, companies, and stocks
   from Yahoo! Finance."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (split join lower-case)]
        [clojure.contrib.def :only (defvar defvar-)]
        [clojure.contrib.json :only (read-json)])
  (:require [clj-http.client :as client]
            [stockings.yql :as yql])
  (:import (org.joda.time LocalDate LocalTime DateTimeZone)
           (java.util TimeZone)))

;;; NOTES

;;; Yahoo! Finance web services do not recognize stock symbols prefixed with
;;; a stock exchange, so we always strip it before passing the symbols
;;; to the web queries.

;;;
;;; Utilities
;;;

(defn bare-stock-symbol [^String stock-symbol]
  (last (split stock-symbol #":")))

(defn prefix-stock-symbol [^String exchange-symbol ^String stock-symbol]
  (if exchange-symbol
    (str exchange-symbol ":" stock-symbol)
    stock-symbol))

(defn explode-stock-symbol [^String stock-symbol]
  (let [[s exchange symbol] (re-matches #"(.*):(.*)" stock-symbol)]
    (if s
      [exchange symbol]
      [nil stock-symbol])))

(defn get-largest-lte
  "Returns the value mapped to the largest key that is less than or
   equal to the supplied key; returns not-found or nil if a matching key
   cannot be found. The supplied map should be a sorted-map
   (clojure.lang.TreeMap). You can use this function to lookup historical
   stock quotes by date and still get a valid quote when the date falls on
   a weekend or holiday. Use to-sorted-map to create a sorted map from a
   sequence of historical quotes."
  ([^clojure.lang.Sorted map key] (get-largest-lte map key nil))
  ([^clojure.lang.Sorted map key not-found]
     (if-let [lte-part (rsubseq map <= key)]
       (val (first lte-part))
       not-found)))

(defn to-sorted-map
  "Builds a sorted map from the supplied collection. The supplied get-key
   function is used to compute the key corresponding to a given item
   in the collection."
  [get-key xs]
  (apply sorted-map (mapcat (fn [x] [(get-key x) x]) xs)))

;;; NOTE: the date and time values returned by queries that are
;;; resolved to http://download.yahoo.finance.com/d/quotes.csv are
;;; wrong. It seems that the date is given for the UTC zone, but the
;;; time is given for the America/New_York time zone.
;;; The following code addresses the problem and assembles a correct
;;; DateTime object in UTC from the date and time given.
;;; You will want to use it if you are assembling a time stamp from
;;; the LastTradeDate and LastTradeTime fields in the stock quotes
;;; fetched by get-quotes or just use parse-last-trade-date-time.
;;; WARNING: use it only for data retrieved from the quotes.csv
;;; service above!

(defvar- nyc-date-time-zone
  (DateTimeZone/forTimeZone (TimeZone/getTimeZone "America/New_York")))

(defn get-correct-date-time [^LocalDate date ^LocalTime time]
  (let [wrong-date-time (.toDateTime date time nyc-date-time-zone)]
    (.withFields (.toDateTime wrong-date-time DateTimeZone/UTC) date)))

;;;
;;; Get current quotes
;;;

(defvar- quote-parse-map
  {:symbol identity
   :Name identity
   :PERatio yql/parse-double
   :EarningsShare yql/parse-double
   :EPSEstimateNextYear yql/parse-double
   :PercebtChangeFromYearHigh yql/parse-double ; typo is actually from data source
   :ChangeFromTwoHundreddayMovingAverage yql/parse-double
   :TwoHundreddayMovingAverage yql/parse-double
   :ChangeinPercent yql/parse-percent
   :Bid yql/parse-double
   :DaysLow yql/parse-double
   :DividendShare yql/parse-double
   :ChangeFromYearLow yql/parse-double
   :MarketCapitalization yql/parse-double
   :YearLow yql/parse-double
   :PriceSales yql/parse-double
   :ShortRatio yql/parse-double
   :PercentChange yql/parse-percent
   :EPSEstimateCurrentYear yql/parse-double
   :FiftydayMovingAverage yql/parse-double
   :ChangeRealtime yql/parse-double
   :PercentChangeFromFiftydayMovingAverage yql/parse-percent
   :LastTradePriceOnly yql/parse-double
   :ExDividendDate yql/parse-date
   :DividendPayDate yql/parse-date
   :Ask yql/parse-double
   :AskRealtime yql/parse-double
   :OneyrTargetPrice yql/parse-double
   :DividendYield yql/parse-percent
   :PercentChangeFromYearLow yql/parse-percent
   :PriceBook yql/parse-double
   :ChangeFromFiftydayMovingAverage yql/parse-double
   :AverageDailyVolume yql/parse-int
   :Open yql/parse-double
   :PercentChangeFromTwoHundreddayMovingAverage yql/parse-percent
   :BidRealTime yql/parse-double
   :BookValue yql/parse-double
   :LastTradeTime yql/parse-time
   :LastTradeDate yql/parse-date
   :Change yql/parse-double
   :Volume yql/parse-int
   :PEGRatio yql/parse-double
   :StockExchange identity
   :ChangeFromYearHigh yql/parse-double
   :DaysHigh yql/parse-double
   :YearHigh yql/parse-double
   :EBITDA yql/parse-double
   :Symbol identity
   :PreviousClose yql/parse-double
   :PERatioRealtime yql/parse-double
   :PriceEPSEstimateNextYear yql/parse-double
   :EPSEstimateNextQuarter yql/parse-double
   :PriceEPSEstimateCurrentYear yql/parse-double})

(defvar raw-quote-keys (keys quote-parse-map))

(defn parse-quote-item [raw-quote k]
  (if-let [value-parser (get quote-parse-map k)]
    (value-parser (get raw-quote k))))

(defn parse-last-trade-date-time [raw-quote]
  (let [date (parse-quote-item raw-quote :LastTradeDate)
        time (parse-quote-item raw-quote :LastTradeTime)]
    (get-correct-date-time date time)))

(defn build-quote-parser [key-map]
  (fn [raw-quote]
    (apply hash-map
           (mapcat (fn [[k v]] [k (parse-quote-item raw-quote v)]) key-map))))

(defvar- default-key-map
  {:symbol :symbol
   :name :Name
   :last :LastTradePriceOnly
   :open :Open
   :previous-close :PreviousClose
   :high :DaysHigh
   :low :DaysLow
   :volume :Volume})

(defvar- default-quote-parser* (build-quote-parser default-key-map))

(defn default-quote-parser [raw-quote]
  (let [stock-quote (default-quote-parser* raw-quote)]
    (assoc stock-quote
      :last-date-time (parse-last-trade-date-time raw-quote))))

(defn- wrap-error-check [parser]
  (fn [r]
    (if-not (:ErrorIndicationreturnedforsymbolchangedinvalid r)
      (parser r))))

(defn get-quotes [parser & stock-symbols]
  (let [[parser stock-symbols] (if (fn? parser)
                                  [parser stock-symbols]
                                  [default-quote-parser (cons parser
                                                              stock-symbols)])]
    (if stock-symbols
      (let [query (str "select * from yahoo.finance.quotes where symbol in "
                       (yql/yql-string-list (map bare-stock-symbol stock-symbols)))
            result (yql/submit-query query)]
        (yql/map-parser (wrap-error-check parser) (:quote result))))))

(defn get-quote
  ([parser stock-symbol] (first (get-quotes parser stock-symbol)))
  ([stock-symbol] (first (get-quotes stock-symbol))))

;;;
;;; Get historical quotes
;;;

;;; FIXME: consider using type annotations for the double and int/long fields!
(defrecord HistoricalQuote [^LocalDate date open high low close volume])

(defn- parse-historical-quote [r]
  (let [date (yql/parse-date (:date r))
        open (yql/parse-double (:Open r))
        high (yql/parse-double (:High r))
        low (yql/parse-double (:Low r))
        close (yql/parse-double (:Close r))
        volume (yql/parse-int (:Volume r))]
    (HistoricalQuote. date open high low close volume)))

(defn get-historical-quotes
  [^String stock-symbol ^LocalDate start-date ^LocalDate end-date]
  (let [query (str "select * from yahoo.finance.historicaldata where symbol = "
                   (yql/yql-string (bare-stock-symbol stock-symbol))
                   " and startDate = " (yql/yql-string start-date)
                   " and endDate = " (yql/yql-string end-date))
        result (yql/submit-query query)]
    (if result
      (yql/map-parser parse-historical-quote (:quote result)))))

;;;
;;; Get stocks
;;;

(defn- parse-stock [r]
  ;; If the requested stock symbol could not be found, the
  ;; :CompanyName key will be missing (in this case, the parser returns nil).
  (if (:CompanyName r)
    (let [stock-symbol (:symbol r)
          company-name (:CompanyName r)
          start-date (yql/parse-date (:start r))
          end-date (yql/parse-date (:end r))
          sector (:Sector r)
          industry (:Industry r)
          full-time-employees (yql/parse-int (:FullTimeEmployees r))]
      {:symbol stock-symbol
       :name company-name
       :start-date start-date
       :end-date end-date
       :sector sector
       :industry industry
       :full-time-employees full-time-employees})))

(defn get-stocks [& stock-symbols]
  (if stock-symbols
    (let [query (str "select * from yahoo.finance.stocks where symbol in "
                     (yql/yql-string-list (map bare-stock-symbol stock-symbols)))
          result (yql/submit-query query)]
      (yql/map-parser parse-stock (:stock result)))))

(defn get-stock [stock-symbol]
  (first (get-stocks stock-symbol)))

;;;
;;; Industries
;;;

(defn- parse-industry-sector [{:keys [name industry]}]
  {:name name, :industries industry})

(defn get-industry-sectors []
  (let [query "select * from yahoo.finance.sectors"
        result (yql/submit-query query)]
    (yql/map-parser parse-industry-sector (:sector result))))

(defn- parse-industry [{:keys [id name company]}]
  {:id id, :name name, :companies company})

(defn get-industries [& industry-ids]
  (if industry-ids
    (let [query (str "select * from yahoo.finance.industry where id in "
                  (yql/yql-string-list industry-ids))
          result (yql/submit-query query)]
      (yql/map-parser parse-industry (:industry result)))))

(defn get-industry [industry-id]
  (first (get-industries industry-id)))

;;;
;;; Get current exchange rate
;;;

(defn- parse-exchange-rate [r]
  ;; If the requested currency pair could not be found, the
  ;; value of the :Name key will be suffixed with "=X"
  ;; (in this case, the parser returns nil).
  (if-not (re-matches #".*=X" (:Name r))
    (let [id (:id r)
          base-currency (keyword (lower-case (subs id 0 3)))
          quote-currency (keyword (lower-case (subs id 3 6)))
          rate (yql/parse-double (:Rate r))
          ask (yql/parse-double (:Ask r))
          bid (yql/parse-double (:Bid r))
          date (yql/parse-date (:Date r))
          time (yql/parse-time (:Time r))
          date-time (get-correct-date-time date time)]
      {:base base-currency
       :quote quote-currency
       :rate rate
       :ask ask
       :bid bid
       :date-time date-time})))

(defn get-exchange-rates [& currency-pairs]
  (if currency-pairs
    (let [pairs (map (fn [[base-currency quote-currency]]
                       (str (name base-currency)
                            (name quote-currency))) currency-pairs)
          query (str "select * from yahoo.finance.xchange where pair in "
                     (yql/yql-string-list pairs))
          result (yql/submit-query query)]
      (yql/map-parser parse-exchange-rate (:rate result)))))

(defn get-exchange-rate [base-currency quote-currency]
  (first (get-exchange-rates [base-currency quote-currency])))

;;;
;;; Get stock symbol suggestion
;;;

(defn- strip-wrapper [^String s]
  (let [[s json-string] (re-matches #"YAHOO\.Finance.SymbolSuggest\.ssCallback\((.*)\)" s)]
    json-string))

(defn get-symbol-suggestion [^String company-name]
  (let [params {:query company-name
                :callback "YAHOO.Finance.SymbolSuggest.ssCallback"}
        response (client/get "http://autoc.finance.yahoo.com/autoc"
                             {:query-params params})
        status (:status response)]
    (if (not= status 200)
      (throw (RuntimeException. (str "Response status: " status))))
    (let [result (-> response :body strip-wrapper read-json :ResultSet :Result)]
      (if-not (empty? result) result))))

