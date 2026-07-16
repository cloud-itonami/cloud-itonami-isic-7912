(ns touroperatorops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean tour-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a tour-operation-scheduling request and a
  vendor-settlement coordination request (both auto-commit clean at
  phase 3), then a traveler-safety-concern flag (ALWAYS escalates, at
  any phase -- approve, then commit), then HARD-hold scenarios: an
  unregistered tour, a tour registered but not yet verified, a
  proposal whose own `:effect` is not `:propose`, and a proposal that
  has drifted into the permanently-excluded traveler-safety-clearance-
  finalization scope."
  (:require [langgraph.graph :as g]
            [touroperatorops.advisor :as advisor]
            [touroperatorops.store :as store]
            [touroperatorops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "tour-operations-manager-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        manager-phase-1 {:actor-id "mgr-1" :actor-role :tour-operations-manager :phase 1}
        manager-phase-3 {:actor-id "mgr-1" :actor-role :tour-operations-manager :phase 3}
        actor (op/build db)]

    (println "== log-tour-record tour-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-tour-record :tour-id "tour-1"
                                  :patch {:participant "Nakamura party" :check-in "2026-07-14" :nights 3}} manager-phase-1)]
      (println r)
      (println "-- human tour-operations manager approves --")
      (println (approve! actor "t1")))

    (println "\n== log-tour-record tour-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-tour-record :tour-id "tour-1"
                                  :patch {:participant "Nakamura party" :check-out "2026-07-17"}} manager-phase-3))

    (println "\n== schedule-tour-operation tour-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-tour-operation :tour-id "tour-1"
                                  :patch {:item "highlands trek guide assignment" :urgency "routine"}} manager-phase-3))

    (println "\n== coordinate-vendor-settlement tour-1 (phase 3, clean, under threshold -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-vendor-settlement :tour-id "tour-1"
                                  :patch {:vendor "highlands-guide-co" :estimated-amount 400}} manager-phase-3))

    (println "\n== coordinate-vendor-settlement tour-1 (phase 3, over amount threshold -- ALWAYS escalates) ==")
    (let [r (exec-op actor "t4b" {:op :coordinate-vendor-settlement :tour-id "tour-1"
                                  :patch {:vendor "bulk-charter-transfer-co" :estimated-amount 2500}} manager-phase-3)]
      (println r)
      (println "-- human tour-operations manager approves --")
      (println (approve! actor "t4b")))

    (println "\n== flag-traveler-safety-concern tour-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-traveler-safety-concern :tour-id "tour-1"
                                 :patch {:concern "guide reports early-onset altitude sickness in one participant near basecamp" :confidence 0.92}} manager-phase-3)]
      (println r)
      (println "-- human tour-operations manager reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-tour-record tour-99 (unregistered tour -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-tour-record :tour-id "tour-99"
                                  :patch {:participant "unknown"}} manager-phase-3))

    (println "\n== log-tour-record tour-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-tour-record :tour-id "tour-3"
                                  :patch {:participant "unknown"}} manager-phase-3))

    (println "\n== schedule-tour-operation tour-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-tour-operation :tour-id "tour-1"
                                           :patch {:item "coastal transfer routing"}} manager-phase-3)))

    (println "\n== log-tour-record tour-1, advisor drifts into traveler-safety-clearance-finalization scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-tour-record :tour-id "tour-1"
                                   :out-of-scope? true
                                   :patch {}} manager-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
