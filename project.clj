(defproject clojure-conj-talk "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-cljsbuild "0.3.0"]]
  :dependencies [[org.clojure/clojure "1.5.1"]
		 [core.async "0.1.0-SNAPSHOT"]
                 [http-kit "2.1.10"]
                 [cheshire "5.2.0"]
                 [org.clojure/clojurescript "0.0-1913"]
                 [com.cemerick/austin "0.1.1"]]
  :profiles {:dev {:repl-options {:init-ns user}
                   :plugins [[com.cemerick/austin "0.1.0"]
                             [lein-cljsbuild "0.3.2"]]
                   :cljsbuild {:builds [{:source-paths ["src-cljs"]
                                         :compiler {:output-to "app.js"
                                                    :optimizations :simple
                                                    :pretty-print true}}]}}})
