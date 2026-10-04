(ns ^:no-doc chaploud.groove.expand
  (:require [chaploud.groove.notation :as notation]))

(defn- fail [msg path data]
  (throw (ex-info msg (assoc data :path path))))

(def transforms #{:rev :fast :slow :every :transpose :degrade :arp})

(defn- check-transform [[op & args :as tf] path]
  (when-not (and (vector? tf) (transforms op))
    (fail (str "Unknown transform " (pr-str tf) "; expected one of " transforms) path {:transform tf}))
  (case op
    :rev nil
    (:fast :slow) (when-not (pos-int? (first args))
                    (fail (str (pr-str op) " needs a positive integer, got " (pr-str (first args))) path {:transform tf}))
    :every (do (when-not (pos-int? (first args))
                 (fail ":every needs a positive integer" path {:transform tf}))
               (check-transform (second args) path))
    :transpose (when-not (number? (first args))
                 (fail ":transpose needs a number of semitones" path {:transform tf}))
    :arp (let [[order rate] args]
           (when-not (#{:up :down :up-down :random} order)
             (fail ":arp needs an order: :up :down :up-down or :random" path {:transform tf}))
           (when-not (or (nil? rate) (and (ratio? rate) (pos? rate)))
             (fail ":arp rate is a fraction of a bar such as 1/16" path {:transform tf})))
    :degrade (when-not (and (number? (first args)) (<= 0 (first args) 1))
               (fail ":degrade needs a probability between 0 and 1" path {:transform tf})))
  tf)

(defn- distance [^String a ^String b]
  (let [n (count b)]
    (peek (reduce (fn [prev [i ca]]
                    (reduce (fn [row j]
                              (conj row (min (inc (peek row))
                                             (inc (prev (inc j)))
                                             (+ (prev j) (if (= ca (nth b j)) 0 1)))))
                            [(inc i)]
                            (range n)))
                  (vec (range (inc n)))
                  (map-indexed vector a)))))

(defn- suggestion [k defs]
  (let [target (str k)
        [best d] (first (sort-by second (map (fn [c] [c (distance target (str c))]) (keys defs))))]
    (when (and best (<= d (max 2 (quot (count target) 4))))
      (str " (did you mean " best "?)"))))

(defn- split-node [node]
  (let [[tag & more] node]
    (if (map? (first more))
      [tag (first more) (vec (rest more))]
      [tag {} (vec more)])))

(defn- one-child [tag children path]
  (when-not (= 1 (count children))
    (fail (str (pr-str tag) " takes exactly one body, got " (count children)) path {}))
  (first children))

(defn- leaf [path f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (fail (ex-message e) path (ex-data e)))))

(defn- positive-len [node path]
  (when-not (pos? (:len node))
    (fail "Pattern is empty" path {}))
  node)

(declare expand*)

(defn expand
  ([node defs] (expand* node defs {} {} [] #{}))
  ([node defs ctx path] (expand* node defs ctx {} path #{})))

(defn- expand*
  ([node defs ctx overrides path seen]
   (cond
     (qualified-keyword? node)
     (expand* [node] defs ctx overrides path seen)

     (not (and (vector? node) (keyword? (first node))))
     (fail (str "Expected a node such as [:steps ...] or a reference such as :clip/name, got " (pr-str node))
           path {:node node})

     :else
     (let [[tag attrs children] (split-node node)
           path (conj path tag)]
       (if (qualified-keyword? tag)
         (do
           (when (seen tag)
             (fail (str "Circular reference through " tag) path {:ref tag :cycle true}))
           (when-not (contains? defs tag)
             (fail (str "Undefined reference " tag (suggestion tag defs)) path {:ref tag}))
           (when (seq children)
             (fail (str "A reference takes only an attribute map: " (pr-str node)) path {}))
           (expand* (defs tag) defs ctx (merge attrs overrides) path (conj seen tag)))
         (let [ctx (merge ctx attrs overrides)
               sub (fn [i child] (expand* child defs ctx overrides (conj path i) seen))]
           (case tag
             :steps (let [step (:step ctx 1/16)
                          steps (leaf path #(notation/parse-steps (one-child tag children path)))]
                      (positive-len {:kind :steps :attrs ctx :step step :steps steps :len (* step (count steps))} path))
             :notes (let [items (leaf path #(notation/parse-notes (one-child tag children path) (:step ctx 1/16)))]
                      (positive-len {:kind :notes :attrs ctx :items items
                                     :len (reduce + 0 (map :dur items))}
                                    path))
             :cycle {:kind :cycle :attrs ctx :len 1
                     :ast (leaf path #(notation/parse-mini (one-child tag children path)))}
             :seq (let [cs (vec (map-indexed sub children))]
                    (positive-len {:kind :seq :children cs :len (reduce + 0 (map :len cs))} path))
             :par (let [cs (vec (map-indexed sub children))]
                    (positive-len {:kind :par :children cs :len (reduce max 0 (map :len cs))} path))
             :rep (let [[n child] children]
                    (when-not (and (pos-int? n) (= 2 (count children)))
                      (fail "Use [:rep n node] with a positive integer n" path {}))
                    (let [c (sub 1 child)]
                      {:kind :rep :n n :child c :len (* n (:len c))}))
             :fx (let [[tfs child] children
                       tfs (if (keyword? (first tfs)) [tfs] tfs)]
                   (when-not (and (vector? tfs) (= 2 (count children)))
                     (fail "Use [:fx [[:transform ...] ...] node]" path {}))
                   (let [c (sub 1 child)]
                     {:kind :fx :fx (mapv #(check-transform % path) tfs) :child c :len (:len c)}))
             (fail (str "Unknown node type " tag "; expected :steps :notes :cycle :seq :par :rep :fx or a qualified reference")
                   path {:tag tag}))))))))
