(ns chaploud.groove.library
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio]))

(def ^:private sections [:kits :instruments :defs :about])

(def library
  (delay
    (let [files (edn/read-string (slurp (jio/resource "chaploud/groove/parts/index.edn")))]
      (apply merge-with merge
             (for [f files]
               (select-keys (edn/read-string (slurp (jio/resource (str "chaploud/groove/parts/" f))))
                            sections))))))

(defn catalog [session]
  (into {} (for [k [:kits :instruments :defs]]
             [k (merge (get @library k) (get session k))])))

(defn about [k] (get-in @library [:about k]))
