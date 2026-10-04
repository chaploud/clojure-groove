(ns chaploud.groove.live
  (:require [chaploud.groove.engine.mixer :as mixer]
            [chaploud.groove.engine.output :as output]
            [chaploud.groove.session :as session])
  (:import [java.io ByteArrayOutputStream]))

(defonce !state (atom {:session session/empty-session :compiled {}}))
(defonce !transport (atom nil))

(def lookahead-seconds 0.12)

(defn commit! [f]
  (:session (swap! !state (fn [{:keys [session]}]
                            (let [s (f session)]
                              {:session s :compiled (session/validate! s)})))))

(defn- nodes [session] (update-vals (:tracks session) :node))

(defn- advance [{:keys [session compiled] :as st} bar]
  (let [s (session/begin-bar session bar)]
    (if (= (nodes s) (nodes session))
      {:session s :compiled compiled}
      (try {:session s :compiled (session/validate! s)}
           (catch clojure.lang.ExceptionInfo e
             (println "groove: scene change rejected:" (ex-message e))
             (assoc st :session (session/begin-bar (dissoc session :arrangement) bar)))))))

(defn- events-of [{:keys [session compiled]} bar]
  (mapcat (fn [track]
            (try (doall (session/bar-events (update session :tracks select-keys [track]) compiled bar))
                 (catch Exception e
                   (println "groove:" track "skipped bar" bar "-" (ex-message e)))))
          (keys (:tracks session))))

(defn- schedule-bar! ^long [mixer st bar ^long frame]
  (let [session (:session st)
        bar-frames (* (session/bar-seconds session) (double (:sample-rate mixer)))]
    ((:set-tempo! mixer) (get-in session [:globals :tempo] 120))
    (doseq [e (events-of st bar)]
      (mixer/submit! mixer (+ frame (long (* (double (:t e)) bar-frames))) e (hash [(:track e) bar (:t e)])))
    (+ frame (long bar-frames))))

(defn- run-transport [mixer running]
  (let [position (:position mixer)
        sr (double (:sample-rate mixer))
        lookahead (long (* sr lookahead-seconds))]
    (loop [bar 0, frame (+ (long (position)) lookahead)]
      (when @running
        (let [st (swap! !state advance bar)
              next-frame (schedule-bar! mixer st bar frame)]
          (swap! !transport assoc :bar bar)
          (while (and @running (< (long (position)) (- next-frame lookahead)))
            (Thread/sleep 2))
          (recur (inc bar) next-frame))))))

(defn start! []
  (or @!transport
      (let [mixer (mixer/make-mixer output/sample-rate)
            stop-line (output/start-line! mixer)
            running (volatile! true)
            thread (doto (Thread. ^Runnable #(try (run-transport mixer running)
                                                  (catch Throwable t (println "groove: transport crashed" t)))
                                  "groove-transport")
                     (.setDaemon true)
                     (.start))]
        (reset! !transport {:mixer mixer :running running :thread thread :stop-line stop-line :bar -1}))))

(defn stop! []
  (when-let [{:keys [running ^Thread thread stop-line]} @!transport]
    (vreset! running false)
    (.join thread 1000)
    (stop-line)
    (reset! !transport nil)
    (swap! !state update :session update :tracks update-vals #(dissoc % :launch))
    nil))

(defn current-bar [] (some-> @!transport :bar))

(defn render [st bars]
  (let [mixer (mixer/make-mixer output/sample-rate)
        out (ByteArrayOutputStream.)
        tail-frames (* 2 output/sample-rate)]
    (loop [bar 0, frame 0, st (update st :session update :tracks update-vals #(dissoc % :launch))]
      (if (< bar bars)
        (let [st (advance st bar)
              next-frame (schedule-bar! mixer st bar frame)]
          (output/render-blocks! mixer (- next-frame (long ((:position mixer)))) out)
          (recur (inc bar) next-frame st))
        (output/render-blocks! mixer tail-frames out)))
    (.toByteArray out)))
