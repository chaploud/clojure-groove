(ns user
  (:require [chaploud.groove :as g]))

(comment
  (g/start!)
  (g/load! "examples/acid.edn")
  (g/stop!))
