(ns stockings.yql
  "Helper functions to access data through the YQL Web Service (Yahoo! Query Language)."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.string :only (join)]
        [clojure.contrib.def :only (defvar defvar-)]
        [clojure.contrib.json :only (read-json)])
  (:require [clj-http.client :as client])
  (:import (org.joda.time DateTime LocalDate)
           (org.joda.time.format DateTimeFormat)))

(defvar- yql-base-url "http://query.yahooapis.com/v1/public/yql")

(defn yql-string [v]
  (str "\"" v "\""))

(defn yql-string-list [vs]
  (str "(" (join "," (map yql-string vs)) ")"))

(defn- strip-wrapper [^String s]
  (subs s 8 (dec (count s))))

(defn submit-query [^String query]
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
      (throw (RuntimeException. (str "Response status: " status))))
    (let [payload (-> response :body strip-wrapper read-json)]
      (if-let [error (:error payload)]
        (throw (RuntimeException. (:description error))))
      (:results (:query payload)))))

(defn map-parser [parser data]
  (if (vector? data)
    (map parser data)
    (parser data)))

;;;
;;; Parsers
;;;

(defn parse-int [s]
  (if s
    (if-let [m (re-matches #"(?:\+|\-)?\d+" s)]
      (Integer/parseInt m 10))))

(defvar- multipliers
  {"B" 1.0e9
   "M" 1.0e6})

(defn parse-double [s]
  (if s
    (if-let [m (re-matches #"((?:\+|\-)?\d+(?:\.\d*)?)(B|M)?" s)]
      (* (Double/parseDouble (nth m 1)) (get multipliers (nth m 2) 1.0)))))

(defn parse-percent [s]
  (if s
    (if-let [m (re-matches #"((?:\+|\-)?\d+(?:\.\d*)?)%" s)]
      (/ (Double/parseDouble (second m)) 100.0))))

;;; FIXME: not implemented yet! They should return a DateTime object.
(defn parse-time [s] s)

(defvar- date-parsers
  [(DateTimeFormat/forPattern "yyyy-MM-dd")
   (DateTimeFormat/forPattern "M/dd/yyyy")])

(defn parse-date
  "Parse a string representing a date into a org.joda.time.LocalDate object."
  [^String s]
  (if s
    (first
     (for [parser date-parsers
           :let [d (try (.parseDateTime parser s) (catch Exception _ nil))]
           :when d]
       (.toLocalDate d)))))

(defvar- time-parser (DateTimeFormat/forPattern "hh:mmaa"))

(defn parse-time
  "Parse a string representing a time into a org.joda.time.LocalTime object."
  [^String s]
  (if s
    (.toLocalTime (.parseDateTime time-parser s))))

