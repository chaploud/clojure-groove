(ns chaploud.groove.io-test
  (:require [chaploud.groove.io :as io]
            [chaploud.groove.session :as s]
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
        loaded (io/song->session (io/read-song (io/write-song! sess path)) s/empty-session)]
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
