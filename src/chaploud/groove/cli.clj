(ns ^:no-doc chaploud.groove.cli
  (:require [chaploud.groove :as g]
            [chaploud.groove.io :as io]
            [chaploud.groove.render :as render]
            [chaploud.groove.session :as session]))

(def usage
  "Usage: groove <command> [args]

  play <song> [--bars N] [--device NAME]   play a song file or bundled song; Ctrl+C stops
  render <song> [--bars N] [out.wav]       render offline to a WAV file
  songs                                    list bundled songs
  devices                                  list audio outputs

A song is a path to an .edn file or the name of a bundled song, e.g. trance.")

(defn- bars-arg [s]
  (or (some-> s parse-long)
      (throw (ex-info (str "bars must be a whole number, got " (pr-str s)) {}))))

(defn- options [args]
  (loop [[a b & more :as args] args, opts {}, positional []]
    (cond
      (empty? args) [opts positional]
      (= a "--bars") (recur more (assoc opts :bars (bars-arg b)) positional)
      (= a "--device") (if b
                         (recur more (assoc opts :device b) positional)
                         (throw (ex-info "--device needs an output name; see groove devices" {})))
      :else (recur (rest args) opts (conj positional a)))))

(defn play [song {:keys [bars device]}]
  (g/load! song)
  (let [s (g/session)
        bars (or bars (when (seq (:arrangement s)) (session/arrangement-bars s)))]
    (g/start! {:device device})
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable g/stop!))
    (println (str "Playing " song " at " (session/tempo s) " BPM"
                  (if bars (str " for " bars " bars") ", Ctrl+C to stop")))
    (let [deadline (when bars (+ (System/currentTimeMillis) (long (* 1000 (+ 2.0 (* bars (session/bar-seconds s)))))))]
      (while (and (:playing? (g/status))
                  (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
        (Thread/sleep 200)))
    (let [{:keys [playing? errors]} (g/status)]
      (g/stop!)
      (when-not playing?
        (throw (ex-info (str "playback stopped: " (or (:message (last errors)) "the audio output closed")) {}))))))

(defn- songs []
  (doseq [{:keys [name genre about]} (io/bundled-songs)]
    (println (format "  %-10s %-14s %3d BPM  %s" name genre
                     (session/tempo (io/song->session (io/read-song name))) about))))

(defn- usage-error []
  (throw (ex-info usage {:exit 2})))

(defn run [[command & args]]
  (let [[opts [song & more]] (options args)]
    (case command
      "play" (if song (play song opts) (usage-error))
      "render" (if song
                 (let [[bars out] (if (or (:bars opts) (not (some-> (first more) parse-long)))
                                    [(:bars opts) (first more)]
                                    [(bars-arg (first more)) (second more)])]
                   (println (render/render-file song bars (or out (render/default-out song)))))
                 (usage-error))
      "songs" (songs)
      "devices" (run! println (g/devices))
      (nil "help" "--help" "-h") (println usage)
      (usage-error))))

(defn -main [& args]
  (let [code (try
               (run args)
               0
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*] (println (if (:exit (ex-data e)) (ex-message e) (str "groove: " (ex-message e)))))
                 (:exit (ex-data e) 1))
               (catch Exception e
                 (binding [*out* *err*] (println "groove:" (str e)))
                 1))]
    (shutdown-agents)
    (System/exit code)))
