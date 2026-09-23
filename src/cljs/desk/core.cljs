(ns desk.core
  "App entry point: wires storage, the live-tick WebSocket, Replicant's
  render loop, and the central action-dispatch multimethod together.

  This namespace is cross-cutting glue rather than a single agent's
  owned domain -- unlike the state layer (desk.db/storage/ws) or any
  view, it doesn't belong to one of the five build agents in
  CLAUDE.md's folder ownership table. It's maintained directly
  (analogous to deps.edn or a future shadow-cljs.edn), and every view
  namespace is expected to extend `handle-action!` with its own
  `defmethod` rather than have this file edited on their behalf.

  See CLAUDE.md's 'Action dispatch (Replicant)' section for the full
  contract this namespace implements.

  shadow-cljs.edn's :app build compiles this against public/index.html's
  #app div, served over shadow-cljs's own :dev-http (see shadow-cljs.edn)
  -- Pedestal itself still has no static-file route of its own (desk.service
  only serves /health and /ws), so the backend and the compiled frontend
  aren't served from the same origin/port yet. Fine for local dev via
  shadow-cljs's dev-http; would need a Pedestal static-file route (State &
  Backend Architect's scope) to serve both together."
  (:require [replicant.dom :as r]
            [datascript.core :as d]
            [desk.db :as db]
            [desk.storage :as storage]
            [desk.ws :as ws]
            [desk.views.dashboard :as dashboard]))

;; -- Central action dispatch --------------------------------------------
;;
;; Interactive elements emit actions as a vector of action vectors, e.g.
;; `[[:action/place-order order]]`, via `:on {:click actions}` instead of
;; a plain function -- this is what keeps view render fns pure. Replicant
;; hands that raw vector to whatever fn is registered with
;; `replicant.dom/set-dispatch!` (see `dispatch!` below), which forwards
;; each individual action vector to this multimethod, dispatching on its
;; first element.
;;
;; View namespaces extend this from their own file with `defmethod`; they
;; never need to edit desk.core to add a new action.

(defmulti handle-action!
  "event: the Replicant-built event map (:replicant/dom-event and friends).
  action: a single action vector, e.g. [:action/place-order order-data]."
  (fn [_event action] (first action)))

(defmethod handle-action! :default
  [_event action]
  (js/console.warn "Unhandled action:" (pr-str action)))

(defn- dispatch!
  "Registered once via `replicant.dom/set-dispatch!` in `init`. `actions`
  is whatever raw value was given to `:on {:some-event actions}` --by
  convention here, a vector of action vectors."
  [event actions]
  (doseq [action actions]
    (handle-action! event action)))

;; -- Render loop ----------------------------------------------------------
;;
;; The DOM lookup happens inside `render!` itself, not at namespace-load
;; time, on purpose: this namespace is transitively required by the
;; shadow-cljs `:test` (node-test) build via view namespaces' `defmethod
;; core/handle-action!` extensions, and Node has no `js/document` -- a
;; top-level `(js/document.getElementById ...)` would throw as soon as
;; the namespace loaded, before any test even ran.

(defn render!
  "Re-renders every top-level view against the current `desk.db/conn`
  value. Only `desk.views.dashboard` exists today -- as more views ship,
  this composition needs to grow to include them (orchestrator-maintained,
  same as the rest of this namespace). No-ops outside a browser (e.g. the
  node-test build) since there's no #app element to render into."
  []
  (when-let [root (and (exists? js/document)
                       (js/document.getElementById "app"))]
    (r/render root (dashboard/view @db/conn))))

(defn ^:export init
  "App entry point. Restores/seeds `desk.db/conn`, connects the live-tick
  WebSocket, registers the central dispatch fn, re-renders on every
  DataScript transaction, and renders once immediately."
  []
  (storage/init!)
  (ws/connect!)
  (r/set-dispatch! dispatch!)
  (d/listen! db/conn ::render (fn [_tx-report] (render!)))
  (render!))
