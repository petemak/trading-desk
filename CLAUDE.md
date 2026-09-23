# trading-desk

## Project

ClojureScript + Replicant + DataScript paper-trading dashboard,
styled with Tailwind CSS. The backend is Clojure, structured as
three Component lifecycle components — config (Aero), engine (price
simulation), and pedestal (Jetty HTTP + WebSocket server) —
assembled into one system in `desk.system`. No real brokerage
connection, no auth.

## Canonical DataScript schema

```clojure
{:ticker/symbol        {:db/unique :db.unique/identity}
 :portfolio/watchlist  {:db/cardinality :db.cardinality/many}
 :tx/id                {:db/unique :db.unique/identity}}
```

Entities:

- `:portfolio/cash` — singleton, defaults to `100000`.
- `:ticker/{symbol,price,bid,ask,day-open,high,low,volume}`
- `:tx/{id,ticker,type,order-type,shares,price,total,ts}`
- `:history/{ticker,price,ts}`
- `:app/selected-ticker` — on `:app/ui`.

## Data validation (Malli)

Shape/type validation is a separate concern from the DataScript
schema above — DataScript enforces storage-level identity and
cardinality, Malli enforces the shape and type of data at the
boundaries where it enters the system. Schemas live in
`src/cljc/desk/schema.cljc` (Config, Ticker, Order) so both the
Clojure backend and the ClojureScript frontend require the same
definitions instead of duplicating them.

Validate at exactly three boundaries:

- `config.edn` on system start.
- Incoming order maps before the business-rule checks in
  `validate-order`.
- (optionally) WebSocket tick payloads before they're transacted
  into DataScript.

Use `(m/explain schema value)` + `malli.error/humanize` for error
messages, never a bare `(m/validate ...)` boolean when the failure
needs to be shown to a person.

## Action dispatch (Replicant)

Interactive elements stay data, not closures, so view render functions
stay pure: `:on {:click actions}` where `actions` is a **vector of
action vectors**, e.g. `[[:action/place-order order-data]]`, never a
plain function. `src/cljs/desk/core.cljs` (app entry point,
orchestrator-maintained — not owned by any single build agent) wires
this up:

- `replicant.dom/set-dispatch!` registers one global fn that receives
  every such `actions` vector and forwards each action to
  `desk.core/handle-action!`, a multimethod dispatching on the
  action's first element (`(fn [_event action] (first action))`).
- A view namespace **extends** `handle-action!` with its own
  `defmethod` from its own file (e.g. `(defmethod core/handle-action!
  :action/place-order [_event [_ order]] ...)`) — it never needs
  `core.cljs` itself edited to add a new action.
- `core.cljs` also re-renders on every `desk.db/conn` transaction via
  `d/listen!`, so an action handler's `d/transact!` call is enough to
  trigger a UI update.

## Persistence

Serialize with `(pr-str @conn)` to `localStorage` key `"desk-db"`;
read back with
`(clojure.edn/read-string {:readers d/data-readers} s)`.

## Design tokens (Tailwind)

- Dark mode only.
- Tailwind slate palette: backgrounds `slate-950` / `slate-900`,
  borders `slate-800`.
- Buy = `emerald-400`, Sell = `rose-400`, Limit = `sky-400` —
  consistent across every view.
- Tailwind CSS v4 is installed (CSS-first config, no
  `tailwind.config.js`). Entry stylesheet: `public/css/input.css`
  (`@import "tailwindcss";`).

## Folder ownership

- `src/clj/desk/{system,server,service,engine}.clj`,
  `resources/config.edn`, `src/cljc/desk/schema.cljc`,
  `src/cljs/desk/{db,storage,ws}.cljs`
  -> **State & Backend Architect** only
- `src/cljs/desk/views/dashboard.cljs` -> **Dashboard Engineer** only
- `src/cljs/desk/views/trading.cljs` -> **Trading Engineer** only
- `src/cljs/desk/views/{watchlist,news}.cljs` -> **Market Intel
  Engineer** only
- `src/cljs/desk/views/history.cljs` -> **Ledger Engineer** only

Agents must not edit files outside their owned namespace.

## Testing

Test namespaces mirror source namespaces/paths. A source file at
`src/clj/desk/foo.clj` (namespace `desk.foo`) has its tests in
`test/clj/desk/foo_test.clj` (namespace `desk.foo-test`). The same
convention applies on the ClojureScript side: `src/cljs/desk/bar.cljs`
(namespace `desk.bar`) is tested by `test/cljs/desk/bar_test.cljs`
(namespace `desk.bar-test`). Run the suite with `clojure -M:test`
(kaocha, configured via `tests.edn`).
