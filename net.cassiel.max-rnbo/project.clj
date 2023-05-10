(defproject net.cassiel.max-rnbo "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.11.4"]
                 [org.clojure/core.async "1.6.673"]
                 [binaryage/oops "0.7.2"]
                 [com.stuartsierra/component "1.1.0"]
                 [net.cassiel/lifecycle "0.1.0-SNAPSHOT"]]

  :source-paths ["src"]

  :plugins [[lein-environ "1.0.0"]
            [com.github.liquidz/antq "RELEASE"]
            [lein-cljsbuild "1.1.8"]]

  :aliases {"fig:build" ["trampoline" "run" "-m" "figwheel.main" "-b" "dev" "-r"]
            "fig:min"   ["run" "-m" "figwheel.main" "-O" "advanced" "-bo" "dev"]
            "fig:test"  ["run" "-m" "figwheel.main" "-co" "test.cljs.edn" "-m" "net.test-runner"]}

  :profiles {:dev {:dependencies [[com.bhauman/figwheel-main "0.2.17"]
                                  [org.slf4j/slf4j-nop "1.7.30"]
                                  [com.bhauman/rebel-readline-cljs "0.1.4"]]

                   :resource-paths ["target"]
                   ;; need to add the compiled assets to the :clean-targets
                   :clean-targets ^{:protect false} ["target"]}})
