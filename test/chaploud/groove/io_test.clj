(ns chaploud.groove.io-test
  (:require [chaploud.groove.io :as io]
            [chaploud.groove.session :as s]
            [clojure.edn]
            [clojure.java.io :as jio]
            [clojure.test :refer [deftest is]]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory "groove" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest round-trip
  (let [path (str (tmp-dir) "/song.edn")
        sess (-> s/empty-session
                 (s/put-def :clip/a [:notes {:inst :synth/keys} [:e 0 #{0 2} :_ 1/16 3]])
                 (s/play :a [:clip/a {:octave 3}])
                 (assoc-in [:globals :root] :a))
        loaded (io/song->session (io/read-song (io/write-song! sess path)))]
    (is (= (select-keys sess [:defs :tracks :globals]) (select-keys loaded [:defs :tracks :globals])))))

(deftest includes-merge-with-local-precedence
  (let [dir (tmp-dir)]
    (spit (jio/file dir "lib.edn") (pr-str {:defs {:clip/a [:steps "x"] :clip/b [:steps "x."]}}))
    (spit (jio/file dir "song.edn") (pr-str {:include ["lib.edn"] :defs {:clip/b [:steps "xx"]}}))
    (is (= {:clip/a [:steps "x"] :clip/b [:steps "xx"]}
           (:defs (io/read-song (str dir "/song.edn")))))))

(deftest rejects-newer-formats
  (let [path (str (tmp-dir) "/song.edn")]
    (spit path (pr-str {:groove/format 99}))
    (is (thrown-with-msg? Exception #"newer version" (io/read-song path)))))

(deftest include-errors-name-the-file
  (let [dir (tmp-dir)]
    (spit (jio/file dir "a.edn") (pr-str {:include ["b.edn"]}))
    (spit (jio/file dir "b.edn") (pr-str {:include ["a.edn"]}))
    (spit (jio/file dir "c.edn") (pr-str {:include "a.edn"}))
    (is (thrown-with-msg? Exception #"Include cycle" (io/read-song (str dir "/a.edn"))))
    (is (thrown-with-msg? Exception #":include must be a vector" (io/read-song (str dir "/c.edn"))))))

(deftest bundled-songs-load-by-name-and-play
  (doseq [{:keys [name]} (io/bundled-songs)]
    (let [session (io/song->session (io/read-song name))]
      (is (map? (s/validate! session)) name))))

(deftest includes-resolve-inside-resources
  (is (contains? (:defs (io/read-song "cascade")) :mylib/house)))

(deftest unknown-songs-say-so
  (is (thrown-with-msg? Exception #"No song file, resource or bundled song named nope" (io/read-song "nope"))))

(deftest the-song-index-lists-every-bundled-song
  (let [dir (jio/file (jio/resource "chaploud/groove/songs/index.edn"))
        files (->> (.listFiles (.getParentFile dir))
                   (map #(.getName ^java.io.File %))
                   (filter #(re-matches #".+\.edn" %))
                   (remove #{"index.edn"})
                   (map #(subs % 0 (- (count %) 4)))
                   set)]
    (is (= files (set (map :name (io/bundled-songs)))))))

(deftest old-scene-files-explain-the-change
  (let [path (str (tmp-dir) "/song.edn")]
    (spit path (pr-str {:scenes {:a {}}}))
    (is (thrown-with-msg? Exception #":scenes was renamed to :sections" (io/read-song path)))))

(deftest round-trip-keeps-sections-and-the-arrangement
  (let [path (str (tmp-dir) "/song.edn")
        sess (assoc s/empty-session
                    :sections {:a {:k [:steps {:inst :drum/kick} "x"]} :b {:base :a :k nil}}
                    :arrangement [[:a 2] [:b 2 {:fill 1}]])
        saved (io/write-song! sess path)
        loaded (io/song->session (io/read-song saved))]
    (is (= (select-keys sess [:sections :arrangement]) (select-keys loaded [:sections :arrangement])))
    (is (= 2 (:groove/format (clojure.edn/read-string (slurp path)))))))

(deftest includes-merge-sections-and-kits
  (let [dir (tmp-dir)]
    (spit (jio/file dir "lib.edn") (pr-str {:sections {:a {:k :x/a} :b {:k :x/b}} :kits {:x/kit {:bd :drum/rim}}}))
    (spit (jio/file dir "song.edn") (pr-str {:include ["lib.edn"] :sections {:b {:k :x/mine}}}))
    (let [song (io/read-song (str dir "/song.edn"))]
      (is (= {:a {:k :x/a} :b {:k :x/mine}} (:sections song)))
      (is (= {:x/kit {:bd :drum/rim}} (:kits song))))))

(deftest misspelled-or-malformed-song-keys-are-rejected
  (let [dir (tmp-dir)]
    (spit (jio/file dir "typo.edn") (pr-str {:arangement [[:a 8]]}))
    (spit (jio/file dir "shape.edn") (pr-str {:sections [:a]}))
    (spit (jio/file dir "bad-node.edn") (pr-str {:tracks {:k [:steps {:inst :drum/kick} "xz"]}}))
    (is (thrown-with-msg? Exception #"unknown keys \[:arangement\]" (io/read-song (str dir "/typo.edn"))))
    (is (thrown-with-msg? Exception #":sections must be a map" (io/read-song (str dir "/shape.edn"))))
    (is (thrown-with-msg? Exception #"bad-node.edn: Track :k" (io/load-song (str dir "/bad-node.edn"))))))

(deftest saving-ignores-the-repl-print-length
  (let [path (str (tmp-dir) "/song.edn")
        sess (s/play s/empty-session :k [:steps {:inst :drum/kick} (vec (repeat 32 :x))])]
    (binding [*print-length* 10 *print-level* 2]
      (io/write-song! sess path))
    (is (= 32 (count (get-in (io/read-song path) [:tracks :k 2]))))))
