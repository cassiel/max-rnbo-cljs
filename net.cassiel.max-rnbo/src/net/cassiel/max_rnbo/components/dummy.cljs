(ns net.cassiel.max-rnbo.components.dummy
  (:require [com.stuartsierra.component :as component]
            [net.cassiel.lifecycle :refer [starting stopping]]))

(defrecord DUMMY [installed?]
  Object
  (toString [this] (str "DUMMY " (seq this)))

  component/Lifecycle
  (start [this]
    (starting this
              :on installed?
              :action #(assoc this
                              :installed? true)))

  (stop [this]
    (stopping this
              :on installed?
              :action #(assoc this
                              :installed? false))))
