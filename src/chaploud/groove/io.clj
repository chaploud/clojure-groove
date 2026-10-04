(ns chaploud.groove.io
  (:require [chaploud.groove.session :as session]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.pprint :as pprint]))

(def format-version 1)

(def ^:private song-keys [:globals :instruments :defs :scenes :tracks :arrangement])

(defn session->song [session]
  (apply array-map
         :groove/format format-version
         (mapcat (fn [k]
                   (let [v (if (= k :tracks) (session/track-nodes session) (get session k))]
                     (when (seq v)
                       [k (if (map? v) (into (sorted-map) v) v)])))
                 song-keys)))

(defn- read-file [path]
  (try (edn/read-string (slurp path))
       (catch Exception e
         (throw (ex-info (str "Cannot read " path ": " (ex-message e)) {:path path} e)))))

(defn- read-song* [path seen]
  (let [file (.getCanonicalFile (jio/file path))
        song (read-file path)]
    (when (seen file)
      (throw (ex-info (str "Include cycle through " path) {:path path})))
    (when-not (map? song)
      (throw (ex-info (str path " does not contain a map") {:path path})))
    (let [version (:groove/format song 1)
          includes (:include song [])]
      (when-not (pos-int? version)
        (throw (ex-info (str path ": :groove/format must be a positive integer") {:path path})))
      (when (> version format-version)
        (throw (ex-info (str path " needs a newer version of groove (format " version ")") {:path path})))
      (when-not (and (vector? includes) (every? string? includes))
        (throw (ex-info (str path ": :include must be a vector of paths") {:path path})))
      (reduce (fn [acc inc-path]
                (let [lib (read-song* (str (jio/file (.getParentFile file) inc-path)) (conj seen file))]
                  (reduce #(update %1 %2 (fn [own] (merge (get lib %2) own))) acc [:instruments :defs :scenes])))
              (dissoc song :include)
              includes))))

(defn read-song [path]
  (read-song* path #{}))

(defn song->session [song]
  (-> (merge session/empty-session (select-keys song song-keys))
      (update :globals #(merge (:globals session/empty-session) %))
      (update :tracks update-vals (fn [node] {:node node}))))

(defn write-song! [session path]
  (let [f (jio/file path)]
    (some-> (.getParentFile f) .mkdirs)
    (spit f (binding [*print-namespace-maps* false]
              (with-out-str (pprint/pprint (session->song session)))))
    path))
