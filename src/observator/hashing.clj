;; # Hashing Things!!!!
(ns observator.hashing
  (:refer-clojure :exclude [hash])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.analyzer.jvm :as ana]
            [clojure.tools.analyzer.passes.jvm.emit-form :as ana.passes.ef]
            [observator.lib :as obs.lib]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n])
  (:import (java.io FileInputStream LineNumberReader InputStreamReader PushbackReader)
           (clojure.lang RT ExceptionInfo)))

(def long-thing
  (map obs.lib/fix-case (str/split-lines (slurp "/usr/share/dict/words"))))

(take 3 long-thing)

(defn source-fn
  "Same as clojure.repl/source-fn but can handle absolute paths."
  [x]
  (when-let [v (resolve x)]
    (when-let [filepath (:file (meta v))]
      (when-let [strm (or (.getResourceAsStream (RT/baseLoader) filepath)
                          (when (not= filepath "NO_SOURCE_PATH")
                            (FileInputStream. (io/file filepath))))]
        strm
        (with-open [rdr (LineNumberReader. (InputStreamReader. strm))]
          (dotimes [_ (dec (:line (meta v)))] (.readLine rdr))
          (let [text (StringBuilder.)
                pbr (proxy [PushbackReader] [rdr]
                      (read [] (let [i (proxy-super read)]
                                 (.append text (char i))
                                 i)))
                read-opts (if (.endsWith ^String filepath "cljc") {:read-cond :allow} {})]
            (if (= :unknown *read-eval*)
              (throw (IllegalStateException. "Unable to read source while *read-eval* is :unknown."))
              (read read-opts (PushbackReader. pbr)))
            (str text)))))))

#_(source-fn 'source-fn)

(defn analyze+qualify [form]
  (-> form
      ana/analyze
      (ana.passes.ef/emit-form #{:qualified-symbols})))

#_(analyze+qualify 'analyze+qualify)


(defn sha1-base64 [s]
  (String. (.encode (java.util.Base64/getUrlEncoder)
                    (.digest (java.security.MessageDigest/getInstance "SHA-1") (.getBytes s)))))

(comment
  (sha1-base64 "hello"))

(defn var-name
  "Takes a `form` and returns the name of the var, if it exists."
  [form]
  (when (and (sequential? form)
             (contains? '#{def defn} (first form)))
    (second form)))


(defn var-dependencies [form]
  (let [form (analyze+qualify form)
        var-name (var-name form)]
    (->> form
         (tree-seq sequential? seq)
         (keep #(when (and (symbol? %)
                           (not= var-name %))
                  (resolve %)))
         (into #{}))))

#_(var-dependencies '(defn foo
                       ([] (foo "s"))
                       ([s] (str/includes? (obs.lib/fix-case s) "hi"))))

(declare hash)

(defn hash-var [var->hash visited var]
  (when-not (or (str/starts-with? (-> var meta :ns str) "clojure.")
                (visited var))
    (when-let [code-string (and (var? var)
                                (-> var
                                    symbol
                                    source-fn))]
      (binding [*ns* (-> var meta :ns)]
        (try
          (hash var->hash (conj visited var) (-> code-string read-string analyze+qualify))
          (catch ExceptionInfo e
            (doto {:ex/source #'hash-var
                   :ex/data (ex-data e)
                   :ex/message (.getMessage e)
                   :ex/var var}
              prn)))))))


(comment
  (= (hash-var {} #{} #'observator.lib/fix-case)
     (hash-var {} #{} #'observator.lib/fix-case-2))

  (hash-var {} #{} #'observator.lib/fix-case-3))

(defn hash-dependencies
  "Takes a `form` and a mapping `var->hash` returns a sorted vector of the hashes of the vars
  it depends on."
  [var->hash visited form]
  (into {}
        (map (juxt identity (partial hash-var var->hash visited)))
        (var-dependencies form)))

#_(hash-dependencies {} #{} '(def foo observator.lib/fix-case))


(defn hash
  ([var->hash form]
   (hash var->hash #{} form))
  ([var->hash visited form]
   (sha1-base64 (pr-str (conj (->> form (hash-dependencies var->hash visited) vals set)
                              (cond->> form
                                (var-name form) (drop 2)))))))

(comment
  (hash '(def foo (do slow-thing)) {(resolve 'slow-thing) "fd234c"}))

(defn hash-vars
  "Hashes the defined vars found in the code string (normally a namespace)."
  [code-string]
  (loop [{:keys [var->hash nodes] :as r} {:nodes (:children (p/parse-string-all code-string))
                                          :visited #{}
                                          :var->hash {}}]
    (if-let [node (first nodes)]
      (recur (cond
               (= :list (n/tag node)) (let [form (-> node n/string read-string analyze+qualify)
                                            var-name (var-name form)]
                                        (cond-> (update r :nodes rest)
                                          var-name
                                          (assoc-in [:var->hash var-name] (hash var->hash form))))
               :else (update r :nodes rest)))
      var->hash)))

#_(hash-vars (slurp "src/observator/lib.clj"))

;; (defn declaring-classfiles [sym]
;;   (->> sym
;;        reflect/reflect
;;        :members
;;        (map :declaring-class)
;;        (map #(str (str/replace % "." fs/*sep*) ".class"))
;;        set))

;; (comment
;;   (declaring-classfiles clojure.core/add-tap)
;;   (declaring-classfiles io.methvin.watcher.DirectoryChangeEvent$EventType/CREATE)
;;   (declaring-classfiles nextjournal.directory-watcher/create))


;; (defn filenames-in-jar [path]
;;   (-> path
;;       io/file
;;       java.util.jar.JarFile.
;;       cp/filenames-in-jar
;;       set))

;; (comment
;;   (filenames-in-jar (nth (cp/classpath) 10))
;;   (filenames-in-jar (nth (cp/classpath) 16)))

;; (comment
;;   (-> (cp/classpath)
;;       (nth 10)
;;       io/file
;;       java.util.jar.JarFile.
;;       cp/filenames-in-jar
;;       set)

;;   (resolve 'io.methvin.watcher.DirectoryChangeEvent$EventType)
;;   ;; clojure var precompiled in jar

;;   (-> #'nextjournal.directory-watcher/create meta)
;;   (-> #'observator.lib/fix-case meta)
;;   (-> #'observator.core/fix-case meta)
;;   (-> #'clojure.core/add-tap meta)

;;   (resolve 'io.methvin.watcher.DirectoryChangeEvent$EventType)
;;   (resolve 'io.methvin.watcher.DirectoryChangeEvent$EventType/CREATE)

;;   (reflect/reflect nextjournal.directory-watcher/create)
;;   (reflect/reflect clojure.core/add-tap)
;;   (find-source (type clojure.core/add-tap))
;;   ;; java class
;;   (find-source io.methvin.watcher.DirectoryChangeEvent$EventType/CREATE)
;;   ;; clojure var with source in jar TODO
;;   (find-source nextjournal.directory-watcher/create)
;;   )

;; (defn required-namespaces
;;   "Takes a `form` and returns a set of the namespaces it requires."
;;   [form]
;;   (->> form
;;        (tree-seq sequential? seq)
;;        (keep (fn [form]
;;                (when (and (sequential? form)
;;                           (= (first form) 'clojure.core/require))
;;                  (into #{}
;;                        (map #(let [f (second %)]
;;                                (cond-> f (sequential? f) first)))
;;                        (rest form)))))
;;        (apply set/union)))


;; (comment
;;   (meta (find-ns 'observator.demo))

;;   (required-namespaces '(ns observator.core
;;                           (:require [observator.lib :as obs.lib]
;;                                     observator.demo)))

;;   (required-namespaces '(do (require '[observator.lib :as obs.lib]
;;                                      'observator.demo2)
;;                             (require 'observator.demo)))
;;   ;; TODO
;;   (required-namespaces '(require '(clojure zip [set :as s]))))

;; (defn file-resource [var]
;;   (some-> var
;;           meta
;;           :file
;;           io/resource))
