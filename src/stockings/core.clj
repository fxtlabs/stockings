(ns stockings.core
  "Get current and historical stock quotes, current currency exchange rates,
   and info on industry sectors, industries, companies, and stocks
   from Yahoo! Finance."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (split join lower-case)]
        [clojure.contrib.def :only (defvar defvar-)]
        [clojure.contrib.json :only (read-json)])
  (:require [clj-http.client :as client]
            [stockings.utils :as yql])
  (:import (org.joda.time LocalDate LocalTime DateTimeZone)
           (java.util TimeZone)))

;;; NOTES

;;; Yahoo! Finance web services do not recognize stock symbols prefixed with
;;; a stock exchange, so we always strip it before passing the symbols
;;; to the web queries.

;;;
;;; Utilities
;;;

(defn prefix-stock-symbol
  "Takes the symbols for a stock exchange and a stock and combines them
   to create an exchange-prefixed stock symbol (e.g. \"NASDAQ\" and
   \"YHOO\" are combined into \"NASDAQ:YHOO\")."
  [^String exchange-symbol ^String stock-symbol]
  (if exchange-symbol
    (str exchange-symbol ":" stock-symbol)
    stock-symbol))

(defn explode-stock-symbol
  "Takes a stock symbol and explodes it into its exchange prefix and
   bare symbol parts, returning a vector with two entries. If the
   exchange prefix is missing, the first entry in the vector will be
   `nil`."
  [^String stock-symbol]
  (let [[s exchange symbol] (re-matches #"(.+):(.+)" stock-symbol)]
    (if s
      [exchange symbol]
      [nil stock-symbol])))

(defn bare-stock-symbol
  "Returns a stock symbol stripped of the stock exchange prefix, if any."
  [^String stock-symbol]
  (second (explode-stock-symbol stock-symbol)))

(defn get-largest-lte
  "Returns the value mapped to the largest key that is less than or
   equal to the supplied key; returns `not-found` or `nil` if a matching key
   cannot be found. The supplied map should be a sorted map. You can use
   this function to lookup historical stock quotes by date and still get
   a valid quote when the date falls on a weekend or holiday. Use
   `to-sorted-map` to create a sorted map from a sequence of historical quotes."
  ([^clojure.lang.Sorted map key] (get-largest-lte map key nil))
  ([^clojure.lang.Sorted map key not-found]
     (if-let [lte-part (rsubseq map <= key)]
       (val (first lte-part))
       not-found)))

(defn to-sorted-map
  "Builds a sorted map from the supplied collection. The supplied `get-key`
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
  (if (and date time)
    (let [wrong-date-time (.toDateTime date time nyc-date-time-zone)]
      (.withFields (.toDateTime wrong-date-time DateTimeZone/UTC) date))))

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
   :AverageDailyVolume yql/parse-long
   :Open yql/parse-double
   :PercentChangeFromTwoHundreddayMovingAverage yql/parse-percent
   :BidRealTime yql/parse-double
   :BookValue yql/parse-double
   :LastTradeTime yql/parse-time
   :LastTradeDate yql/parse-date
   :Change yql/parse-double
   :Volume yql/parse-long
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
   :PriceEPSEstimateCurrentYear yql/parse-double}
  "A map from the keys of a raw stock quote to the parsers used to parse
   the corresponding value strings of the raw stock quote into useful
   typed values (ints, doubles, dates, etc.).")

(defvar raw-quote-keys (keys quote-parse-map)
  "A list of all the keys available in a raw stock quote. A custom stock
   quote can be created by supplying `get-quote` with a parser capable of
   extracting the value corresponding to a chosen subset of these keys
   from a raw stock quote and packaging them into the desired result
   structure.")

(defn parse-quote-item
  "Looks up the value of a supplied key into a raw stock quote and
   converts it from a string into a more useful type (int, double, date,
   time, or keyword depending on the key). It returns `nil` if it cannot
   find a valid value."
  [raw-quote k]
  (if-let [value-parser (get quote-parse-map k)]
    (value-parser (get raw-quote k))))

(defn parse-last-trade-date-time
  "Looks up the last trade date and time in the supplied raw stock quote
   and combines them into an `org.joda.time.DateTime` object in UTC. It
   works around an inconsistency in the Yahoo! Finance data where the
   last trade date is represented in UTC while the last trade time seems
   to be using the North American East Coast time zone. It returns `nil`
   if a valid `DateTime` object cannot be created."
  [raw-quote]
  (let [date (parse-quote-item raw-quote :LastTradeDate)
        time (parse-quote-item raw-quote :LastTradeTime)]
    (get-correct-date-time date time)))

(defn build-quote-parser
  "Returns a parser that can be passed to `get-quote` and `get-quotes` to
   get custom stock quotes. The generated parser will return a stock quote
   in the form of a map with a set of desired keys. The builder expects a
   map from the set of desired keys to the corresponding keys in the raw
   stock quote (see `raw-quote-keys` for a list of available keys)."
  [key-map]
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

(defn default-quote-parser
  "The quote parser used by `get-quote` and `get-quotes` when a custom quote
   parser is not provided. It returns a stock quote in the form of a map
   with :symbol, :name, :last-date-time, :last, :open, :previous-close,
   :high, :low, and :volume keys."
  [raw-quote]
  (let [stock-quote (default-quote-parser* raw-quote)]
    (assoc stock-quote
      :last-date-time (parse-last-trade-date-time raw-quote))))

(defn- wrap-error-check
  "Augments a stock quote parser to check the raw quote for errors
   (typically arising when an invalid stock symbol is required of
   `get-quote`). The resulting parser will return `nil` if an error is
   found; otherwise, it will invoke the original parser."
  [parser]
  (fn [r]
    (if-not (:ErrorIndicationreturnedforsymbolchangedinvalid r)
      (parser r))))

(defn get-quotes
  "Returns a sequence of stock quotes corresponding to the given stock
   symbols. If the first parameter is a function, it uses it as a
   parser to turn a raw stock quote (provided by Yahoo! Finance) to a
   custom stock quote structure; otherwise, it uses `default-quote-parser`."
  [parser & stock-symbols]
  (let [[parser stock-symbols] (if (fn? parser)
                                  [parser stock-symbols]
                                  [default-quote-parser (cons parser
                                                              stock-symbols)])]
    (if stock-symbols
      (let [query (str "select * from yahoo.finance.quotes where symbol in "
                       (yql/yql-string-list (map bare-stock-symbol stock-symbols)))
            result (yql/submit-yql-query query)]
        (yql/map-parser (wrap-error-check parser) (:quote result))))))

(defn get-quote
  "Returns the stock quote corresponding to the given stock symbol.
   If the first parameter is a function, it uses it as a parser to turn
   a raw stock quote (provided by Yahoo! Finance) to a custom stock quote
   structure; otherwise, it uses `default-quote-parser`."
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
        volume (yql/parse-long (:Volume r))]
    (HistoricalQuote. date open high low close volume)))

(defn get-historical-quotes
  "Returns a sequence of historical stock quotes corresponding to the
   supplied stock symbol, one quote per day between the supplied start
   and end dates (as `org.joda.time.LocalDate` objects). Quotes
   corresponding to dates falling on weekends and holidays are not
   included in the resulting sequence. If quotes for the given symbol
   or period cannot be found, it returns `nil`.

   **NOTE:** The Yahoo! Finance service limits the requested period to about
   18 months. If the supplied date range describes a period longer than that
   limit, the query returns `nil`."
  [^String stock-symbol ^LocalDate start-date ^LocalDate end-date]
  (let [query (str "select * from yahoo.finance.historicaldata where symbol = "
                   (yql/yql-string (bare-stock-symbol stock-symbol))
                   " and startDate = " (yql/yql-string start-date)
                   " and endDate = " (yql/yql-string end-date))
        result (yql/submit-yql-query query)]
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

(defn get-stocks
  "Returns a sequence of stock descriptions for the supplied stock symbols.
   A stock description is a map with :symbol, :name, :start-date, :end-date,
   :sector, :industry, and :full-time-employees keys.
   If data for a symbol cannot be found that description will be `nil`."
  [& stock-symbols]
  (if stock-symbols
    (let [query (str "select * from yahoo.finance.stocks where symbol in "
                     (yql/yql-string-list (map bare-stock-symbol stock-symbols)))
          result (yql/submit-yql-query query)]
      (yql/map-parser parse-stock (:stock result)))))

(defn get-stock
  "Returns the stock description for the supplied stock symbols.
   This description is a map with :symbol, :name, :start-date, :end-date,
   :sector, :industry, and :full-time-employees keys.
   If data for a symbol cannot be found it returns `nil`."
  [stock-symbol]
  (first (get-stocks stock-symbol)))

;;;
;;; Industries
;;;

(defn- parse-industry-sector [{:keys [name industry]}]
  {:name name, :industries (if (vector? industry) industry [industry])})

(defn get-industry-sectors
  "Returns a sequence of industry sectors. Each sector is a map with
   :name and :industries keys. The value of the latter is a vector of
   maps corresponding to the industries for that sector. Each industry
   is a map with :id and :name fields. The id value can be passed to
   `get-industry` to get a list of the companies for a given industry."
  []
  (let [query "select * from yahoo.finance.sectors"
        result (yql/submit-yql-query query)]
    (yql/map-parser parse-industry-sector (:sector result))))

(defn- parse-industry [{:keys [id name company]}]
  (if id
    {:id id, :name name, :companies (if (vector? company) company [company])}))

(defn get-industries
  "Returns a sequence of the industries corresponding to the supplied
   industry ids. Each industry is returned as a map with :id, :name,
   and :companies keys. The value of the latter is a vector of maps
   corresponding to the companies in that industry. Each company is
   a map with :name and :symbol keys. The symbol value can be passed to
   `get-quote` or `get-historical-quotes` to get stock quotes for that company.
   If the industry for a given id cannot be found, its place in the result
   sequence will be nil."
  [& industry-ids]
  (if industry-ids
    (let [query (str "select * from yahoo.finance.industry where id in "
                  (yql/yql-string-list industry-ids))
          result (yql/submit-yql-query query)]
      (yql/map-parser parse-industry (:industry result)))))

(defn get-industry
  "Returns the industry corresponding to the supplied industry ids.
   The result is a map with :id, :name, and :companies keys. The value
   of the latter is a vector of maps corresponding to the companies in
   that industry. Each company is a map with :name and :symbol keys.
   The symbol value can be passed to `get-quote` or `get-historical-quotes`
   to get stock quotes for that company.
   If the industry for the supplied id cannot be found, it returns `nil`."
  [industry-id]
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

(defn get-exchange-rates
  "Returns a sequence of currency exchange rate for all the supplied
   currency pairs. Each currency pair is given as a vector of two items:
   base and quote currencies, specified as keywords or strings using the
   ISO 4217 3-letter designators (e.g. `[:usd :eur]`).
   The items in the returned sequence are maps with :base, :quote, :rate,
   :ask, :bid, and :date-time keys.
   If the exchange rate for a given currency pair cannot be found, its
   place in the result sequence will be `nil`."
  [& currency-pairs]
  (if currency-pairs
    (let [pairs (map (fn [[base-currency quote-currency]]
                       (str (name base-currency)
                            (name quote-currency))) currency-pairs)
          query (str "select * from yahoo.finance.xchange where pair in "
                     (yql/yql-string-list pairs))
          result (yql/submit-yql-query query)]
      (yql/map-parser parse-exchange-rate (:rate result)))))

(defn get-exchange-rate
  "Returns the currency exchange rate for the supplied currency pairs.
   The currency pair is given as two separate parameters: base and quote
   currencies, specified as keywords or strings using the ISO 4217
   3-letter designators (e.g. `(get-exchange-rate :usd :eur)`).
   The result is a map with :base, :quote, :rate, :ask, :bid, and
   :date-time keys.
   If the exchange rate for the given currency pair cannot be found, it
   returns `nil`."  
  [base-currency quote-currency]
  (first (get-exchange-rates [base-currency quote-currency])))

;;;
;;; Get stock symbol suggestion
;;;

(defn- strip-wrapper [^String s]
  (let [[s json-string] (re-matches #"YAHOO\.Finance.SymbolSuggest\.ssCallback\((.*)\)" s)]
    json-string))

(defn get-symbol-suggestion
  "Returns zero, one or more suggestions for the stock symbols corresponding
   to the supplied company name. Company name prefixes will return results
   as well. Note that large companies may be traded on several exchanges and
   may thus result in a long list of suggestions."
  [^String company-name]
  (let [params {:query company-name
                :callback "YAHOO.Finance.SymbolSuggest.ssCallback"}
        response (client/get "http://autoc.finance.yahoo.com/autoc"
                             {:query-params params})
        status (:status response)]
    (if (not= status 200)
      (throw (Exception. (str status))))
    (let [result (-> response :body strip-wrapper read-json :ResultSet :Result)]
      (if-not (empty? result) result))))

