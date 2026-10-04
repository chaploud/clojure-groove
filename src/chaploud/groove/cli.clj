(ns chaploud.groove.cli
  (:require [chaploud.groove :as g]
            [chaploud.groove.io :as io]
            [chaploud.groove.render :as render]
            [chaploud.groove.session :as session]))

(def usage
  "Usage: groove <command> [args]

  play <song> [--bars N] [--device NAME]   play a song file or bundled song; Ctrl+C stops
  render <song> [bars] [out.wav]           render offline to a WAV file
  songs                                    list bundled songs
  devices                                  list audio outputs

A song is a path to an .edn file or the name of a bundled song, e.g. trance.")

(defn- options [args]
  (loop [[a b & more :as args] args, opts {}, positional []]
    (cond
      (empty? args) [opts positional]
      (= a "--bars") (recur more (assoc opts :bars (parse-long b)) positional)
      (= a "--device") (recur more (assoc opts :device b) positional)
      :else (recur (rest args) opts (conj positional a)))))

(defn play [song {:keys [bars device]}]
  (g/load! song)
  (let [s (g/session)
        bars (or bars (when (seq (:arrangement s)) (render/song-bars s)))]
    (g/start! {:device device})
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable g/stop!))
    (println (str "Playing " song " at " (session/tempo s) " BPM"
                  (if bars (str " for " bars " bars") ", Ctrl+C to stop")))
    (if bars
      (Thread/sleep (long (* 1000 (+ 1.0 (* bars (session/bar-seconds s))))))
      @(promise))
    (g/stop!)))

(defn- songs []
  (doseq [{:keys [name genre tempo about]} (io/bundled-songs)]
    (println (format "  %-10s %-14s %3d BPM  %s" name genre tempo about))))

(defn run [[command & args]]
  (let [[opts [song & more]] (options args)]
    (case command
      "play" (if song (play song opts) (println usage))
      "render" (if song
                 (println (render/render-file song (some-> (first more) parse-long)
                                              (or (second more) (render/default-out song))))
                 (println usage))
      "songs" (songs)
      "devices" (run! println (g/devices))
      (println usage))))

(defn -main [& args]
  (try
    (run args)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println "groove:" (ex-message e)))
      (System/exit 1)))
  (shutdown-agents)
  (System/exit 0))
