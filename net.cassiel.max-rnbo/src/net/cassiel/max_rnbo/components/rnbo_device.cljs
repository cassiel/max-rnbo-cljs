(ns net.cassiel.max-rnbo.components.rnbo-device
  (:require [com.stuartsierra.component :as component]
            [net.cassiel.lifecycle :refer [starting stopping]]
            [oops.core :refer [oset! oget]]
            [clojure.core.async :as a :refer [>! <! go go-loop]]
            [cljs.core.async.interop :refer-macros [<p!]]))

;; Actual side-effect here is a binding of a field RNBO into js/window.
;; A second side-effect seems to be the non-idempotent addition of the <script>.
;; TODO remove script and shut down audio processor on (stop).

(defn load-RNBO-script [version]
  (js/Promise.
   (fn [resolve, reject]
     (if (re-matches #"\d+\.\d+\.\d+-dev" version)
       (reject (js/Error. "Patcher exported with a Debug Version!\nPlease specify the correct RNBO version to use in the code."))
       (let [el (.createElement js/document "script")]
         (doto el
           (oset! :src (str "https://c74-public.nyc3.digitaloceanspaces.com/rnbo/"
                            (js/encodeURIComponent version)
                            "/rnbo.min.js"))
           (oset! :onload resolve)
           (oset! :onerror (fn [err]
                             (js/console.log err)
                             (reject (js/Error. (str "Failed to load rnbo.js v" version))))))

         (js/console.log "BODY" (.-body js/document))
         (.append (.-body js/document) el))))))

;; From a server response ("user_data") asynchronously fetch all audio assets. Return a
;; channel which delivers the audio data buffers as a single sequence.

(defn fetch-audio-buffers-ch [context]
  (let [ch (a/chan)]
    (-> js/$
        (.post "https://time.apdev.uk/user_data"
               #js {:authToken "ktztMDY4lUrdzuqf"}
               (fn [response]
                 (let [urls  (as-> response X
                               (js->clj X :keywordize-keys true)
                               (:data X)
                               (remove (fn [x] (-> x :badAudio)) X)
                               (map :audio X)
                               (remove nil? X)
                               (filter (partial re-matches #"(?i).+\.wav") X)
                               ;; Make sure we have exactly 10, by repeating (assuming we have at least one) -
                               ;; but also randomize so we don't get the same first 10
                               (shuffle X)
                               (take 10 (cycle X))
                               (map (partial str "https://time.apdev.uk/uploads/") X))
                       chans (map (fn [url] (go
                                              (as-> (js/fetch url) X
                                                (<p! X)
                                                (<p! (.arrayBuffer X))
                                                (<p! (.decodeAudioData context X)))))
                                  urls)]
                   (go (>! ch (a/merge chans)))))))
    ch))

(defprotocol START-AUDIO
  (start-audio [this] "Start up audio"))

(defrecord RNBO-DEVICE [_context installed?]
  Object
  (toString [this] (str "RNBO-DEVICE " (seq this)))

  component/Lifecycle
  (start [this]
    (starting this
              :on installed?
              ;; Slight race condition here: we create the context immediately so that it can go
              ;; into the button, but the fetch and wiring of the RNBO device is async.
              ;; TODO: hide the button until this completes?
              :action #(let [WAContext (or (.-AudioContext js/window)
                                           (.-webkitAudioContext js/window))
                             context   (WAContext.)]
                         (go
                           (let [response      (<p! (js/fetch "export/rnbo-main.export.json"))
                                 patcher       (<p! (.json response))
                                 version       (-> patcher .-desc.meta.rnboversion)
                                 _             (<p! (load-RNBO-script version))
                                 output-node   (.createGain context)
                                 ;;deps        (<p! (js/fetch "export/dependencies.json"))
                                 _             (js/console.log "window.RNBO" (.-RNBO js/window))
                                 device        (<p! (.createDevice (.-RNBO js/window)
                                                                   #js {:context context :patcher patcher}))
                                 merged-chan (<! (fetch-audio-buffers-ch context))
                                 _           (go-loop [dbuf (<! merged-chan)
                                                       idx 0]
                                               (when dbuf
                                                 (js/console.log "BUF" dbuf)
                                                 (.setDataBuffer device (str "MAIN_" idx) dbuf)
                                                 (recur (<! merged-chan) (inc idx))))]
                             (.connect output-node (.-destination context))
                             (.connect (.-node device) output-node)

                             ;; Debugging:
                             (-> (oget device :messageEvent)
                                 (.subscribe (fn [ev] (js/console.log (.-tag ev)))))

                             (doseq [x (oget device :dataBufferDescriptions)]
                               (js/console.log "BUFFER" x))
                             ))
                         (assoc this
                                :_context context
                                :installed? true))))

  (stop [this]
    (stopping this
              :on installed?
              :action #(do
                         (.close _context)
                         (js-delete js/window "RNBO")
                         (assoc this
                                :_context nil
                                :installed? false))))

  START-AUDIO
  (start-audio [this]
    (js/console.log "START-AUDIO")
    (when _context
      (.resume _context))))
