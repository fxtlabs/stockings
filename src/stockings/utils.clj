(ns stockings.utils
  "Helper functions to access data through the YQL Web Service
   (Yahoo! Query Language) and parse a variety of value types."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (join lower-case)]
        [clojure.contrib.def :only (defvar defvar-)]
        [clojure.contrib.json :only (read-json)])
  (:require [clj-http.client :as client])
  (:import (org.joda.time DateTime LocalDate)
           (org.joda.time.format DateTimeFormat)))

(defvar- yql-base-url "http://query.yahooapis.com/v1/public/yql")

(defn yql-string
  "String values in a YQL query must be enclosed in double quotes.
   This function takes a value and returns it as a string wrapped in
   escaped double quotes."
  [v]
  (str "\"" v "\""))

(defn yql-string-list
  "A list of string values in a YQL query is a comma-separated list
   of strings, enclosed in parenthesis. Also, each string is wrapped
   in double quotes. This function takes a sequence of values and
   converts them in a list suitable for a YQL query."
  [vs]
  (str "(" (join "," (map yql-string vs)) ")"))

(defn- strip-wrapper [^String s]
  (subs s 8 (dec (count s))))

(defn submit-yql-query
  "Takes a YQL query string and submit it to Yahoo!'s YQL service,
   returning the value of the :result key in the JSON body of the
   response as a Clojure map. It throws an Exception if the
   response status is anything other than 200 or if the JSON response
   indicates an error (the error description is included in the
   exception)."
  [^String query]
  (let [params {:q query
                :format "json"
                :env "http://datatables.org/alltables.env"
                :callback "wrapper" ; wrap JSON response so errors are
                                    ; returned in payload rather than
                                    ; throw exceptions
                }
        response (client/get yql-base-url {:query-params params})
        status (:status response)]
    (if (not= status 200)
      (throw (Exception. (str status))))
    (let [payload (-> response :body strip-wrapper read-json)]
      (if-let [error (:error payload)]
        (throw (Exception. (:description error))))
      (:results (:query payload)))))

(defn map-parser
  "Takes a parser and a data value. If the data is a vector, it maps the
   parser to each item in the vector; otherwise, it simply calls the parser
   on the data value. This function is useful to process the result
   returned by submit-yql-query because YQL returns either a JSON object
   or a vector depending on whether the query returns one or more results."
  [parser data]
  (if (vector? data)
    (map parser data)
    (list (parser data))))

;;;
;;; Parsers
;;;

(defn parse-keyword
  "If the supplied string is not empty, it is returned as a lower-case
   keyword; otherwise it returns nil."
  [^String s]
  (if-not (empty? s)
    (keyword (lower-case s))))

(defn parse-int
  "If the supplied string represents a valid integer, it returns its
   value as an int; otherwise it returns nil."
  [^String s]
  (if s
    (if-let [m (re-matches #"(?:\+|\-)?\d+" s)]
      (Integer/parseInt m 10))))

(defvar- multipliers
  {"B" 1.0e9
   "M" 1.0e6
   "K" 1.0e3})

(defn parse-double
  "If the supplied string represents a valid number in standard decimal
   notation (e.g. 123.4 and not 1.234E2), it returns its value as a
   double; otherwise it returns nil. The string can optionally end with
   a letter K, M, or B indicating thousands, millions, or billions
   respectively."
  [^String s]
  (if s
    (if-let [m (re-matches #"((?:\+|\-)?\d+(?:\.\d*)?)(B|K|M)?" s)]
      (* (Double/parseDouble (nth m 1)) (get multipliers (nth m 2) 1.0)))))

(defn parse-percent
  "If the supplied string represents a valid percentage in standard
   decimal notation and ending with a % sign (e.g. 12.3%), it returns
   its fractional value as a double (e.g. 12.3% becomes 0.123);
   otherwise, it returns nil."
  [^String s]
  (if s
    (if-let [m (re-matches #"((?:\+|\-)?\d+(?:\.\d*)?)%" s)]
      (/ (Double/parseDouble (second m)) 100.0))))

(defvar- date-parsers
  [(DateTimeFormat/forPattern "yyyy-MM-dd")
   (DateTimeFormat/forPattern "M/dd/yyyy")])

(defn parse-date
  "If the supplied string represents a valid date in either of the
   yyyy-MM-dd or M/dd/yyyy formats, it returns the date as an
   org.joda.time.LocalDate object; otherwise, it returns nil."
  [^String s]
  (if s
    (first
     (for [parser date-parsers
           :let [d (try (.parseDateTime parser s) (catch Exception _ nil))]
           :when d]
       (.toLocalDate d)))))

(defvar- time-parser (DateTimeFormat/forPattern "hh:mmaa"))

(defn parse-time
  "If the supplied string represents a valid time in the hh:mmaa format
   (e.g. 9:30pm), it returns the time as an org.joda.time.LocalTime object;
   otherwise, it returns nil."
  [^String s]
  (if s
    (.toLocalTime (.parseDateTime time-parser s))))

