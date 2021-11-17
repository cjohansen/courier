(ns courier.fs
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.nio.file Files StandardCopyOption)))

(defn read-file [file]
  (let [java-file (io/file file)]
    (when (.exists java-file)
      (slurp java-file))))

(defn delete-file [file]
  (io/delete-file file true))

(defn write-file
  "Writes the file to a temporary file, then performs an atomic move, ensuring
  that cache files are never partial files."
  [file content]
  (let [^File end-file (if (isa? File file) file (File. ^String file))
        tmp-f (File/createTempFile (.getName end-file) ".tmp" (.getParentFile end-file))]
    (spit tmp-f content)
    (Files/move (.toPath tmp-f) (.toPath end-file) (into-array [StandardCopyOption/ATOMIC_MOVE]))))

(defn ensure-dir [^String dir]
  (.mkdirs (File. dir)))

(defn dirname [path]
  (str/join "/" (drop-last (str/split path #"/"))))
