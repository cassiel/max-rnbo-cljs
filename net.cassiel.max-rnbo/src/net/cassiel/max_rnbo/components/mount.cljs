(ns net.cassiel.max-rnbo.components.mount
  (:require [com.stuartsierra.component :as component]
            [net.cassiel.lifecycle :refer [starting stopping]]
            [net.cassiel.max-rnbo.components.rnbo-device :as rnbo-device]
            [reagent.dom :as rdom]
            [oops.core :refer [oset!]]
            [clojure.core.async :as a :refer [>! <! go go-loop]]))

(defrecord MOUNT [rnbo-device installed?]
  Object
  (toString [this] (str "MOUNT " (seq this)))

  component/Lifecycle
  (start [this]
    (letfn [(page []
              (fn [] [:div#ROOT
                      [:div.AUDIO-BUTTON [:button {:type "button"} "Start Audio"]]]))]
      (starting this
                :on installed?
                :action #(do
                           (rdom/render [page] (.getElementById js/document "main"))
                           (let [button (first (js/$ "div.AUDIO-BUTTON"))]
                             (oset! button :onclick (fn [] (rnbo-device/start-audio rnbo-device)))
                             (assoc this
                                    :_button button
                                    :installed? true))))))

  (stop [this]
    (stopping this
              :on installed?
              :action #(do
                         #_ (.remove (js/$ "ROOT"))
                         (js-delete (:_button this) "onclick")
                         (assoc this
                                :_button nil
                                :installed? false)))))
