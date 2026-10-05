(ns ^:no-doc chaploud.groove.engine.output
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File]
           [javax.sound.sampled AudioFileFormat$Type AudioFormat AudioInputStream AudioSystem SourceDataLine]))

(set! *warn-on-reflection* true)

(def sample-rate 48000)

(defn- audio-format ^AudioFormat []
  (AudioFormat. (float sample-rate) 16 2 true false))

(defn- block->bytes ^bytes [^doubles l ^doubles r ^bytes buf]
  (let [n (alength l)]
    (dotimes [i n]
      (let [sl (short (Math/round (* 32767.0 (Math/max -1.0 (Math/min 1.0 (aget l i))))))
            sr (short (Math/round (* 32767.0 (Math/max -1.0 (Math/min 1.0 (aget r i))))))
            j (* 4 i)]
        (aset buf j (unchecked-byte sl))
        (aset buf (+ j 1) (unchecked-byte (bit-shift-right sl 8)))
        (aset buf (+ j 2) (unchecked-byte sr))
        (aset buf (+ j 3) (unchecked-byte (bit-shift-right sr 8)))))
    buf))

(defn- output-mixers []
  (filter #(.isLineSupported (AudioSystem/getMixer %)
                             (javax.sound.sampled.DataLine$Info. SourceDataLine (audio-format)))
          (AudioSystem/getMixerInfo)))

(defn devices []
  (mapv #(.getName ^javax.sound.sampled.Mixer$Info %) (output-mixers)))

(defn pick-device [names wanted]
  (let [lower #(.toLowerCase ^String %)
        w (lower wanted)]
    (or (first (filter #(= w (lower %)) names))
        (first (filter #(.contains ^String (lower %) w) names)))))

(def default-buffer-ms 170)

(defn- open-line ^SourceDataLine [device buffer-ms]
  (let [mixers (output-mixers)
        info (when device
               (let [name (or (pick-device (devices) device)
                              (throw (ex-info (str "No audio output matching " (pr-str device) "; available: " (devices))
                                              {:device device})))]
                 (first (filter #(= name (.getName ^javax.sound.sampled.Mixer$Info %)) mixers))))]
    (try
      (doto (if info
              (AudioSystem/getSourceDataLine (audio-format) info)
              (AudioSystem/getSourceDataLine (audio-format)))
        (.open (audio-format) (* 4 (long (* sample-rate (/ (double buffer-ms) 1000.0))))))
      (catch Exception e
        (throw (ex-info (str "No audio output is available (" (ex-message e) "). "
                             "Render to a file instead, e.g. (render! \"out/song.wav\" 8) or bb render <song>.")
                        {} e))))))

(defn start-line! [mixer {:keys [device buffer-ms] :or {buffer-ms default-buffer-ms}} on-error]
  (let [line (open-line device buffer-ms)
        render! (:render! mixer)
        ^doubles l (:out-l mixer)
        ^doubles r (:out-r mixer)
        buf (byte-array (* 4 (alength l)))
        running (volatile! true)
        dropouts (long-array 1)
        primed (volatile! false)
        t (Thread. ^Runnable
           (fn []
             (try
               (while @running
                 (render!)
                 (when (and @primed (>= (.available line) (.getBufferSize line)))
                   (aset dropouts 0 (inc (aget dropouts 0))))
                 (.write line (block->bytes l r buf) 0 (alength buf))
                 (when-not @primed
                   (vreset! primed (< (.available line) (alength buf)))))
               (catch Throwable e
                 (on-error (str "audio stopped: " e) e))
               (finally
                 (.close line))))
                   "groove-audio")]
    (.start line)
    (.setPriority t Thread/MAX_PRIORITY)
    (.setDaemon t true)
    (.start t)
    {:alive? #(.isAlive t)
     :take-dropouts! (fn [] (let [n (aget dropouts 0)] (aset dropouts 0 0) n))
     :stop (fn [] (vreset! running false) (.join t 1000))}))

(defn render-blocks! [mixer ^long frames ^ByteArrayOutputStream out]
  (let [render! (:render! mixer)
        ^doubles l (:out-l mixer)
        ^doubles r (:out-r mixer)
        buf (byte-array (* 4 (alength l)))]
    (dotimes [_ (quot (+ frames (dec (alength l))) (alength l))]
      (render!)
      (.write out (block->bytes l r buf) 0 (alength buf)))))

(defn write-wav! [^bytes pcm path]
  (let [f (File. ^String path)]
    (some-> (.getParentFile f) .mkdirs)
    (AudioSystem/write (AudioInputStream. (ByteArrayInputStream. pcm) (audio-format) (quot (alength pcm) 4))
                       AudioFileFormat$Type/WAVE f)
    path))
