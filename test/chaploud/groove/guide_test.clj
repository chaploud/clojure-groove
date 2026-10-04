(ns chaploud.groove.guide-test
  (:require [chaploud.groove :as g]
            [chaploud.groove.live :as live]
            [chaploud.groove.session :as s]
            [clojure.java.io :as jio]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- code-blocks [path]
  (map second (re-seq #"(?s)```clojure\n(.*?)```" (slurp path))))

(deftest the-guide-runs-as-written
  (let [saved @live/!state
        dir (str/replace (str (java.nio.file.Files/createTempDirectory "guide" (make-array java.nio.file.attribute.FileAttribute 0))) "\\" "/")]
    (reset! live/!state {:session s/empty-session :compiled {}})
    (try
      (with-redefs [g/start! (constantly :playing)]
        (binding [*ns* (create-ns 'guide-run)]
          (refer-clojure)
          (doseq [block (code-blocks "docs/guide.md")
                  form (read-string (str "[" (str/replace block "\"my-track." (str "\"" dir "/my-track.")) "]"))]
            (try (eval form)
                 (catch Exception e
                   (when-not (= '(g/drum :kick "x..q") form)
                     (throw (ex-info (str "Guide form failed: " (pr-str form)) {} e))))))))
      (is (= #{:drums :bass :pad :arp :echo} (set (keys (:tracks (g/session))))))
      (is (.exists (java.io.File. (str dir "/my-track.wav"))))
      (finally (reset! live/!state saved)))))

(deftest the-jam-scratchpad-runs
  (let [saved @live/!state
        forms (with-open [r (java.io.PushbackReader. (jio/reader "examples/jam.clj"))]
                (doall (take-while some? (repeatedly #(read {:eof nil} r)))))
        body (rest (first (filter #(and (seq? %) (= 'comment (first %))) forms)))]
    (reset! live/!state {:session s/empty-session :compiled {}})
    (try
      (with-redefs [g/start! (constantly :playing)]
        (binding [*ns* (create-ns 'jam-run)]
          (refer-clojure)
          (alias 'g 'chaploud.groove)
          (doseq [form body]
            (try (eval form)
                 (catch Exception e
                   (throw (ex-info (str "jam.clj form failed: " (pr-str form)) {} e)))))))
      (is (empty? (:tracks (g/session))))
      (finally (reset! live/!state saved)))))
