(ns slam.hound.search
  "Search the classpath for vars and classes."
  (:require [clojure.java.io :refer [file reader]])
  (:import (java.io BufferedReader File FilenameFilter InputStreamReader
                    PushbackReader)
           (java.util StringTokenizer)
           (java.util.jar JarEntry JarFile)))

;;; Mostly taken from leiningen.util.ns and swank.util.class-browse.

;; TODO: replace with bultitude? but that doesn't do classes

;;; Clojure namespaces

(def classpath-files
  (for [f (.split (System/getProperty "java.class.path")
                  (System/getProperty "path.separator"))]
    (file f)))

(defn clj? [f]
  (.endsWith (.getName f) ".clj"))

(defn jar? [f]
  (let [f (file f)]
    (and (.isFile f) (.endsWith (.getName f) ".jar"))))

(defn class-file? [f]
  (.endsWith f ".class"))

(defn clojure-fn-file? [f]
  (re-find #"\$.*__\d+\.class" f))

(defn clojure-ns-file? [n]
  (.endsWith n "__init.class"))

(defn read-ns-form [r f]
  (let [form (try (read r false ::done)
                  (catch Exception _ ::done))]
    (if (and (list? form) (= 'ns (first form)))
      form
      (when-not (= ::done form)
        (recur r f)))))

(defn find-ns-form [f]
  (when (and (.isFile (file f)) (clj? f))
    (read-ns-form (PushbackReader. (reader f)) f)))

(defn namespaces-in-dir [dir]
  (sort (for [f (file-seq (file dir))
              :let [ns-form (find-ns-form f)]
              :when ns-form]
          (second ns-form))))

(defn ns-in-jar-entry [jarfile entry]
  (with-open [rdr (-> jarfile
                      (.getInputStream (.getEntry jarfile (.getName entry)))
                      InputStreamReader.
                      BufferedReader.
                      PushbackReader.)]
    (read-ns-form rdr jarfile)))

(defn namespaces-in-jar [jar]
  (let [jarfile (JarFile. jar)]
    (for [entry (enumeration-seq (.entries jarfile))
          :when (and (not (.isDirectory entry))
                     (clj? entry))]
      (if-let [ns-form (ns-in-jar-entry jarfile entry)]
        (second ns-form)))))

(defn namespaces []
  (let [files (mapcat namespaces-in-dir (filter #(.isDirectory %)
                                                classpath-files))
        jars (mapcat namespaces-in-jar (filter jar? classpath-files))]
    (set (remove nil? (concat files jars)))))

;;; Java classes

;; could probably be simplified

(defn expand-wildcard
  "Expands a wildcard path entry to its matching .jar files (JDK 1.6+).
  If not expanding, returns the path entry as a single-element vector."
  [#^String path]
  (let [f (File. path)]
    (if (= (.getName f) "*")
      (-> f .getParentFile
          (.list (proxy [FilenameFilter] []
                   (accept [d n] (jar? (file n))))))
      [f])))

(defn class-or-ns-name
  "Returns the Java class or Clojure namespace name for a class relative path."
  [n]
  (.replace
   (if (clojure-ns-file? n)
     (-> n (.replace "__init.class" "") (.replace "_" "-"))
     (.replace n ".class" ""))
   File/separator "."))

(def path-class-files nil)
(defmulti path-class-files
  "Returns a list of classes found on the specified path location
  (jar or directory), each comprised of a map with the following keys:
    :name  Java class or Clojure namespace name
    :loc   Classpath entry (directory or jar) on which the class is located
    :file  Path of the class file, relative to :loc"
  (fn [#^File f _]
    (cond (.isDirectory f)           :dir
          (jar? f)        :jar
          (class-file? (.getName f)) :class)))

(defmethod path-class-files :default
  [& _] [])

(defmethod path-class-files :jar
  ;; Build class info for all jar entry class files.
  [#^File f #^File loc]
  (let [lp (.getPath loc)]
    (try
      (map class-or-ns-name
           (filter class-file?
                   (map #(.getName #^JarEntry %)
                        (enumeration-seq (.entries (JarFile. f))))))
     (catch Exception e []))))          ; fail gracefully if jar is unreadable

(defmethod path-class-files :dir
  ;; Dispatch directories and files (excluding jars) recursively.
  [#^File d #^File loc]
  (let [fs (.listFiles d (proxy [FilenameFilter] []
                           (accept [d n] (not (jar? (file n))))))]
    (reduce concat (for [f fs] (path-class-files f loc)))))

(defmethod path-class-files :class
  ;; Build class info using file path relative to parent classpath entry
  ;; location. Make sure it decends; a class can't be on classpath directly.
  [#^File f #^File loc]
  (let [fp (str f), lp (str loc)
        loc-pattern (re-pattern (str "^" loc))]
    (if (re-find loc-pattern fp)                 ; must be descendent of loc
      (let [fpr (.substring fp (inc (count lp)))]
        [(class-or-ns-name fpr)])
      [])))

(defn scan-paths
  "Takes one or more classpath strings, scans each classpath entry location, and
  returns a list of all class file paths found, each relative to its parent
  directory or jar on the classpath."
  ([cp]
     (if cp
       (let [entries (enumeration-seq
                      (StringTokenizer. cp File/pathSeparator))
             locs (mapcat expand-wildcard entries)]
         (reduce concat (for [loc locs]
                          (path-class-files loc loc))))
       ()))
  ([cp & more]
     (reduce #(concat %1 (scan-paths %2)) (scan-paths cp) more)))

(def available-classes
  (remove clojure-fn-file?
          (scan-paths (System/getProperty "sun.boot.class.path")
                      (System/getProperty "java.ext.dirs")
                      (System/getProperty "java.class.path"))))
