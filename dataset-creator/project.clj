(defproject dataset-creator "0.1.0-SNAPSHOT"
  :description "functions for data acquisition"
  :url "http://github.org/jaratec/github-sna"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 [cheshire "4.0.3"]
                 [clj-http "0.5.7"]
                 ]
  ;; load namespace
  :main dataset-creator.core)
