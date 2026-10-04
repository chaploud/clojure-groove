(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.chaploud/clojure-groove)
(def version "0.1.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn jar [_]
  (b/delete {:path "target"})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis (b/create-basis {:project "deps.edn"})
                :src-dirs ["src"]
                :scm {:url "https://github.com/chaploud/clojure-groove"
                      :connection "scm:git:https://github.com/chaploud/clojure-groove.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Live-code grooves from the Clojure REPL: patterns as EDN data, pure-JVM synthesis"]
                           [:url "https://github.com/chaploud/clojure-groove"]
                           [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/licenses/MIT"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println jar-file))

(defn deploy [_]
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
