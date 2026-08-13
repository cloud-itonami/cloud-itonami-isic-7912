(ns touroperatorops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-7912`: this
  repo had NO demo page and no generator at all. This namespace drives
  the REAL actor stack -- `touroperatorops.operation` (a langgraph-clj
  StateGraph) -> `touroperatorops.governor` ->
  `touroperatorops.store` -- through a scenario adapted from this
  repo's own `touroperatorops.sim` demo driver (`clojure -M:dev:run`,
  run and read BEFORE this file was written: its tour ids `tour-1`..
  `tour-3` DO exist in `touroperatorops.store/demo-data`, so reusing
  them was safe).

  EVERY entity, id, amount, hold reason and approver on the generated
  page comes out of this run's actual actor/store output. Nothing is
  hand-typed. Where a value CANNOT be obtained from the store, the
  page says so rather than inventing it -- see `approver-disclosure`,
  which derives at RENDER time whether the store actually kept the
  approver, instead of asserting a fixed claim that would rot the day
  someone changes `commit-record`.

  Determinism: no timestamps in the page content, no wall clock, no
  random. Byte-identical across reruns from the same seed (verify by
  diffing two runs into two distinct temp files).

  Build-time invariant: `-main` REFUSES to write the page unless the
  run produced at least one HARD governor refusal that carries a real
  violation. The check is two-stage on purpose -- a rollout-phase hold
  (`:phase-disabled` / `:phase-approval`) is also written as a
  `:governor-hold` fact but carries an EMPTY `:violations` vector, so
  merely counting holds would be satisfied by a page that never showed
  a single compliance refusal.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [touroperatorops.advisor :as advisor]
            [touroperatorops.governor :as governor]
            [touroperatorops.operation :as op]
            [touroperatorops.phase :as phase]
            [touroperatorops.store :as store]))

(def ^:private manager "tour-operations-manager-1")

(defn- ctx [ph]
  {:actor-id "mgr-1" :actor-role :tour-operations-manager :phase ph})

;; ----------------------------- scenario -----------------------------
;;
;; Each scenario is executed through the compiled graph and the store's
;; coordination log is measured immediately before and after, so the
;; record a run produced is attributed by POSITION, not by joining on
;; [op tour-id] -- that join is NOT unique here (three separate runs
;; touch `:log-tour-record` on `tour-1`) and would mis-attribute
;; approvers.

(defn- audit-of [r] (get-in r [:state :audit] []))

(defn- run-scenario!
  "Executes one scenario against `actor`/`db` and returns a run map
  carrying everything the page needs about it -- all of it observed,
  none of it declared up front."
  [db actor {:keys [id label phase request approve]}]
  (let [before (count (store/coordination-log db))
        r1     (g/run* actor {:request request :context (ctx phase)} {:thread-id id})
        paused? (= :escalate (get-in r1 [:state :disposition]))
        r2     (when (and paused? approve)
                 (g/run* actor {:approval approve} {:thread-id id :resume? true}))
        final  (or r2 r1)
        after  (vec (drop before (store/coordination-log db)))]
    {:id          id
     :label       label
     :phase       phase
     :op          (:op request)
     :tour-id     (:tour-id request)
     :verdict     (get-in r1 [:state :verdict])
     :disposition (get-in final [:state :disposition])
     :paused?     paused?
     :approval    approve
     :audit       (into (audit-of r1) (when r2 (audit-of r2)))
     :records     after}))

(defn- direct-actuation-advisor
  "An advisor that claims a direct actuation (`:effect :commit`)
  instead of a proposal -- the governor's `:effect-not-propose` HARD
  check exists for exactly this failure mode."
  []
  (reify advisor/Advisor
    (-advise [_ store request]
      (assoc (advisor/infer store request) :effect :commit))))

(def ^:private scenarios
  "Every disposition this actor can reach, driven from the seeded tour
  directory only. `tour-99` is deliberately absent from the seed -- it
  is a lookup miss, not an invented entity."
  [{:id "s1" :label "tour record logged at phase 1 -- rollout gate demands a human, manager approves"
    :phase 1 :approve {:status :approved :by manager}
    :request {:op :log-tour-record :tour-id "tour-1"
              :patch {:participant "Nakamura party" :check-in "2026-07-14" :nights 3}}}

   {:id "s2" :label "tour record logged at phase 3 -- governor clean, auto-eligible, no human"
    :phase 3
    :request {:op :log-tour-record :tour-id "tour-2"
              :patch {:participant "Ferreira party" :excursion "old-town walking loop"}}}

   {:id "s3" :label "tour operation scheduled at phase 3 -- governor clean, auto-eligible, no human"
    :phase 3
    :request {:op :schedule-tour-operation :tour-id "tour-2"
              :patch {:item "city guide assignment" :urgency "routine"}}}

   {:id "s4" :label "vendor settlement -- recomputed amount over the escalation threshold, manager approves"
    :phase 3 :approve {:status :approved :by manager}
    :request {:op :coordinate-vendor-settlement :tour-id "tour-1"
              :patch {:vendor "highlands-guide-co"}}}

   {:id "s5" :label "traveler-safety concern flagged -- ALWAYS escalates at any phase, manager approves"
    :phase 3 :approve {:status :approved :by manager}
    :request {:op :flag-traveler-safety-concern :tour-id "tour-2"
              :patch {:concern "loose handrail reported on the old-town stair section"}}}

   {:id "s6" :label "traveler-safety concern flagged -- manager REJECTS; a human decision, not a governor refusal"
    :phase 3 :approve {:status :rejected :by manager}
    :request {:op :flag-traveler-safety-concern :tour-id "tour-1"
              :patch {:concern "guide reports early-onset altitude sickness near basecamp"
                      :confidence 0.92}}}

   {:id "s7" :label "unknown tour id -- no store record to verify against"
    :phase 3
    :request {:op :log-tour-record :tour-id "tour-99" :patch {:participant "unknown"}}}

   {:id "s8" :label "registered but NOT verified tour -- guide certification still outstanding"
    :phase 3
    :request {:op :schedule-tour-operation :tour-id "tour-3"
              :patch {:item "kayak launch window"}}}

   {:id "s9" :label "advisor claims a direct actuation instead of a proposal"
    :phase 3 :advisor :direct-actuation
    :request {:op :schedule-tour-operation :tour-id "tour-2"
              :patch {:item "coastal transfer routing"}}}

   {:id "s10" :label "advisor drifts into finalizing a traveler-safety clearance -- permanently out of charter"
    :phase 3
    :request {:op :log-tour-record :tour-id "tour-1" :out-of-scope? true :patch {}}}

   {:id "s11" :label "op outside the closed four-op allowlist"
    :phase 3
    :request {:op :finalize-traveler-safety-clearance :tour-id "tour-1" :patch {}}}

   {:id "s12" :label "advisor understates a settlement to slip under the escalation threshold"
    :phase 3
    :request {:op :coordinate-vendor-settlement :tour-id "tour-2" :understate? true
              :patch {:vendor "charter-coach-co"}}}

   {:id "s13" :label "vendor settlement attempted at phase 1 -- op not yet enabled in this phase"
    :phase 1
    :request {:op :coordinate-vendor-settlement :tour-id "tour-1"
              :patch {:vendor "highlands-guide-co"}}}

   {:id "s14" :label "tour operation scheduled at phase 0 -- read-only rollout phase, no writes at all"
    :phase 0
    :request {:op :schedule-tour-operation :tour-id "tour-2"
              :patch {:item "sunset harbour extension"}}}])

(defn run-demo!
  "Runs every scenario against one freshly seeded store. Returns
  `{:db .. :runs [..]}` -- the store IS the evidence; the runs carry
  the per-scenario attribution the store cannot express."
  []
  (let [db      (store/seed-db)
        default (op/build db)
        direct  (op/build db {:advisor (direct-actuation-advisor)})]
    {:db   db
     :runs (mapv (fn [{:keys [advisor] :as s}]
                   (run-scenario! db (if (= :direct-actuation advisor) direct default) s))
                 scenarios)}))

;; ----------------------------- fact classification -----------------------------
;;
;; "the governor refused" and "the rollout gate held" are DIFFERENT
;; things and are never merged into one table here. Both are written to
;; the ledger as `:t :governor-hold`; only the former carries
;; violations.

(defn- hold-facts [ledger]
  (filterv #(= :governor-hold (:t %)) ledger))

(defn- hard-refusals
  "Holds that carry at least one real governor violation -- a refusal
  that never reaches a human."
  [ledger]
  (filterv #(seq (:violations %)) (hold-facts ledger)))

(defn- phase-holds
  "Holds with NO violations: the rollout phase gate stopped a proposal
  the governor itself had cleared."
  [ledger]
  (filterv #(empty? (:violations %)) (hold-facts ledger)))

(defn- rejection-facts [ledger]
  (filterv #(= :approval-rejected (:t %)) ledger))

(defn- commit-facts [ledger]
  (filterv #(= :committed (:t %)) ledger))

(defn- distinct-hard-reasons [ledger]
  (->> (hard-refusals ledger) (mapcat :basis) distinct sort vec))

;; ----------------------------- approver attribution -----------------------------

(def ^:private approver-keys
  "Every key an approver could plausibly land under. Probed against the
  real record; nothing is assumed."
  [:approved-by :approver :by :approved-by-id])

(defn- approver-in
  "Walks one committed record for an approver, returning
  `[path value]` or nil. Checks the record itself and its `:value` /
  `:payload` sub-maps."
  [record]
  (first
   (for [[label m] [["record" record]
                    ["record :value" (:value record)]
                    ["record :payload" (:payload record)]]
         k approver-keys
         :when (and (map? m) (contains? m k) (some? (get m k)))]
     [(str label " " k) (get m k)])))

(defn- granted-approver
  "The approver the RUN observed, taken from the graph's own
  `:approval-granted` audit fact -- the authoritative statement that a
  named human approved."
  [run]
  (some #(when (= :approval-granted (:t %)) (:by %)) (:audit run)))

(defn- approved-runs
  "Runs that a human actually approved AND that produced a committed
  record. Attribution is positional (see `run-scenario!`), never a
  join on [op tour-id]."
  [runs]
  (filterv #(and (granted-approver %) (seq (:records %))) runs))

(defn- approver-disclosure
  "DERIVED at render time: does this store actually keep the approver
  on the committed record, and does the append-only ledger keep it?

  Measured, not declared -- if the scaffold is later changed so the
  record drops (or the ledger gains) the approver, this page corrects
  itself on the next build instead of repeating a stale claim."
  [db runs]
  (let [approved (approved-runs runs)
        probes   (for [r approved
                       rec (:records r)]
                   {:run r :record rec :found (approver-in rec)})
        kept     (filterv :found probes)
        ledger-approver-facts
        (filterv (fn [f] (some #(and (contains? f %) (some? (get f %))) approver-keys))
                 (store/ledger db))]
    {:approved-runs   (count approved)
     :records-probed  (count probes)
     :records-keeping (count kept)
     :probes          (vec probes)
     :ledger-keeps?   (boolean (seq ledger-approver-facts))
     :type            (cond
                        (zero? (count probes))         :no-approval-path
                        (= (count kept) (count probes)) :record-keeps-approver
                        (zero? (count kept))            :record-drops-approver
                        :else                           :partial)}))

;; ----------------------------- settlement ground truth -----------------------------

(defn- settlement-truth
  "Per-tour settlement facts read straight out of the store's own filed
  rate plan and recomputed through `governor/recomputed-settlement` --
  the number the high-value gate and the mismatch gate both read.
  Never the advisor's claim."
  [db tour]
  (let [plan (:rate-plan tour)]
    {:tour-id     (:tour-id tour)
     :plan-id     (:rate/id plan)
     :per-unit    (:rate/base-amount plan)
     :currency    (:rate/currency plan)
     :units       (:billable-units tour)
     :recomputed  (governor/recomputed-settlement db (:tour-id tour))}))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc (kw v)) "</code>"))

(defn- yes-no [b klass-yes klass-no yes no]
  (if b
    (str "<span class=\"" klass-yes "\">" yes "</span>")
    (str "<span class=\"" klass-no "\">" no "</span>")))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title lead head-cells rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") head-cells)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows)
         (str (str/join "\n" rows) "\n")
         (str (row (str "<span class=\"muted\">nothing in this run</span>")) "\n"))
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn- sorted-kv
  "Deterministic rendering of a small payload map -- keys sorted by
  name so the page never depends on map iteration order."
  [m]
  (if (map? m)
    (str/join " &middot; "
              (for [[k v] (sort-by (comp str key) m)]
                (str "<code>" (esc (kw k)) "</code> " (esc v))))
    (esc m)))

;; --- tour directory ---

(defn- tour-rows [db]
  (for [t (store/all-tours db)]
    (row (code (:tour-id t))
         (esc (:name t))
         (yes-no (:registered? t) "ok" "critical" "registered" "not registered")
         (yes-no (:verified? t) "ok" "critical" "verified" "NOT verified")
         (str "<span class=\"num\">" (esc (:billable-units t)) "</span>"))))

;; --- settlement recompute ---

(defn- settlement-rows [db]
  (for [t (store/all-tours db)
        :let [s (settlement-truth db t)]]
    (row (code (:tour-id s))
         (code (:plan-id s))
         (str "<span class=\"num\">" (esc (:per-unit s)) "</span> "
              (esc (:currency s)) " minor units / unit")
         (str "<span class=\"num\">" (esc (:units s)) "</span>")
         (str "<span class=\"num\">" (esc (:recomputed s)) "</span>")
         (if (and (:recomputed s) (> (:recomputed s) governor/high-cost-threshold))
           "<span class=\"warn\">over threshold &middot; always escalates</span>"
           "<span class=\"ok\">under threshold</span>"))))

;; --- scenario dispositions ---

(defn- disposition-cell [{:keys [disposition verdict approval paused?]}]
  (let [hard? (:hard? verdict)]
    (cond
      hard?                       "<span class=\"critical\">HARD hold &middot; governor refused</span>"
      (and paused? (= :rejected (:status approval)))
                                  "<span class=\"err\">held &middot; human rejected</span>"
      (= :hold disposition)       "<span class=\"warn\">held &middot; rollout gate</span>"
      (and paused? (= :commit disposition))
                                  "<span class=\"ok\">approved &amp; committed</span>"
      (= :commit disposition)     "<span class=\"ok\">auto-committed</span>"
      (= :escalate disposition)   "<span class=\"warn\">awaiting a human</span>"
      :else                       (str "<span class=\"muted\">" (esc (kw disposition)) "</span>"))))

(defn- scenario-rows [runs]
  (for [r runs]
    (row (code (:id r))
         (esc (:label r))
         (code (:op r))
         (code (:tour-id r))
         (str "<span class=\"num\">" (esc (:phase r)) "</span> "
              (esc (get-in phase/phases [(:phase r) :label])))
         (disposition-cell r)
         (yes-no (:paused? r) "warn" "muted" "yes" "no"))))

;; --- hard refusals ---

(defn- violation-cell [violations]
  (str/join "<br>"
            (for [v violations]
              (str "<code>" (esc (kw (:rule v))) "</code> " (esc (:detail v))))))

(defn- hard-rows [ledger]
  (for [f (hard-refusals ledger)]
    (row (code (:op f))
         (code (:tour-id f))
         (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>") (:basis f)))
         (violation-cell (:violations f))
         (str "<span class=\"num\">" (esc (:confidence f)) "</span>"))))

;; --- phase-gate holds ---

(defn- phase-hold-rows [ledger]
  (for [f (phase-holds ledger)]
    (row (code (:op f))
         (code (:tour-id f))
         (str "<span class=\"num\">" (esc (:phase f)) "</span> "
              (esc (get-in phase/phases [(:phase f) :label])))
         (code (or (:phase-reason f) :none))
         (if (seq (:violations f))
           (violation-cell (:violations f))
           "<span class=\"muted\">none &mdash; the governor itself cleared this proposal</span>"))))

;; --- human rejections ---

(defn- rejection-rows [ledger]
  (for [f (rejection-facts ledger)]
    (row (code (:op f))
         (code (:tour-id f))
         (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>") (:basis f)))
         (str "<span class=\"num\">" (esc (:confidence f)) "</span>"))))

;; --- committed records + approver attribution ---

(defn- record-rows [db runs]
  (let [by-record (into {} (for [r runs, rec (:records r)] [rec r]))]
    (for [rec (store/coordination-log db)
          :let [r     (get by-record rec)
                found (approver-in rec)
                said  (some-> r granted-approver)]]
      (row (code (:op rec))
           (code (:tour-id rec))
           (sorted-kv (:value rec))
           (cond
             found (str "<span class=\"ok\">" (esc (second found)) "</span> "
                        "<span class=\"muted\">(" (esc (first found)) ")</span>")
             said  (str "<span class=\"err\">" (esc said) "</span> "
                        "<span class=\"muted\">&mdash; named in this run's approval trail, "
                        "but the committed record kept no approver key</span>")
             :else "<span class=\"muted\">no human in the loop &mdash; auto-committed</span>")))))

(defn- approver-note [{:keys [type approved-runs records-probed records-keeping ledger-keeps?]}]
  (str
   (case type
     :record-keeps-approver
     (str "Measured on this run: <strong>" records-keeping " of " records-probed
          "</strong> committed records produced by a human-approved path carry the approver, "
          "so this store does <span class=\"ok\">keep</span> approver attribution on the record.")
     :record-drops-approver
     (str "Measured on this run: <strong>0 of " records-probed
          "</strong> committed records carry the approver even though " approved-runs
          " run(s) were approved by a named human, so this store "
          "<span class=\"critical\">drops</span> approver attribution.")
     :partial
     (str "Measured on this run: <strong>" records-keeping " of " records-probed
          "</strong> committed records carry the approver &mdash; "
          "<span class=\"warn\">attribution is inconsistent</span> across ops.")
     :no-approval-path
     "Measured on this run: no committed record came from a human-approved path.")
   " The append-only audit ledger "
   (if ledger-keeps?
     "<span class=\"ok\">also records</span> who approved."
     (str "<span class=\"warn\">does NOT record</span> who approved &mdash; "
          "<code>:committed</code> facts carry the op, tour and basis but no approver, "
          "and the graph's <code>:approval-granted</code> fact is never appended to it. "
          "The name shown above is recovered from the committed record, not from the ledger."))
   " This paragraph is derived at build time by probing the real records for "
   (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>") approver-keys))
   "; it is not a fixed claim about this scaffold."))

;; --- op gate contract (read out of the governor/phase namespaces, not typed) ---

(defn- op-gate-rows []
  (let [auto (get-in phase/phases [3 :auto])]
    (for [o (sort-by kw governor/allowed-ops)]
      (row (code o)
           (yes-no (contains? (get-in phase/phases [3 :writes]) o)
                   "ok" "critical" "yes" "no")
           (yes-no (contains? auto o) "ok" "warn" "yes &mdash; when the governor is clean" "never at any phase")
           (if (contains? governor/always-escalate-ops o)
             "<span class=\"warn\">always escalates to a human</span>"
             "<span class=\"muted\">&mdash;</span>")))))

;; --- ledger ---

(defn- ledger-rows [ledger]
  (for [f ledger]
    (row (code (:t f))
         (code (:op f))
         (code (:tour-id f))
         (code (:disposition f))
         (if (seq (:basis f))
           (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>") (:basis f)))
           (if-let [pr (:phase-reason f)]
             (str "<code>" (esc (kw pr)) "</code> <span class=\"muted\">(phase gate)</span>")
             "<span class=\"muted\">&mdash;</span>")))))

(defn render
  "Renders the whole console from a store `db` that has already been
  driven by `run-demo!`, plus the per-scenario `runs`."
  [db runs]
  (let [ledger   (vec (store/ledger db))
        disc     (approver-disclosure db runs)
        hard     (hard-refusals ledger)
        reasons  (distinct-hard-reasons ledger)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-7912 &middot; tour operator operations console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Tour operator activities (ISIC 7912) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; traveler-safety clearance permanently out of charter</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>What this page is</h2>\n"
     "    <p>Generated at build time by <code>touroperatorops.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by running the real actor stack &mdash; "
     "<code>touroperatorops.operation</code> (a langgraph-clj StateGraph) "
     "&rarr; <code>touroperatorops.governor</code> &rarr; <code>touroperatorops.store</code> &mdash; "
     "over " (count runs) " scenarios against one freshly seeded tour directory. "
     "Every id, amount, hold reason and approver below is this run's actual output. "
     "The generator refuses to write this file unless the run produced at least one HARD "
     "governor refusal carrying a real violation.</p>\n"
     "    <p class=\"muted\">This run: <strong>" (count runs) "</strong> scenarios &middot; "
     "<strong>" (count ledger) "</strong> ledger facts &middot; "
     "<strong>" (count (commit-facts ledger)) "</strong> commits &middot; "
     "<strong>" (count hard) "</strong> HARD governor refusals over "
     "<strong>" (count reasons) "</strong> distinct rules ("
     (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>") reasons)) ") &middot; "
     "<strong>" (count (phase-holds ledger)) "</strong> rollout-gate holds &middot; "
     "<strong>" (count (rejection-facts ledger)) "</strong> human rejections. "
     "No timestamps: the page is byte-identical across reruns from the same seed.</p>\n"
     "  </section>\n"

     (section
      "Tour directory (SSoT)"
      (str "Seeded from <code>touroperatorops.store/demo-data</code>. A proposal for a tour that is "
           "not both <em>registered</em> and <em>verified</em> can never commit and can never even "
           "escalate &mdash; the governor re-derives this from the tour's own record, never from the "
           "proposal's claim.")
      ["Tour" "Name" "Registered" "Verified" "Billable units"]
      (tour-rows db))

     (section
      "Settlement ground truth (recomputed, never the advisor's claim)"
      (str "Recomputed by <code>touroperatorops.governor/recomputed-settlement</code> from each tour's "
           "own filed <code>kotoba.reservation</code> rate plan &times; its billable units. A "
           "<code>:coordinate-vendor-settlement</code> proposal whose claimed amount does not equal "
           "this number is a HARD hold (<code>:settlement-mismatch</code>); one whose amount cannot be "
           "recomputed at all is also a HARD hold, because a check that cannot be performed is a "
           "violation, not a pass. Threshold for mandatory human escalation: "
           "<code>" governor/high-cost-threshold "</code>.")
      ["Tour" "Filed rate plan" "Per unit" "Units" "Recomputed amount" "Escalation"]
      (settlement-rows db))

     (section
      "Op gate contract"
      (str "Read out of <code>touroperatorops.governor/allowed-ops</code>, "
           "<code>always-escalate-ops</code> and <code>touroperatorops.phase/phases</code> at build "
           "time, not transcribed. An op outside the closed allowlist is a scope violation by "
           "construction. <code>:flag-traveler-safety-concern</code> is absent from every phase's "
           "auto set, including phase 3 &mdash; a structural fact, not a rollout milestone.")
      ["Op" "Writable at phase 3" "Auto-commit eligible" "Standing posture"]
      (op-gate-rows))

     (section
      "Scenarios driven through the actor (this run)"
      (str "One row per graph run. <em>Paused for a human</em> means the graph actually stopped at "
           "<code>interrupt-before #{:request-approval}</code> and only resumed when an approval was "
           "supplied. A HARD hold never pauses: it never reaches a human at all.")
      ["#" "Scenario" "Op" "Tour" "Rollout phase" "Outcome" "Paused for a human"]
      (scenario-rows runs))

     (section
      "HARD governor refusals &mdash; never reach a human"
      (str "Permanent, un-overridable compliance blocks written by "
           "<code>touroperatorops.governor</code>. No human approval can release any of these; the "
           "graph routes straight to <code>:hold</code> without ever entering "
           "<code>:request-approval</code>. "
           "Distinct rules fired in this run: <strong>" (count reasons) "</strong>.")
      ["Op" "Tour" "Rule" "Detail" "Advisor confidence"]
      (hard-rows ledger))

     (section
      "Rollout-gate holds &mdash; a different thing from a governor refusal"
      (str "These proposals were <em>clean</em>: the governor raised no violation. They were stopped "
           "by <code>touroperatorops.phase/gate</code> because the op is not yet enabled in that "
           "rollout phase. They are written to the ledger with the same <code>:governor-hold</code> "
           "fact type but an EMPTY violations vector, which is why this console counts them "
           "separately and why the build-time invariant does not accept them as evidence of a "
           "compliance refusal.")
      ["Op" "Tour" "Rollout phase" "Phase reason" "Governor violations"]
      (phase-hold-rows ledger))

     (section
      "Human rejections &mdash; a person looked and said no"
      (str "An escalation that reached a human who declined it. Distinct from both of the tables "
           "above: the governor cleared the proposal and the rollout gate allowed it to be asked, "
           "and a named operator still refused.")
      ["Op" "Tour" "Basis" "Advisor confidence"]
      (rejection-rows ledger))

     (section
      "Committed coordination records &amp; approver attribution"
      (approver-note disc)
      ["Op" "Tour" "Committed value" "Approved by"]
      (record-rows db runs))

     (section
      "Append-only audit ledger (this run)"
      (str "Every decision fact this scenario produced, in order. "
           "<code>:committed</code> writes the SSoT; <code>:governor-hold</code> and "
           "<code>:approval-rejected</code> write nothing but themselves.")
      ["Fact" "Op" "Tour" "Disposition" "Basis"]
      (ledger-rows ledger))

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-7912 &middot; tour operator operations-coordination actor. "
     "Regenerate with <code>clojure -M:dev:render-html</code>. "
     "This actor never finalizes a traveler-safety clearance, never issues a resumption order for a "
     "held excursion and never overrides a traveler-safety authority &mdash; those are permanent, "
     "un-overridable exclusions in <code>touroperatorops.governor</code>, not unfinished work.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn- assert-hard-holds!
  "Build-time invariant, two-stage on purpose.

  Stage 1: the run must have produced holds at all.
  Stage 2: at least one of them must carry a NON-EMPTY violation --
  a rollout-phase hold (`:phase-disabled` / `:phase-approval`) is
  recorded as a `:governor-hold` fact with an empty `:violations`
  vector and would satisfy a naive count while proving nothing about
  the compliance layer.

  Throws rather than writing a page that quietly shows no refusal."
  [ledger]
  (let [holds (hold-facts ledger)
        hard  (hard-refusals ledger)]
    (when (empty? holds)
      (throw (ex-info "render-html refuses to write: the run produced ZERO holds of any kind"
                      {:ledger-facts (count ledger) :holds 0})))
    (when (empty? hard)
      (throw (ex-info (str "render-html refuses to write: " (count holds)
                           " hold(s) fired but NONE carried a governor violation"
                           " -- every one of them was a rollout-phase gate, which proves"
                           " nothing about the compliance layer")
                      {:holds (count holds)
                       :hard-refusals 0
                       :phase-holds (mapv :phase-reason (phase-holds ledger))})))
    {:holds (count holds)
     :hard (count hard)
     :reasons (distinct-hard-reasons ledger)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs]} (run-demo!)
        ledger (vec (store/ledger db))
        {:keys [holds hard reasons]} (assert-hard-holds! ledger)
        disc (approver-disclosure db runs)]
    (spit out (render db runs))
    (println "wrote" out)
    (println "  scenarios      :" (count runs))
    (println "  ledger facts   :" (count ledger))
    (println "  commits        :" (count (commit-facts ledger)))
    (println "  holds          :" holds "(" hard "HARD governor refusals,"
             (count (phase-holds ledger)) "rollout-gate )")
    (println "  hard reasons   :" (str/join ", " (map kw reasons)))
    (println "  rejections     :" (count (rejection-facts ledger)))
    (println "  approver type  :" (:type disc)
             (str "(" (:records-keeping disc) "/" (:records-probed disc)
                  " approved records keep it; ledger keeps it: " (:ledger-keeps? disc) ")"))))
