(ns net.cassiel.max-rnbo.components.rnbo-device
  (:require [com.stuartsierra.component :as component]
            [net.cassiel.lifecycle :refer [starting stopping]]
            [oops.core :refer [oset! oget ocall]]
            [clojure.core.async :as a :refer [>! <! go go-loop]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clojure.core.match :refer [match]]))

;; This component is a loose rewrite of https://github.com/Cycling74/rnbo.example.webpage/blob/main/js/app.js

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

;; From a server response asynchronously fetch all audio assets. Return a
;; channel which delivers the HTML audio data buffers as a single sequence.
;; Based on an earlier effort which fetches from Amazon EC2, hence some of
;; the filters and tests.
;; DEPRECATED.

(defn ^:deprecated fetch-audio-assets-ch [context]
  (let [ch (a/chan)]
    (-> js/$
        (.get "/data.json"
              (fn [response]
                 (let [urls  (as-> response X
                               (js->clj X :keywordize-keys true)
                               (:data X)
                               (remove (fn [x] (-> x :badAudio)) X)
                               (map :audio X)
                               (remove nil? X)
                               (filter (partial re-matches #"(?i).+\.wav") X)
                               ;; Make sure we have exactly 10, by cycling (assuming we have at least one) -
                               ;; but also randomize so we don't get the same first 10.
                               (shuffle X)
                               (take 10 (cycle X))
                               (map (partial str "/export/media/") X))
                       chans (map (fn [url] (go
                                              (as-> (js/fetch url) X
                                                (<p! X)
                                                (<p! (.arrayBuffer X))
                                                (<p! (.decodeAudioData context X)))))
                                  urls)]
                   ;; TODO: should we be closing those channels as they deliver their results?
                   ;; If not, our go-loop block in the main body of the code will just park.
                   ;; TODO: is there a bogus level of channel indirection here?
                   (go (>! ch (a/merge chans)))))))
    ch))

(defn fetch-url
  "Fetch a buffer's audio via its URL (deferred)."
  [context device id url]
  (go (as-> (js/fetch url) X
        (<p! X)
        (<p! (ocall X :arrayBuffer))
        (<p! (ocall context :decodeAudioData X))
        (ocall device :setDataBuffer id X))))

(defn fetch-file
  "Fetch a file by treating it as a URL at known location.
   TODO: protect against odd characters/spaces in names."
  [context device id file]
  (fetch-url context device id (str "/export/media/" file)))

;; Load all buffers declared in the exported patcher.

;; Current understanding: for buffers with named (and accessible) files, or URLs,
;; the information will be in `dependencies.json`, with file paths amended
;; as required relative to patcher (rather, to the JSON file containing the compiled code).
;; These paths are only useful if "Copy Sample Dependencies" is enabled, in which
;; case they all degenerate to "media/XXX". (URLs are preserved.)

;; We can also look at device.dataBufferDescriptions which seems to pull information
;; from buffer instances and their file/URL arguments (if any). This gets us
;; buffers with no file or URL attached (or buffers outside the RNBO patcher itself,
;; if we encounter groove~, play~ etc.) but just gives us raw arguments. Also, @url
;; arguments come in as @file. Nevertheless, if we're copying file dependencies
;; we know the local file paths, so this seems more useful, although we have to
;; determine the URL instances the hard way. Maybe longer term it's more accurate
;; to glean information from both sources in parallel.

(defn load-buffers [context device]
  (go-loop [bufs (-> (oget device :dataBufferDescriptions)
                     (js->clj :keywordize-keys true)
                     (as-> X (sort-by :id X)))]
    ;; The fetches aren't sequenced here and run in parallel.
    (when-let [b (first bufs)]
      (js/console.log "<" b ">")

      (match [b]
             [{:file (u :guard #(re-matches #"https?:/.*" (str %)))}]
             ;; TODO that (str %) coercion is needed for some reason.
             (do (js/console.log "Buffer" (:id b) "with URL-like file" u)
                 (fetch-url context device (:id b) u))

             [{:file f}]
             (do (js/console.log "Buffer" (:id b) "with file" f)
                 (fetch-file context device (:id b) f))

             [{:url u}]
             (do (js/console.log "Buffer" (:id b) "with URL" u)
                 (fetch-url context device (:id b) u))

             :else
             ;; Buffers that have no file or URL information: plant
             ;; a fixed file (as a test):
             (do
               (js/console.log "Empty buffer for " (:id b))
               (fetch-file context device (:id b) "CHEERS.wav")))

      (recur (next bufs)))))

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
                           (let [response    (<p! (js/fetch "export/rnbo-main.export.json"))
                                 patcher     (<p! (.json response))
                                 version     (-> patcher .-desc.meta.rnboversion)
                                 _           (<p! (load-RNBO-script version))
                                 output-node (.createGain context)
                                 ;;deps        (<p! (js/fetch "export/dependencies.json"))
                                 _           (js/console.log "window.RNBO" (.-RNBO js/window))
                                 device      (<p! (.createDevice (.-RNBO js/window)
                                                                 #js {:context context :patcher patcher}))
                                 ;; merged-chan (<! (fetch-audio-assets-ch context))
                                 ;; We fetch audio files from a remote source (specified in JSON):
                                 ;; as they arrive asynchronously, we associate them with buffers
                                 ;; that need to be referenced in the RNBO patcher: MAIN_0, MAIN_1 etc.
                                 #_ _           #_ (go-loop [dbuf (<! merged-chan)
                                                       idx 0]
                                               (when dbuf
                                                 (js/console.log "BUF" dbuf)
                                                 (.setDataBuffer device (str "MAIN_" idx) dbuf)
                                                 (recur (<! merged-chan) (inc idx))))

                                 ]

                             ;; dependencies.json contains entries for buffer~ objects in the RNBO patcher
                             ;; itself which are associated with files or urls. The file paths seem to
                             ;; just be "media/xxx" which is where they get planted by the export (assuming
                             ;; we copy media files at that point). Given the
                             ;; information we can get from .dataBufferDescriptions it's not clear what we
                             ;; get that's different from reading the JSON. But let's at least print it.

                             (as-> (js/fetch "/export/dependencies.json") X
                               (<p! X)
                               (.json X)
                               (<p! X)
                               (js/console.log "dependencies.json:" X))

                             (load-buffers context device)

                             (.connect output-node (.-destination context))
                             (.connect (.-node device) output-node)

                             ;; Debugging:
                             (-> (oget device :messageEvent)
                                 (ocall :subscribe (fn [ev] (js/console.log "OBJ" (oget ev :?objectId)
                                                                            "TAG" (oget ev :?tag)
                                                                            "PAYLOAD" (oget ev :?payload)
                                                                            ))))))

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
