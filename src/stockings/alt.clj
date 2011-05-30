(ns stockings.alt
  "Alternative functions for getting and parsing historical stock quotes."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (join split-lines lower-case)]
        [clojure.contrib.def :only (defvar-)]
        [stockings.utils :only (parse-double parse-int)])
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

(defn- parse-keyword [s]
  (if-not (empty? s)
    (keyword (lower-case s))))

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
       :volume (parse-int (:volume raw-map))
       :avg-volume (parse-double (:avg_volume raw-map))})))

(defn- build-quotes-query-string [stock-symbols]
  (join "&" (map (fn [s] (str "stock=" (URLEncoder/encode s "UTF-8")))
                 stock-symbols)))

(defn get-quotes [& stock-symbols]
  (if stock-symbols
    (let [url (str "http://www.google.com/ig/api?"
                   (build-quotes-query-string stock-symbols))
          response (client/get url)
          status (:status response)]
      (if (not= status 200)
        (throw (RuntimeException. (str "Response status: " status))))
      (let [input-stream (ByteArrayInputStream. (.getBytes (:body response)
                                                           "UTF-8"))
            payload (xml/parse input-stream)]
        (map parse-quote (:content payload))))))

(defn get-quote [stock-symbol]
  (first (get-quotes stock-symbol)))

;;;
;;; Get historical quotes
;;;

(defvar- date-parser (DateTimeFormat/forPattern "dd-MMM-yy"))

(defn- parse-date
  "Parse a string representing a date into a org.joda.time.LocalDate object."
  [^String s]
  (.toLocalDate (.parseDateTime date-parser s)))

(defvar- re-line
  #"((?:[0-9]|[123][0-9])-\w{3}-[0-9]{2}),([0-9]+(?:\.[0-9]*)?),([0-9]+(?:\.[0-9]*)?),([0-9]+(?:\.[0-9]*)?),([0-9]+(?:\.[0-9]*)?),([0-9]+(?:\.[0-9]*)?)"
  "The regular expression used to match one line in the CSV-encoded quotes.
   It matches a line in the form 'Date,Open,High,Low,Close,Volume'
   where the Date is given as dd-MMM-yy and the other fields are
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
   as a sequence of HistoricalQuote records."
  [^String s]
  (->> s
       split-lines
       rest
       (map (partial re-matches re-line))
       (filter valid-record?)
       (map convert-record)))

(defn get-historical-quotes
  "Returns a sequence of historical stock quotes for the supplied stock
   symbol. The symbol can optionally be prefixed by the stock exchange
   (e.g. \"GOOG\" or \"NASDAQ:GOOG\"). A start and end date must also
   be provided to constrain the range of historical quotes returned."
  [^String stock-symbol ^LocalDate start-date ^LocalDate end-date]
  (let [params {:q stock-symbol
                :startdate (str start-date)
                :enddate (str end-date)
                :output "csv"}
        response (client/get "http://www.google.com/finance/historical"
                             {:query-params params})
        status (:status response)]
    (if (not= status 200)
      (throw (RuntimeException. (str "Response status: " status))))
    (parse-historical-quotes (:body response))))

