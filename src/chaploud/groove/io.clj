(ns chaploud.groove.io
  (:require [chaploud.groove.session :as session]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.pprint :as pprint]))

(def format-version 1)

(def ^:private song-keys [:globals :kits :instruments :defs :scenes :tracks :arrangement])

(defn session->song [session]
  (apply array-map
         :groove/format format-version
         (mapcat (fn [k]
                   (let [v (if (= k :tracks) (session/track-nodes session) (get session k))]
                     (when (seq v)
                       [k (if (map? v) (into (sorted-map) v) v)])))
                 song-keys)))

(def bundled-prefix "chaploud/groove/songs/")

(defn locate ^java.net.URL [path]
  (let [f (jio/file path)]
    (cond
      (.isFile f) (jio/as-url (.getCanonicalFile f))
      (jio/resource path) (jio/resource path)
      (jio/resource (str bundled-prefix path ".edn")) (jio/resource (str bundled-prefix path ".edn"))
      :else (throw (ex-info (str "No song file, resource or bundled song named " path) {:path path})))))

(defn- read-url [^java.net.URL url]
  (try (edn/read-string (slurp url))
       (catch Exception e
         (throw (ex-info (str "Cannot read " url ": " (ex-message e)) {:url (str url)} e)))))

(defn- read-song* [^java.net.URL url seen]
  (let [where (str url)
        song (read-url url)]
    (when (seen where)
      (throw (ex-info (str "Include cycle through " where) {:url where})))
    (when-not (map? song)
      (throw (ex-info (str where " does not contain a map") {:url where})))
    (let [version (:groove/format song 1)
          includes (:include song [])]
      (when-not (pos-int? version)
        (throw (ex-info (str where ": :groove/format must be a positive integer") {:url where})))
      (when (> version format-version)
        (throw (ex-info (str where " needs a newer version of groove (format " version ")") {:url where})))
      (when-not (and (vector? includes) (every? string? includes))
        (throw (ex-info (str where ": :include must be a vector of paths") {:url where})))
      (reduce (fn [acc inc-path]
                (let [lib (read-song* (java.net.URL. url ^String inc-path) (conj seen where))]
                  (reduce #(update %1 %2 (fn [own] (merge (get lib %2) own))) acc [:kits :instruments :defs :scenes])))
              (dissoc song :include)
              includes))))

(defn read-song [path]
  (read-song* (locate path) #{}))

(defn bundled-songs []
  (edn/read-string (slurp (jio/resource (str bundled-prefix "index.edn")))))

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
