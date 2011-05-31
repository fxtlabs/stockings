(ns stockings.test.core
  (:use clojure.test
        stockings.core)
  (:import [org.joda.time LocalDate]))

(deftest test-stock-symbol-ops
  (testing "Basic operations on stock symbols"
    (is (= (prefix-stock-symbol "abcd" "efgh") "abcd:efgh"))
    (is (= (prefix-stock-symbol nil "abcd") "abcd"))
    (are [x y] (= (explode-stock-symbol x) y)
         "abcd" [nil "abcd"]
         "abcd:efgh" ["abcd" "efgh"]
         ":abcd" [nil ":abcd"])
    (are [x y] (= (bare-stock-symbol x) y)
         "abcd" "abcd"
         "abcd:efgh" "efgh"
         "ab:cd:ef:gh" "gh"
         ":abcd" ":abcd"
         "abcd:" "abcd:")))

(deftest test-current-stock-quotes
  (testing "Current stock quotes"
    (let [yhoo (get-quote "yhoo")]
      (is (= (:symbol yhoo "YHOO")))
      (is (= (:name yhoo) "Yahoo! Inc.")))
    (is (not (get-quote "invalid")))
    (let [stock-quotes (get-quotes "yhoo" "invalid" "goog")]
      (is (= (count stock-quotes) 3))
      (let [[yhoo invalid goog] stock-quotes]
        (is (= (:symbol yhoo "YHOO")))
        (is (not invalid))
        (is (= (:symbol goog "GOOG")))))
    (let [kmap {:symbol :symbol, :name :Name, :ask :Ask, :volume :Volume}
          parser (build-quote-parser kmap)
          intc (get-quote parser "intc")]
      (is (= (sort (keys kmap)) (sort (keys intc))))
      (is (= (:symbol intc) "INTC"))
      (is (= (:name intc) "Intel Corporation")))))

(deftest test-historical-stock-quotes
  (testing "Historical stock quotes"
    (let [stock-quotes (get-historical-quotes "yhoo"
                                              (LocalDate. 2010 1 1)
                                              (LocalDate. 2011 4 30))
          quotes-map (to-sorted-map :date stock-quotes)
          lookup-quote (partial get-largest-lte quotes-map)]
      (is (= (count stock-quotes) 334))
      (is (= (lookup-quote (LocalDate. 2011 4 29))
             (lookup-quote (LocalDate. 2011 4 30))))
      (is (= (:close (lookup-quote (LocalDate. 2011 3 18))) 16.03)))))

(deftest test-industries
  (testing "Industries and industry sectors"
    (let [sectors (get-industry-sectors)
          sector-names (set ["Basic Materials" "Conglomerates" "Consumer Goods"
                             "Financial" "Healthcare" "Industrial Goods"
                             "Services" "Technology" "Utilities"])]
      (is (= (set (map :name sectors)) sector-names)))
    (let [i112 (get-industry 112)]
      (is (= (:id i112) "112"))
      (is (= (:name i112) "Agricultural Chemicals"))
      (is (contains? (set (:companies i112))
                     {:name "Terra Nitrogen Company, L.P."
                      :symbol "TNH"})))
    (is (not (get-industry 12345)))
    (is (not (get-industry "abc")))))

(deftest test-currency-exchange-rates
  (testing "Currency exchange rates"
    (let [r (get-exchange-rate :usd :eur)]
      (is (< 0.5 (:rate r) 1.0)))))

(deftest test-stock-symbol-suggestions
  (testing "Stock symbol suggestions"
    (let [ss (get-symbol-suggestion "google")]
      (is (> (count ss) 8))
      (is (some (fn [s] (= (:exchDisp s) "NASDAQ")) ss)))))
