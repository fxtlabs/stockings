(ns stockings.exchanges
  "Functions to get a listing of the companies traded on the AMEX,
   NASDAQ, and NYSE stock exchanges. Based on the data made available
   by the NASDAQ at <http://www.nasdaq.com/screening/company-list.aspx>."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (split lower-case upper-case)]
        [clojure.contrib.def :only (defvar defvar-)]
        [clojure-csv.core :only (parse-csv)]
        [stockings.core :only (explode-stock-symbol)])
  (:require [clj-http.client :as client]))

;;;
;;; Stock Exchanges
;;;

(defvar nasdaq
  {:name "NASDAQ Stock Market", :symbol "NASDAQ"}
  "A map describing the NASDAQ Stock Market (NASDAQ).")

(defvar nyse
  {:name  "New York Stock Exchange", :symbol "NYSE"}
  "A map describing the New York Stock Exchange (NYSE).")

(defvar amex
  {:name "NYSE Amex Equities", :symbol "AMEX"}
  "A map describing the NYSE Amex Equities (AMEX).")

(defvar exchanges
  {:amex amex,
   :nasdaq nasdaq
   :nyse nyse}
  "A map from stock exchange keywords to stock exchange info maps.")

;;;
;;; Industry Sectors
;;;

(defvar industry-sectors
  ["Basic Industries"
   "Capital Goods"
   "Consumer Durables"
   "Consumer Non-Durables"
   "Consumer Services"
   "Energy"
   "Finance"
   "Healthcare"
   "Miscellaneous"
   "Public Utilities"
   "Technology"
   "Transportation"]
  "A list of the major industry sectors as classified by the NASDAQ.")

;;;
;;; Companies
;;;

(defvar- source-url "http://www.nasdaq.com/screening/companies-by-name.aspx")

;; Use a record instead of a map for efficiency since the lists of
;; companies are pretty long.
(defrecord Company [exchange symbol name ipo-year sector industry])

(defn- valid-record?
  "A predicate that returns true if the given CSV record is well formed.
   The test is very basic and mainly serves to eliminate blank lines and
   the ending comma (which causes the CSV parser to return an extra record)
   from the parsed CSV file."
  [r]
  (and
   (vector? r)
   (= 9 (count r))
   (< 0 (count (first r)))))

(defn- convert-record
  "Transforms a CSV record into a Company record.
   It assumes the CSV record is a sequence of strings corresponding to the
   following fields: symbol, name, last sale, market capitalization, IPO year,
   sector, industry, URL for summary quote.
   The resulting Company record retains the exchange, symbol, name, IPO year,
   sector, and industry for the company."
  [exchange-key r]
  (let [stock-symbol (upper-case (nth r 0))
        name (nth r 1)
        ipo-year (let [field (nth r 4)]
                   (if (re-find #"^\d{4}$" field) (Integer/parseInt field 10)))
        sector (nth r 5)
        industry (nth r 6)]
    (Company. exchange-key stock-symbol name ipo-year sector industry)))

(defn parse-companies
  "Parses a string of CSV-encoded companies and returns them
   as a sequence of Company records. It expects one company record per
   line with the following fields: symbol, name, last sale, market
   capitalization, IPO year, sector, industry, and URL for summary quote.
   The first line is expected to contain the column headers and is discarded.
   The first parameter should be a key representing the exchange on which all
   the companies described in the input string are traded.
   The result includes exchange, symbol name, IPO year, sector, and
   industry for each company."
  [exchange-key ^String s]
  (->> s
       parse-csv
       rest
       (filter valid-record?)
       (map (partial convert-record exchange-key))))

(defn get-companies
  "Requests a list of the companies traded on the stock exchange denoted by
   the supplied keyword. If no keyword is provided, it returns a merged list
   of the companies traded on the NASDAQ, NYSE, and AMEX exchanges.
   The companies are returned as a sequence of Company records.
   See `parse-companies` for details."
  ([exchange-key]
     (let [params {:render "download", :exchange (name exchange-key)}
           request (client/get source-url {:query-params params})]
       (parse-companies exchange-key (:body request))))
  ([]
     (mapcat (fn [exchange-key] (get-companies exchange-key)) (keys exchanges))))

(defn- build-companies-map [companies]
  (letfn [(merge-entry [m c]
                       (let [k (:symbol c)]
                         (if-let [v (get m k)]
                           (assoc m k (if (vector? v) (conj v c) [vector v c]))
                           (assoc m k c))))]
    (reduce merge-entry {} companies)))

(defn- normalize-stock-symbol [^String stock-symbol]
  (let [[exchange-symbol stock-symbol] (explode-stock-symbol stock-symbol)]
    [(if exchange-symbol (keyword (lower-case exchange-symbol)))
     (upper-case stock-symbol)]))

(defn build-lookup
  "Builds a lookup function corresponding to the supplied list of companies
   (a sequence of Company records). The lookup function takes a stock symbol
   and returns the Company record corresponding to it. The stock symbol may
   optionally be prefixed with a stock exchange (e.g. \"NASDAQ:GOOG\").
   If the stock exchange is not specified and multiple matches are found, it
   returns a vector of the matches; if no matches are found, it returns `nil`."
  [companies]
  (let [m (build-companies-map companies)]
    (fn [^String stock-symbol]
      (let [[exchange-key stock-symbol] (normalize-stock-symbol stock-symbol)
            res (get m stock-symbol)]
        (if exchange-key
          (if (vector? res)
            (first (filter (fn [c] (= exchange-key (:exchange c))) res))
            (if (= exchange-key (:exchange res)) res))
          res)))))

