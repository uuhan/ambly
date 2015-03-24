(ns ambly.repl.jsc
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [cljs.analyzer :as ana]
            [cljs.util :as util]
            [cljs.compiler :as comp]
            [cljs.repl :as repl]
            [cljs.closure :as closure]
            [clojure.data.json :as json])
  (:import java.net.Socket
           java.lang.StringBuilder
           [java.io File BufferedReader BufferedWriter IOException]
           (javax.jmdns JmDNS ServiceListener)))

(defn sh
  [& args]
  {:pre [(every? string? args)]}
  (.waitFor (.exec (Runtime/getRuntime) (string/join " " args))))

(defn repl-print [opts msg]
  {:pre [(map? opts)]}
  ((or (:print-no-newline opts) print) msg))

(defn repl-println [opts msg]
  {:pre [(map? opts)]}
  ((or (:print opts) println) msg))

(defn repl-flush [opts]
  {:pre [(map? opts)]}
  (or (:flush opts) flush))

(defn set-logging-level [logger-name level]
  {:pre [(string? logger-name) (instance? java.util.logging.Level level)]}
  (.setLevel (java.util.logging.Logger/getLogger logger-name) level))

(def ambly-bonjour-name-prefix "Ambly ")

(defn is-ambly-bonjour-name? [bonjour-name]
  {:pre [(string? bonjour-name)]}
  (.startsWith bonjour-name ambly-bonjour-name-prefix))

(defn bonjour-name->display-name
  [bonjour-name]
  {:pre [(is-ambly-bonjour-name? bonjour-name)]
   :post [(string? %)]}
  (subs bonjour-name (count ambly-bonjour-name-prefix)))

(defn name-endpoint-map->choice-list [name-endpoint-map]
  {:pre [(map? name-endpoint-map)]}
  (map vector (iterate inc 1) name-endpoint-map))

(defn print-discovered-devices [name-endpoint-map opts]
  {:pre [(map? name-endpoint-map) (map? opts)]}
  (if (empty? name-endpoint-map)
    (repl-println opts "(No devices)")
    (doseq [[choice-number [bonjour-name _]] (name-endpoint-map->choice-list name-endpoint-map)]
      (repl-println opts (str "[" choice-number "] " (bonjour-name->display-name bonjour-name))))))

(defn discover-and-choose-device
  "Looks for Ambly WebDAV devices advertised via Bonjour and presents
  a simple command-line UI letting user pick one, unless
  choose-first-discovered? is set to true in which case the UI is bypassed"
  [choose-first-discovered? opts]
  {:pre [(map? opts)]}
  (let [reg-type "_http._tcp.local."
        name-endpoint-map (atom (sorted-map))
        mdns-service (JmDNS/create)
        service-listener
        (reify ServiceListener
          (serviceAdded [_ service-event]
            (let [type (.getType service-event)
                  name (.getName service-event)]
              (when (and (= reg-type type) (is-ambly-bonjour-name? name))
                (.requestServiceInfo mdns-service type name 1))))
          (serviceRemoved [_ service-event]
            (swap! name-endpoint-map dissoc (.getName service-event)))
          (serviceResolved [_ service-event]
            (let [type (.getType service-event)
                  name (.getName service-event)]
              (when (and (= reg-type type) (is-ambly-bonjour-name? name))
                (let [entry {name (let [info (.getInfo service-event)]
                                    {:address (.getAddress info)
                                     :port    (.getPort info)})}]
                  (swap! name-endpoint-map merge entry))))))]
    (try
      (.addServiceListener mdns-service reg-type service-listener)
      (loop [count 0]
        (when (empty? @name-endpoint-map)
          (Thread/sleep 100)
          (when (= 20 count)
            (repl-println opts "\nSearching for devices ..."))
          (recur (inc count))))
      (Thread/sleep 500)                                    ;; Sleep a little more to catch stragglers
      (loop [current-name-endpoint-map @name-endpoint-map]
        (repl-println opts "")
        (print-discovered-devices current-name-endpoint-map opts)
        (when-not choose-first-discovered?
          (repl-println opts "\n[R] Refresh\n")
          (repl-print opts "Choice: ")
          (repl-flush opts))
        (let [choice (if choose-first-discovered? "1" (read-line))]
          (if (= "r" (.toLowerCase choice))
            (recur @name-endpoint-map)
            (let [choices (name-endpoint-map->choice-list current-name-endpoint-map)
                  choice-ndx (try (dec (Long/parseLong choice)) (catch NumberFormatException _ -1))]
              (if (< -1 choice-ndx (count choices))
                (second (nth choices choice-ndx))
                (recur current-name-endpoint-map))))))
      (finally
        (.start
          (Thread.
            (fn []
              (.removeServiceListener mdns-service reg-type service-listener)
              (.close mdns-service))))))))

(defn socket
  [host port]
  {:pre [(string? host) (number? port)]}
  (let [socket (doto (Socket. host port) (.setKeepAlive true))
        in     (io/reader socket)
        out    (io/writer socket)]
    {:socket socket :in in :out out}))

(defn close-socket
  [s]
  {:pre [(map? s)]}
  (.close (:socket s)))

(defn write
  [out js]
  (:pre [(instance? BufferedWriter out) (string? js)])
  (.write out js)
  (.write out (int 0)) ;; terminator
  (.flush out))

(defn read-messages
  [in response-promise opts]
  {:pre [(instance? BufferedReader in) (map? opts)]}
  (loop [sb (StringBuilder.) c (.read in)]
    (cond
      (= c -1) (do
                 (if-let [resp-promise @response-promise]
                   (deliver resp-promise :eof))
                 :eof)
      (= c 1) (do
                (repl-print opts (str sb))
                (repl-flush opts)
                (recur (StringBuilder.) (.read in)))
      (= c 0) (do
                (deliver @response-promise (str sb))
                (recur (StringBuilder.) (.read in)))
      :else (do
              (.append sb (char c))
              (recur sb (.read in))))))

(defn start-reading-messages
  "Starts a thread reading inbound messages."
  [repl-env opts]
  {:pre [(map? repl-env) (map? opts)]}
  (.start
    (Thread.
      #(try
        (let [rv (read-messages (:in @(:socket repl-env)) (:response-promise repl-env) opts)]
          (when (= :eof rv)
            (close-socket @(:socket repl-env))))
        (catch IOException e
          (when-not (.isClosed (:socket @(:socket repl-env)))
            (.printStackTrace e)))))))

(defn stack-line->canonical-frame
  "Parses a stack line into a frame representation, returning nil
  if parse failed."
  [stack-line opts]
  {:pre [(string? stack-line) (map? opts)]}
  (let [[function file line column]
        (rest (re-matches #"(.*)@file:///(.*):([0-9]+):([0-9]+)"
                stack-line))]
    (if (and file function line column)
      {:file     (str (io/file (util/output-directory opts) file))
       :function function
       :line     (Long/parseLong line)
       :column   (Long/parseLong column)}
      (when-not (string/blank? stack-line)
        {:file nil
         :function (string/trim stack-line)
         :line nil
         :column nil}))))

(defn raw-stacktrace->canonical-stacktrace
  "Parse a raw JSC stack representation, parsing it into stack frames.
  The canonical stacktrace must be a vector of maps of the form
  {:file <string> :function <string> :line <integer> :column <integer>}."
  [raw-stacktrace opts]
  {:pre  [string? (map? opts)]
   :post [(vector? %)]}
  (->> raw-stacktrace
    string/split-lines
    (map #(stack-line->canonical-frame % opts))
    (remove nil?)
    vec))

(defn jsc-eval
  "Evaluate a JavaScript string in the JSC REPL process."
  [repl-env js]
  {:pre [(map? repl-env) (string? js)]}
  (let [{:keys [out]} @(:socket repl-env)
        response-promise (promise)]
    (reset! (:response-promise repl-env) response-promise)
    (write out js)
    (let [response @response-promise]
      (if (= :eof response)
        {:status :error
         :value  "Connection to JavaScriptCore closed."}
        (let [result (json/read-str response
                       :key-fn keyword)]
          (merge
            {:status (keyword (:status result))
             :value  (:value result)}
            (when-let [raw-stacktrace (:stacktrace result)]
              {:stacktrace raw-stacktrace})))))))

(defn load-javascript
  "Load a Closure JavaScript file into the JSC REPL process."
  [repl-env provides url]
  (jsc-eval repl-env
    (str "goog.require('" (comp/munge (first provides)) "')")))

(defn form-ambly-import-script-expr-js
  "Takes a JavaScript path expression and forms an `AMBLY_IMPORT_SCRIPT` command."
  [path-expr]
  {:pre [(string? path-expr)]}
  (str "AMBLY_IMPORT_SCRIPT(" path-expr ");"))

(defn form-ambly-import-script-path-js
  "Takes a path and forms a JavaScript `AMBLY_IMPORT_SCRIPT` command."
  [path]
  {:pre [(or (string? path) (instance? File path))]}
  (form-ambly-import-script-expr-js (str "'" path "'")))

(defn tear-down
  [repl-env]
  (when-let [webdav-mount-point @(:webdav-mount-point repl-env)]
    (sh "umount" webdav-mount-point))
  (when-let [socket @(:socket repl-env)]
    (close-socket socket)))

(defn- mount-webdav
  [repl-env bonjour-name endpoint-address endpoint-port]
  {:pre [(map? repl-env) (is-ambly-bonjour-name? bonjour-name)
         (string? endpoint-address) (number? endpoint-port)]}
  (let [webdav-mount-point (str "/Volumes/Ambly-" (format "%08X" (hash bonjour-name)))
        output-dir (io/file webdav-mount-point)
        webdav-endpoint (str "http://" endpoint-address ":" endpoint-port)]
    (when (.exists output-dir)
      (sh "umount" webdav-mount-point)
      (Thread/sleep 250))
    (.mkdirs output-dir)
    (if (zero? (sh "mount_webdav" webdav-endpoint webdav-mount-point))
      (reset! (:webdav-mount-point repl-env) webdav-mount-point)
      (throw (Exception. (str "Unable to mount WebDAV at " webdav-endpoint))))
    webdav-mount-point))

(defn- set-up-socket
  [repl-env opts address port]
  {:pre [(map? repl-env) (map? opts) (string? address) (number? port)]}
  (when-let [socket @(:socket repl-env)]
    (close-socket socket))
  (reset! (:socket repl-env)
    (socket address port))
  ;; Start dedicated thread to read messages from socket
  (start-reading-messages repl-env opts))

(defn setup
  [repl-env opts]
  {:pre [(map? repl-env) (map? opts)]}
  (try
    (let [_ (set-logging-level "javax.jmdns" java.util.logging.Level/OFF)
          [bonjour-name endpoint] (discover-and-choose-device (:choose-first-discovered (:options repl-env)) opts)
          endpoint-address (.getHostAddress (:address endpoint))
          endpoint-port (:port endpoint)
          _ (reset! (:bonjour-name repl-env) bonjour-name)
          webdav-mount-point (mount-webdav repl-env bonjour-name endpoint-address endpoint-port)
          output-dir (io/file webdav-mount-point)
          env (ana/empty-env)
          core (io/resource "cljs/core.cljs")]
      (repl-println opts (str "\nConnecting to" (bonjour-name->display-name bonjour-name) "...\n"))
      (set-up-socket repl-env opts endpoint-address (dec endpoint-port))
      (if (= "true" (:value (jsc-eval repl-env "typeof cljs === 'undefined'")))
        (do
          ;; compile cljs.core & its dependencies, goog/base.js must be available
          ;; for bootstrap to load, use new closure/compile as it can handle
          ;; resources in JARs
          (let [core-js (closure/compile core
                          (assoc opts
                            :output-dir webdav-mount-point
                            :output-file
                            (closure/src-file->target-file core)))
                deps (closure/add-dependencies opts core-js)]
            ;; output unoptimized code and the deps file
            ;; for all compiled namespaces
            (apply closure/output-unoptimized
              (assoc opts
                :output-dir webdav-mount-point
                :output-to (.getPath (io/file output-dir "ambly_repl_deps.js")))
              deps))
          ;; Set up CLOSURE_IMPORT_SCRIPT function, injecting path
          (jsc-eval repl-env
            (str "CLOSURE_IMPORT_SCRIPT = function(src) {"
              (form-ambly-import-script-expr-js
                (str "'goog" File/separator "' + src"))
              "return true; };"))
          ;; bootstrap
          (jsc-eval repl-env
            (form-ambly-import-script-path-js (io/file "goog" "base.js")))
          ;; load the deps file so we can goog.require cljs.core etc.
          (jsc-eval repl-env
            (form-ambly-import-script-path-js (io/file "ambly_repl_deps.js")))
          ;; monkey-patch isProvided_ to avoid useless warnings - David
          (jsc-eval repl-env
            (str "goog.isProvided_ = function(x) { return false; };"))
          ;; monkey-patch goog.require, skip all the loaded checks
          (repl/evaluate-form repl-env env "<cljs repl>"
            '(set! (.-require js/goog)
               (fn [name]
                 (js/CLOSURE_IMPORT_SCRIPT
                   (aget (.. js/goog -dependencies_ -nameToPath) name)))))
          ;; load cljs.core, setup printing
          (repl/evaluate-form repl-env env "<cljs repl>"
            '(do
               (.require js/goog "cljs.core")
               (set-print-fn! js/AMBLY_PRINT_FN)))
          ;; redef goog.require to track loaded libs
          (repl/evaluate-form repl-env env "<cljs repl>"
            '(do
               (set! *loaded-libs* #{"cljs.core"})
               (set! (.-require js/goog)
                 (fn [name reload]
                   (when (or (not (contains? *loaded-libs* name)) reload)
                     (set! *loaded-libs* (conj (or *loaded-libs* #{}) name))
                     (js/CLOSURE_IMPORT_SCRIPT
                       (aget (.. js/goog -dependencies_ -nameToPath) name))))))))
        (let [expected-clojurescript-version (cljs.util/clojurescript-version)
              actual-clojurescript-version (:value (jsc-eval repl-env "cljs.core._STAR_clojurescript_version_STAR_"))]
          (when-not (= expected-clojurescript-version actual-clojurescript-version)
            (repl-println opts
              (str "WARNING: " (bonjour-name->display-name bonjour-name)
                "\n         is running ClojureScript " actual-clojurescript-version
                ", while the Ambly REPL is\n         set up to use ClojureScript "
                expected-clojurescript-version ".\n")))))
      {:merge-opts {:output-dir webdav-mount-point}})
    (catch Throwable t
      (tear-down repl-env)
      (throw t))))

(defrecord JscEnv [response-promise bonjour-name webdav-mount-point socket options]
  repl/IReplEnvOptions
  (-repl-options [this]
    {:require-foreign true})
  repl/IParseStacktrace
  (-parse-stacktrace [_ stacktrace _ build-options]
    (raw-stacktrace->canonical-stacktrace stacktrace build-options))
  repl/IPrintStacktrace
  (-print-stacktrace [_ stacktrace _ build-options]
    (let [source (fn [url file]
                   (if file
                     (let [file-path (str file)]
                       (if (.startsWith file-path @webdav-mount-point)
                         (subs file-path (inc (count @webdav-mount-point)))
                         file-path))
                     (str url)))]
      (doseq [{:keys [function file url line column]}
              (repl/mapped-stacktrace stacktrace build-options)]
        (let [url (when url (string/trim (.toString url)))
              file (when file (string/trim (.toString file)))]
          (repl-println repl/*repl-opts*
            (str "\t" (when function (str function " "))
              "(" (source url file) (when line (str ":" line)) (when column (str ":" column)) ")"))))))
  repl/IJavaScriptEnv
  (-setup [repl-env opts]
    (setup repl-env opts))
  (-evaluate [repl-env _ _ js]
    (jsc-eval repl-env js))
  (-load [repl-env provides url]
    (load-javascript repl-env provides url))
  (-tear-down [repl-env]
    (tear-down repl-env)))

(defn repl-env* [options]
  (JscEnv. (atom nil) (atom nil) (atom nil) (atom nil) options))

(defn repl-env
  [& {:as options}]
  (repl-env* options))

(comment

  (require
    '[cljs.repl :as repl]
    '[ambly.repl.jsc :as jsc])

  (repl/repl* (jsc/repl-env) {})

  )
