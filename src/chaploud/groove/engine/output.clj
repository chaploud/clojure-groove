(ns chaploud.groove.engine.output
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

(defn start-line! [mixer]
  (let [^SourceDataLine line (AudioSystem/getSourceDataLine (audio-format))
        render! (:render! mixer)
        ^doubles l (:out-l mixer)
        ^doubles r (:out-r mixer)
        buf (byte-array (* 4 (alength l)))
        running (volatile! true)
        t (Thread. ^Runnable
           (fn []
             (try
               (while @running
                 (render!)
                 (.write line (block->bytes l r buf) 0 (alength buf)))
               (finally
                 (.drain line)
                 (.close line))))
                   "groove-audio")]
    (.open line (audio-format) (* 4 2048))
    (.start line)
    (.setPriority t Thread/MAX_PRIORITY)
    (.setDaemon t true)
    (.start t)
    (fn [] (vreset! running false) (.join t 1000))))

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
