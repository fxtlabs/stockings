(ns stockings.alt
  "Alternative functions for getting current and historical stock quotes
   from Google Finance."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (join split-lines)]
        [clojure.contrib.def :only (defvar-)]
        [stockings.utils :only (parse-double parse-int parse-long parse-keyword)]
        [stockings.core :only (bare-stock-symbol)])
  (:require [clojure.xml :as xml]
            [clj-http.client :as client])
  (:import (java.net URLEncoder)
           (java.io ByteArrayInputStream)
           (org.joda.time DateTime LocalDate DateTimeZone)
           (org.joda.time.format DateTimeFormat)
           (stockings.core HistoricalQuote)))

;;;
;;; Get current quotes
;;;

(defvar- date-time-parser
  (.withZone (DateTimeFormat/forPattern "yyyyMMddHHmmss") DateTimeZone/UTC))

(defn- parse-date-time
  [^String date ^String time]
  (if-not (or (empty? date) (empty? time))
    (.parseDateTime date-time-parser (str date time))))

(defn- parse-quote [raw-quote]
  (let [raw-map (apply hash-map
                       (mapcat (fn [m] [(:tag m) (:data (:attrs m))])
                               (:content raw-quote)))]
    ;; If the requested stock symbol could not be found, the
    ;; value of the :company key will be empty
    ;; (in this case, the parser returns nil).
    (if-not (empty? (:company raw-map))
      {:symbol (:symbol raw-map)
       :exchange (:exchange raw-map)
       :name (:company raw-map)
       :currency (parse-keyword (:currency raw-map))
       :previous-close (parse-double (:y_close raw-map))
       :open (parse-double (:open raw-map))
       :low (parse-double (:low raw-map))
       :high (parse-double (:high raw-map))
       :last (parse-double (:last raw-map))
       :last-date-time (parse-date-time (:trade_date_utc raw-map)
                                        (:trade_time_utc raw-map))
       :change (parse-double (:change raw-map))
       :percent-change (/ (parse-double (:perc_change raw-map)) 100.0)
       :volume (parse-long (:volume raw-map))
       :avg-volume (parse-double (:avg_volume raw-map))})))

;;; Note that we strip any exchange prefix from the stock symbol
;;; because the Google service does not recognize them.
(defn- build-quotes-query-string [stock-symbols]
  (letfn [(to-param [s]
                    (str "stock=" (URLEncoder/encode (bare-stock-symbol s)
                                                     "UTF-8")))]
    (join "&" (map to-param stock-symbols))))

(defn get-quotes
  "Returns a sequence of stock quotes corresponding to the given stock
   symbols. A stock quote is a map with :symbol, :exchange, :name,
   :currency, :previous-close, :open, :low, :high, :last (trade),
   :last-date-time, :change, :percent-change, :volume, and :avg-volume
   keys. If data for a symbol cannot be found, its place in the result
   sequence will be `nil`."
  [& stock-symbols]
  (if stock-symbols
    (let [url (str "http://www.google.com/ig/api?"
                   (build-quotes-query-string stock-symbols))
          response (client/get url)
          status (:status response)]
      (if (not= status 200)
        (throw (Exception. (str status))))
      (let [input-stream (ByteArrayInputStream. (.getBytes (:body response)
                                                           "UTF-8"))
            payload (xml/parse input-stream)]
        (map parse-quote (:content payload))))))

(defn get-quote
  "Returns the current stock quote corresponding to the supplied stock
   symbol. The result is a map with :symbol, :exchange, :name,
   :currency, :previous-close, :open, :low, :high, :last (trade),
   :last-date-time, :change, :percent-change, :volume, and :avg-volume
   keys. If data for the supplied stock symbol cannot be found, it
   returns `nil`."
  [stock-symbol]
  (first (get-quotes stock-symbol)))

;;;
;;; Get historical quotes
;;;

(defvar- date-parser (DateTimeFormat/forPattern "dd-MMM-yy"))

(defn- parse-date
  "Parse a string representing a date into a `org.joda.time.LocalDate` object."
  [^String s]
  (.toLocalDate (.parseDateTime date-parser s)))

(defvar- re-line
  #"((?:[0-9]|[123][0-9])-\w{3}-[0-9]{2}),([0-9]+(?:\.[0-9]*)?),([0-9]+(?:\.[0-9]*)?),([0-9]+(?:\.[0-9]*)?),([0-9]+(?:\.[0-9]*)?),([0-9]+(?:\.[0-9]*)?)"
  "The regular expression used to match one line in the CSV-encoded quotes.
   It matches a line in the form `Date,Open,High,Low,Close,Volume`
   where the `Date` is given as dd-MMM-yy and the other fields are
   non-negative numbers with an optional fractional part.")

(defn- valid-record?
  "A predicate that validates whether the regular expression matches
   produced by one line of the CSV-encoded quotes were successful.
   The test is very basic and only checks that all the expected capturing
   groups have matches."
  [r]
  (and r
       (= 7 (count r))))

(defn- convert-record
  "Converts the regular expression matches corresponding to one line of
   the CSV-encoded quotes into a HistoricalQuote record."
  [r]
  (let [date (parse-date (nth r 1))
        open (Double/parseDouble (nth r 2))
        high (Double/parseDouble (nth r 3))
        low (Double/parseDouble (nth r 4))
        close (Double/parseDouble (nth r 5))
        volume (Double/parseDouble (nth r 6))]
    (HistoricalQuote. date open high low close volume)))

(defn parse-historical-quotes
  "Parses a string of CSV-encoded historical stock quotes and returns them
   as a sequence of `HistoricalQuote` records. It expects one quote per line
   with fields for date, open, high, low, close, and volume. The first line
   is assumed to be column headers and is discarded."
  [^String s]
  (->> s
       split-lines
       rest
       (map (partial re-matches re-line))
       (filter valid-record?)
       (map convert-record)))

(defn- get-historical-quotes*
  "Tries to get historical quotes from Google Finance. If the request fails
   with a 400 status code, it means the stock symbol could not be found or
   the dates were invalid; in this case, it just returns `nil`; otherwise, it
   rethrows the exception."
  [params]
   (try
     (client/get "http://www.google.com/finance/historical"
                 {:query-params params})
     (catch Exception e
       (let [status (parse-int (.getMessage e))]
         (if-not (= status 400)
           (throw e))))))

(defn get-historical-quotes
  "Returns a sequence of historical stock quotes corresponding to the
   supplied stock symbol, one quote per day between the supplied start
   and end dates (as `org.joda.time.LocalDate` objects). Quotes
   corresponding to dates falling on weekends and holidays are not
   included in the resulting sequence. If quotes for the given symbol
   or period cannot be found, it returns `nil`. The supplied stock symbol
   can have an optional exchange prefix (.e.g \"GOOG\" or \"NASDAQ:GOOG\")." 
  [^String stock-symbol ^LocalDate start-date ^LocalDate end-date]
  (let [params {:q stock-symbol
                :startdate (str start-date)
                :enddate (str end-date)
                :output "csv"}
        response (get-historical-quotes* params)]
    (when response
      (if (not= (:status response) 200)
        (throw (Exception. (str (:status response)))))
      (parse-historical-quotes (:body response)))))

