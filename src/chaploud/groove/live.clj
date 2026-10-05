(ns ^:no-doc chaploud.groove.live
  (:require [chaploud.groove.engine.mixer :as mixer]
            [chaploud.groove.engine.output :as output]
            [chaploud.groove.instruments :as instruments]
            [chaploud.groove.session :as session])
  (:import [java.io ByteArrayOutputStream]))

(defn state-of [session]
  {:session session :compiled (session/validate! session)})

(defonce !state (atom {:session session/empty-session :compiled {}}))
(defonce !transport (atom nil))
(defonce !errors (atom []))

(def lookahead-seconds 0.12)

(defn commit! [f]
  (:session (swap! !state #(state-of (f (:session %))))))

(defn- advance [{:keys [session] :as st} bar on-error]
  (try
    (let [s (session/begin-bar session bar)]
      (if (= (session/track-nodes s) (session/track-nodes session))
        (assoc st :session s)
        {:session s :compiled (session/compile-tracks s)}))
    (catch Exception e
      (on-error (str "bar " bar ": arrangement stopped, " (ex-message e)) e)
      (assoc st :session (session/begin-bar (dissoc session :arrangement) bar)))))

(defn- events-of [{:keys [session compiled]} bar on-error]
  (mapcat (fn [track]
            (try (doall (session/bar-events (update session :tracks select-keys [track]) compiled bar))
                 (catch Exception e
                   (on-error (str "track " track " skipped: " (ex-message e)) e))))
          (keys (:tracks session))))

(defn- schedule-bar! [mixer st bar frame on-error]
  (let [frame (double frame)
        session (:session st)
        bar-frames (* (session/bar-seconds session) (double (:sample-rate mixer)))]
    ((:set-globals! mixer) (session/tempo session) (session/delay-feedback session) (session/duck-depth session))
    (when (pos? (long ((:take-non-finite-resets! mixer))))
      (on-error "the output went non-finite, so the delay and reverb were reset" nil))
    (doseq [e (events-of st bar on-error)]
      (try (mixer/submit! mixer (Math/round (+ frame (* (double (:t e)) bar-frames)))
                          e (hash [(:track e) bar (:t e)]))
           (catch Exception ex
             (on-error (str "track " (:track e) " skipped: " (ex-message ex)) ex))))
    (+ frame bar-frames)))

(defn- run-transport [mixer running {:keys [alive? take-dropouts!]} on-error]
  (let [position (:position mixer)
        lookahead (* (double (:sample-rate mixer)) lookahead-seconds)]
    (loop [bar 0, frame (+ (double (position)) lookahead)]
      (when (and @running (alive?))
        (swap! !transport #(if (identical? running (:running %)) (assoc % :bar bar) %))
        (let [st (swap! !state advance bar on-error)
              next-frame (double (schedule-bar! mixer st bar frame on-error))]
          (when (pos? (long (take-dropouts!)))
            (on-error "audio dropouts: the output buffer ran dry, so the CPU was too busy; try (start! {:buffer-ms 300})" nil))
          (while (and @running (alive?) (< (double (position)) (- next-frame lookahead)))
            (Thread/sleep 2))
          (recur (inc bar) next-frame))))))

(defn- reporter [out]
  (fn [msg _]
    (let [seen? (some #(= msg (:message %)) @!errors)]
      (swap! !errors (fn [errors]
                       (if seen?
                         (mapv #(cond-> % (= msg (:message %)) (update :count inc)) errors)
                         (conj errors {:message msg :count 1}))))
      (when-not seen?
        (binding [*out* out] (println "groove:" msg))))))

(defn stop! []
  (when-let [{:keys [running ^Thread thread audio]} @!transport]
    (vreset! running false)
    (when-not (= thread (Thread/currentThread)) (.join thread 1000))
    ((:stop audio))
    (reset! !transport nil)
    (swap! !state update :session session/rewind)
    nil))

(defn- warm-up! []
  (let [mixer (mixer/make-mixer output/sample-rate)]
    (doseq [[i [_ preset]] (map-indexed vector instruments/builtin)]
      (mixer/submit! mixer (* i 2000) (merge {:bus :synth} preset {:vel 0.8 :dur-s 0.2 :midi 48}) i))
    (dotimes [_ 400] ((:render! mixer)))))

(defn start! [opts]
  (or @!transport
      (let [_ (warm-up!)
            mixer (mixer/make-mixer output/sample-rate)
            on-error (reporter *out*)
            audio (output/start-line! mixer opts on-error)
            running (volatile! true)
            thread (Thread. ^Runnable
                    (fn []
                      (try (run-transport mixer running audio on-error)
                           (catch Throwable t (on-error (str "transport stopped: " t) t)))
                      (when @running (stop!)))
                            "groove-transport")]
        (reset! !errors [])
        (reset! !transport {:mixer mixer :running running :thread thread :audio audio :bar -1})
        (.setDaemon thread true)
        (.start thread)
        @!transport)))

(defn current-bar [] (:bar @!transport))

(defn status []
  {:playing? (some? @!transport)
   :bar (current-bar)
   :errors @!errors})

(defn render [session bars]
  (let [mixer (mixer/make-mixer output/sample-rate)
        out (ByteArrayOutputStream.)
        block (long mixer/block)
        fail (fn [msg e] (throw (ex-info msg {} e)))]
    (loop [bar 0, frame 0.0, st (state-of (session/rewind session))]
      (if (< bar bars)
        (let [st (advance st bar fail)
              next-frame (double (schedule-bar! mixer st bar frame fail))
              ahead (- (long next-frame) (long ((:position mixer))))]
          (output/render-blocks! mixer (* block (quot ahead block)) out)
          (recur (inc bar) next-frame st))
        (output/render-blocks! mixer (* 2 output/sample-rate) out)))
    (.toByteArray out)))
