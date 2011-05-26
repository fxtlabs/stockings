(ns stockings.historical
  "Functions for getting, parsing, and looking up historical stock quotes."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (split-lines)]
        [clojure.contrib.def :only (defvar-)])
  (:require [clj-http.client :as client])
  (:import (org.joda.time DateTime LocalDate)
           (org.joda.time.format DateTimeFormat)
           (stockings.core HistoricalQuote)))

(defvar- source-url "http://www.google.com/finance/historical")

(defvar- date-parser (DateTimeFormat/forPattern "dd-MMM-yy"))

(defn- parse-date
  "Parse a string representing a date into a org.joda.time.LocalDate object."
  [#^String s]
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
        open (Float/parseFloat (nth r 2))
        high (Float/parseFloat (nth r 3))
        low (Float/parseFloat (nth r 4))
        close (Float/parseFloat (nth r 5))
        volume (Float/parseFloat (nth r 6))]
    (HistoricalQuote. date open high low close volume)))

(defn parse-quotes
  "Parses a string of CSV-encoded historical stock quotes and returns them
   as a sequence of HistoricalQuote records."
  [#^String s]
  (->> s
       split-lines
       rest
       (map (partial re-matches re-line))
       (filter valid-record?)
       (map convert-record)))

(defn- get-quotes*
  "Requests historical stock quotes from the financial web service using
   the supplied parameters map to build a query string. The quotes are
   returned as a sequence of HistoricalQuote records."
  [params]
  (let [params (merge {:output "csv"} params)
        request (client/get source-url {:query-params params})]
    (parse-quotes (:body request))))

(defn get-quotes
  "Returns a sequence of historical stock quotes for the supplied stock
   symbol. The symbol can optionally be prefixed by the stock exchange
   (e.g. \"GOOG\" or \"NASDAQ:GOOG\"). A start and end date can be provided
   to constrain the range of historical quotes returned. Otherwise, it
   returns the quotes for one year up to the current date."
  ([#^String stock-symbol]
     (get-quotes* {:q stock-symbol}))
  ([#^String stock-symbol #^LocalDate start-date #^LocalDate end-date]
     (letfn [(add [params key date]
                  (if date
                    (assoc params key (str date))
                    params))]
       (-> {:q stock-symbol}
           (add :startdate start-date)
           (add :enddate end-date)
           get-quotes*))))

(defn get-quote
  "Returns the stock quotes for the supplied stock symbol and date.
   The symbol can optionally be prefixed by the stock exchange
   (e.g. \"GOOG\" or \"NASDAQ:GOOG\"). A start and end date can be provided
   to constrain the range of historical quotes returned. It returns nil if
   the stock market was closed on the requested date."
  [#^String stock-symbol #^LocalDate date]
  (let [res (get-quotes stock-symbol date date)]
    (if (empty? res) nil (first res))))

