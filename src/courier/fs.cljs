(ns courier.fs
  (:require [fs]
            [path]))

(defn read-file [file]
  (try
    (.readFileSync fs file "utf-8")
    (catch :default e
      nil)))

(defn delete-file [file]
  (try
    (.unlinkSync fs file)
    (catch :default e nil)))

(defn write-file [file str]
  (.writeFileSync fs file str "utf-8"))

(def ^:private mode (js/parseInt "0755" 8))

(defn mkdirs [dir & [made]]
  (let [dir (.resolve path dir)]
    (try
      (.mkdirSync fs dir mode)
      (or made dir)
      (catch :default root-error
        (if (= "ENOENT" (.-code root-error))
          (->> made
               (mkdirs (.dirname path dir))
               (mkdirs dir))
          (when-not (.isDirectory (try
                                    (.statSync fs dir)
                                    (catch :default e
                                      (throw root-error))))
            (throw root-error)))))))

(defn ensure-dir [dir]
  (mkdirs dir))
