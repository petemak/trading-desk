(ns desk.schema
  "Malli schemas shared by the Clojure backend and ClojureScript frontend.

  Per CLAUDE.md's 'Data validation (Malli)' section, these are validated at
  the boundaries where data enters the system:
    - `Config`  -> resources/config.edn, validated once on system start
                   (see `desk.system/config`).
    - `Order`   -> incoming order/tx submission maps, before business-rule
                   checks (e.g. sufficient cash/shares) are applied.
    - `Ticker`  -> the shape of a DataScript ticker entity; useful both for
                   validating WS tick payloads before they're transacted and
                   for tests.

  This namespace is .cljc (not .clj) so both sides `require` the exact same
  definitions instead of duplicating them."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def Config
  [:map
   [:http
    [:map
     [:port pos-int?]
     [:join? boolean?]]]
   [:engine
    [:map
     [:tick-ms pos-int?]]]])

(def Ticker
  [:map
   [:ticker/symbol string?]
   [:ticker/price number?]
   [:ticker/bid number?]
   [:ticker/ask number?]
   [:ticker/day-open number?]
   [:ticker/high number?]
   [:ticker/low number?]
   [:ticker/volume number?]])

(def Order
  [:map
   [:ticker string?]
   [:type [:enum :buy :sell]]
   [:order-type [:enum :market :limit]]
   [:shares pos-int?]
   [:price {:optional true} number?]])

(defn explain-humanized
  "Validate `value` against `schema`. Returns `nil` when valid, or a
  humanized (malli.error/humanize) error structure when invalid.

  Prefer this (or explicit m/explain + me/humanize) over a bare
  `m/validate` boolean whenever the failure might be shown to a person."
  [schema value]
  (some-> (m/explain schema value) me/humanize))

(defn valid?
  "Quick boolean guard. Use `explain-humanized` instead when the result of
  an invalid check needs to be reported to a person (error messages,
  exceptions, etc.)."
  [schema value]
  (m/validate schema value))
