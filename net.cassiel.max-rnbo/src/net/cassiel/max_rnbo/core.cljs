(ns ^:figwheel-hooks net.cassiel.max-rnbo.core
  (:require [com.stuartsierra.component :as component]
            [net.cassiel.max-rnbo.components.dummy :as dummy]
            [net.cassiel.max-rnbo.components.rnbo-device :as rnbo-device]))

(enable-console-print!)

(defn system []
  (component/system-map :dummy (dummy/map->DUMMY {})
                        :rnbo-device (rnbo-device/map->RNBO-DEVICE {})))

(defonce S (atom (system)))

(defn ^:before-load teardown []
  (swap! S component/stop))

(defn ^:after-load startup []
  (swap! S component/start))
