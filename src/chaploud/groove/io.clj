(ns chaploud.groove.io
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.pprint :as pprint]))

(def format-version 1)

(def ^:private song-keys [:globals :instruments :defs :scenes :tracks :arrangement])

(defn session->song [session]
  (apply array-map
         :groove/format format-version
         (mapcat (fn [k]
                   (let [v (cond-> (get session k)
                             (= k :tracks) (update-vals :node))]
                     (when (seq v)
                       [k (if (map? v) (into (sorted-map) v) v)])))
                 song-keys)))

(defn- read-file [path]
  (try (edn/read-string (slurp path))
       (catch Exception e
         (throw (ex-info (str "Cannot read " path ": " (ex-message e)) {:path path} e)))))

(defn read-song [path]
  (let [song (read-file path)
        dir (.getParentFile (jio/file path))
        included (for [inc-path (:include song)]
                   (read-song (str (jio/file dir inc-path))))]
    (when-not (map? song)
      (throw (ex-info (str path " does not contain a map") {:path path})))
    (when-let [v (:groove/format song)]
      (when (> v format-version)
        (throw (ex-info (str path " needs a newer version of groove (format " v ")") {:path path}))))
    (reduce (fn [acc lib]
              (reduce #(update %1 %2 (fn [own] (merge (get lib %2) own))) acc [:instruments :defs :scenes]))
            (dissoc song :include)
            included)))

(defn song->session [song empty-session]
  (-> (merge empty-session (select-keys song song-keys))
      (update :globals #(merge (:globals empty-session) %))
      (update :tracks update-vals (fn [node] {:node node}))))

(defn write-song! [session path]
  (let [f (jio/file path)]
    (some-> (.getParentFile f) .mkdirs)
    (spit f (binding [*print-namespace-maps* false]
              (with-out-str (pprint/pprint (session->song session)))))
    path))
