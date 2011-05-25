(defproject com.fxtlabs/stockings "1.0.0-SNAPSHOT"
  :description "Get current and historical stock quotes."
  :url "https://github.com/fxtlabs/stockings"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [joda-time "1.6"]
                 [clojure-csv "1.2.4"]
                 [clj-http "0.1.3"]]
  :dev-dependencies 
  [[autodoc "0.7.1"
    :exclusions [org.clojure/clojure org.clojure/clojure-contrib]]]
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :autodoc 
  {:name "Stockings"
   :description "Get current and historical stock quotes."
   :copyright "Copyright 2011 Filippo Tampieri"
   :root "."
   :source-path "src"
   :web-src-dir "https://github.com/fxtlabs/stockings/blob/"
   :web-home "http://stockings.fxtlabs.com"
   :output-path "autodoc"
   :namespaces-to-document ["stockings"]
   :load-except-list [#"/test/" #"project.clj"]})

