(ns stockings.exchanges
  "Functions to get a listing of the companies traded on the AMEX,
   NASDAQ, and NYSE stock exchanges. Based on the data made available
   by the NASDAQ at <http://www.nasdaq.com/screening/company-list.aspx>."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (split lower-case upper-case)]
        [clojure.contrib.def :only (defvar defvar-)]
        [clojure-csv.core :only (parse-csv)]
        [stockings.core :only (explode-stock-symbol)]
        [stockings.utils :only (interleave-if)])
  (:require [clj-http.client :as client]))

;;;
;;; Stock Exchanges
;;;

(defvar- source-url "http://www.nasdaq.com/screening/companies-by-name.aspx")
(defvar- default-record-keys [:stock-symbol :name :n/a :n/a :ipo-year :sector :industry :n/a :n/a])

(defvar nasdaq
  {:name "NASDAQ Stock Market", :symbol "NASDAQ"
   :source-url source-url :record-keys default-record-keys}
  "A map describing the NASDAQ Stock Market (NASDAQ).")

(defvar nyse
  {:name  "New York Stock Exchange", :symbol "NYSE"
   :source-url source-url :record-keys default-record-keys}
  "A map describing the New York Stock Exchange (NYSE).")

(defvar amex
  {:name "NYSE Amex Equities", :symbol "AMEX"
   :source-url source-url :record-keys default-record-keys}
  "A map describing the NYSE Amex Equities (AMEX).")

(defvar exchanges
  {:amex amex
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

;; Use a record instead of a map for efficiency since the lists of
;; companies are pretty long.
(defrecord Company [exchange symbol name ipo-year sector industry])

(defn- valid-record?
  "A predicate that returns true if the given CSV record is well formed.
   The test is very basic and mainly serves to eliminate blank lines and
   the ending comma (which causes the CSV parser to return an extra record)
   from the parsed CSV file."
  [r-keys r]
  (and
   (vector? r)
   (= (count r-keys) (count r))
   (< 0 (count (first r)))))

(defn new-company
  "Constructor function for Company datastructure. Requirest at least the named
   parameters :exchange-key :stock-symbol :name. The remaining parameters are
   optional and default to nil if not provided."
  [& {:keys [exchange-key stock-symbol name ipo-year sector industry] 
      :or {ipo-year nil sector nil industry nil}}]
  (Company. exchange-key stock-symbol name ipo-year sector industry))

(defn convert-record [exchange-key r-keys r]
  "Transforms a CSV record into a Company record.
   It assumes the CSV record is a sequence of strings corresponding to the
   fields specified in r-keys.
   The resulting Company record retains the exchange, symbol, name, IPO year,
   sector, and industry for the company."
  (apply (partial new-company :exchange-key exchange-key)
         (interleave-if (fn [x] (not= x :n/a)) r-keys r)))

(defn parse-companies
  "Parses a string of CSV-encoded companies and returns them
   as a sequence of Company records. It expects one company record per
   line.
   The first line is expected to contain the column headers and is discarded.
   The first parameter should be a key representing the exchange on which all
   the companies described in the input string are traded.
   The result includes exchange, symbol name, IPO year, sector, and
   industry for each company."
  [exchange-key ^String s]
  (let [r-keys (:record-keys (exchange-key exchanges))]
    (->> s
         parse-csv
         (filter (partial valid-record? r-keys))
         rest
         (map (partial convert-record exchange-key r-keys)))))

(defn get-companies-request
  "Requests a list of the companies traded on the stock exchange denoted by the supplied keyword.
  If no keyword is provided, it returns a merged string of all companies traded on the exchanges
  specified in exchanges."
  ([exchange-key]
     (let [params {:render "download", :exchange (name exchange-key)}
           source-url (:source-url (exchange-key exchanges))]
       (:body (client/get source-url {:query-params params}))))
  ([]
     (mapcat (fn [exchange-key] (get-companies exchange-key) (keys exchanges)))))

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
