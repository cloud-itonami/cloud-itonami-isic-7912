(ns touroperatorops.store
  "SSoT for the ISIC-7912 tour-operator OPERATIONS-COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite --
  the same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a tour
  operator: tour-record logging (itinerary / participant-manifest /
  excursion data), tour-operation scheduling (itinerary / guide /
  vendor), local-vendor/guide settlement coordination, and
  traveler-safety-concern flagging (hazard / incident / medical
  concern). It NEVER directly finalizes a traveler-safety-clearance
  decision (e.g. clearing an excursion as safe after a reported
  hazard) -- see `touroperatorops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable
  block, per this fleet's Wave 4 person-facing-service safety
  guardrail (ADR-2607152500).

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `tours` directory keyed by `:tour-id` STRING
  (never a keyword -- consistent keying from the start, avoiding the
  silent-miss bug that plagued an earlier shepherd attempt).

  A registered/verified tour-booking record must exist before ANY
  proposal for that tour may ever commit or escalate --
  `touroperatorops.governor`'s `tour-unverified-violations` re-derives
  this from the tour's own `:registered?`/`:verified?` fields, never
  from a proposal's self-report, the SAME 'ground truth, not
  self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which tour a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log."
  (:require [kotoba.reservation :as res]))

(defprotocol Store
  (tour [s tour-id] "Registered tour-booking record, or nil.
    Tour map: {:tour-id .. :name .. :registered? bool :verified? bool}.")
  (all-tours [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-tours [s tours] "replace/seed the tour directory (map tour-id->tour)"))

;; ----------------------------- demo data -----------------------------

(defn- settlement-plan
  "One filed per-unit settlement rate -- `kotoba.reservation` ground
  truth the governor recomputes a settlement amount from. Integer minor
  units (USD cents)."
  [id per-unit]
  (res/rate-plan id :unit per-unit "USD" :min-units 1))

(defn demo-data
  "A small, self-contained tour directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:tours
   {"tour-1" {:tour-id "tour-1" :name "3-day guided highlands trek, 8 participants"
              :registered? true :verified? true
             :billable-units 40 :rate-plan (settlement-plan "tour-1-rate" 2500)}
    "tour-2" {:tour-id "tour-2" :name "Half-day city walking tour, 12 participants"
              :registered? true :verified? true
             :billable-units 400 :rate-plan (settlement-plan "tour-2-rate" 6000)}
    "tour-3" {:tour-id "tour-3" :name "Coastal kayak excursion, awaiting guide-certification verification"
              :registered? true :verified? false
             :billable-units 10 :rate-plan (settlement-plan "tour-3-rate" 3000)}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (tour [_ tour-id] (get-in @a [:tours tour-id]))
  (all-tours [_] (sort-by :tour-id (vals (:tours @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-tours [s tours] (when (seq tours) (swap! a assoc :tours tours)) s))

(defn seed-db
  "A MemStore seeded with the demo tour directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `tours` map (tour-id string ->
  tour map) -- the primary test/dev entry point. `tours` may be empty
  (an unregistered-everywhere store)."
  [tours]
  (->MemStore (atom {:tours (or tours {}) :ledger [] :coordination-log []})))
