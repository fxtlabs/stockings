(ns stockings.exchanges
  "Functions to get basic info on the AMEX, NASDAQ, and NYSE stock exchanges."
  {:author "Filippo Tampieri <fxt@fxtlabs.com>"}
  (:use [clojure.contrib.def :only (defvar)]))

(defrecord StockExchange [key name short-name])

(defvar nasdaq
  (StockExchange. :nasdaq "NASDAQ Stock Market" "NASDAQ")
  "A StockExchange object describing the NASDAQ Stock Market (NASDAQ).")

(defvar nyse
  (StockExchange. :nyse "New York Stock Exchange" "NYSE")
  "A StockExchange object describing the New York Stock Exchange (NYSE).")

(defvar amex
  (StockExchange. :amex "NYSE Amex Equities" "AMEX")
  "A StockExchange object describing the NYSE Amex Equities (AMEX).")

(defvar exchanges
  {:amex amex,
   :nasdaq nasdaq
   :nyse nyse}
  "A map from stock exchange keywords to StockExchange objects.")


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

