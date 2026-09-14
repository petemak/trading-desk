(ns desk.storage
  "localStorage persistence, per CLAUDE.md's 'Persistence' section:
  serialize with `(pr-str @conn)` to key \"desk-db\", read back with
  `cljs.reader/read-string` (the cljs equivalent of
  `clojure.edn/read-string`) plus DataScript's tagged-literal readers."
  (:require [cljs.reader :as reader]
            [datascript.core :as d]
            [desk.db :as db]))

(def storage-key "desk-db")

(defn save!
  "Persist the current DB value to localStorage."
  []
  (.setItem js/localStorage storage-key (pr-str @db/conn)))

(defn load!
  "Restore a previously persisted DB value into `db/conn`, if present.
  Returns true when a persisted DB was found and loaded, false otherwise."
  []
  (if-let [s (.getItem js/localStorage storage-key)]
    (let [data (reader/read-string {:readers d/data-readers} s)]
      (d/reset-conn! db/conn data)
      true)
    false))

(defn init!
  "Load persisted state if present, otherwise seed fresh initial data.
  Call once at app startup."
  []
  (when-not (load!)
    (db/init!)))
