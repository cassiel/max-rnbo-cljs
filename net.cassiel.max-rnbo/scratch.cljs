(ns user
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]])
  (:require [com.stuartsierra.component :as component]
            [cljs.core.async :as a :refer [>! <!]]
            [clojure.core.match :refer [match]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [oops.core :refer [oset! oget]]
            [goog.string :as gstring]
            [goog.string.format]))

(-> js/$
    (.get "/data.json"
          (fn [response]
             (let [urls (as-> response X
                          (js->clj X :keywordize-keys true)
                          (:data X)
                          (map :audio X)
                          (remove nil? X)
                          (filter (partial re-matches #"(?i).+\.wav") X)
                          (map (partial str "/export/media/") X))]
               (js/console.log urls)))))

(-> js/$
    (.get "/data.json"
          (fn [response]
             (let [payload (as-> response X
                             (js->clj X :keywordize-keys true)
                          )]
               (js/console.log payload)))))

(.-AudioContext js/window)
(.-webkitAudioContext js/window)

(oget js/window :?webkitAudioContext)

(sort-by :X [{:X "B"} {:X "A"} {:X "Z"}])

(match [{:id "BAR", :file "anton.aif", :type "Float32Buffer", :tag "buffer~"}]
       [{:file f}] f
       :else "ELSE")

(match ["AAA"]
       [(#"AAA" :as x)] x
       :else 0)

(match ["AAABBB"]
       [(x :guard (partial re-matches #"AAA.*"))] x
       :else 0)

(type "X")

(js/console.log (type #(identity "X")))
