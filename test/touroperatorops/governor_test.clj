(ns touroperatorops.governor-test
  "Pure unit tests of `touroperatorops.governor/check` against
  hand-built proposals -- the fast, focused complement to
  `governor-contract-test`'s full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [touroperatorops.governor :as gov]
            [touroperatorops.advisor :as adv]
            [touroperatorops.store :as store]))

(def tour-1 {:tour-id "tour-1" :name "3-day guided highlands trek" :registered? true :verified? true})
(def tour-3 {:tour-id "tour-3" :name "Coastal kayak excursion, awaiting verification" :registered? true :verified? false})

(defn- clean-proposal [op tour-id]
  {:op op :tour-id tour-id :summary "s" :rationale "routine tour-operator operations coordination"
   :cites [tour-id] :effect :propose :value {} :confidence 0.85})

(deftest tour-unregistered-is-hard
  (testing "no tour record at all -> HARD hold"
    (let [s (store/mem-store {"tour-1" tour-1})
          verdict (gov/check {} nil (clean-proposal :log-tour-record "unknown-tour") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:tour-unverified} (map :rule (:violations verdict)))))))

(deftest tour-unverified-is-hard
  (testing "tour registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"tour-3" tour-3})
          verdict (gov/check {} nil (clean-proposal :log-tour-record "tour-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:tour-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"tour-1" tour-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-tour-operation "tour-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"tour-1" tour-1})
          verdict (gov/check {} nil (clean-proposal :cancel-tour "tour-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest finalize-safety-clearance-is-hard-and-permanent
  (testing "a proposal that claims to directly finalize a traveler-safety-clearance decision is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"tour-1" tour-1})
          poisoned (assoc (clean-proposal :flag-traveler-safety-concern "tour-1")
                          :rationale "finalize the excursion-safety clearance for the highlands trek immediately"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest authorize-excursion-resumption-content-is-hard
  (testing "a proposal that claims to directly authorize excursion resumption is HARD-blocked, same as clearance finalization"
    (let [s (store/mem-store {"tour-1" tour-1})
          poisoned (assoc (clean-proposal :flag-traveler-safety-concern "tour-1")
                          :rationale "authorize the excursion to resume despite the reported hazard"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest clear-excursion-as-safe-content-is-hard
  (testing "a proposal that claims to directly clear an excursion as safe is HARD-blocked"
    (let [s (store/mem-store {"tour-1" tour-1})
          poisoned (assoc (clean-proposal :flag-traveler-safety-concern "tour-1")
                          :summary "clear the excursion as safe for loop A departure")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest override-traveler-safety-authority-content-is-hard
  (testing "a proposal that claims to override a traveler-safety authority's decision is HARD-blocked"
    (let [s (store/mem-store {"tour-1" tour-1})
          poisoned (assoc (clean-proposal :schedule-tour-operation "tour-1")
                          :value {:decision "override traveler safety authority hold order for the kayak excursion"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-traveler-safety-concern-is-not-scope-excluded
  (testing "flagging observed hazard/incident/medical concerns (including a mention of a held excursion as raw observation, not a finalized clearance) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"tour-1" tour-1})
          concern (assoc (clean-proposal :flag-traveler-safety-concern "tour-1")
                         :value {:concern "altitude sickness observed near basecamp; the guide has held the excursion pending medical review"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (hazard, held excursion) is exactly what this op exists to surface"))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own DEFAULT rationale/summary text for every allowed op, against a verified tour, never trips the governor's scope-exclusion check -- a known self-tripping bug pattern in this actor family (bare-noun scope terms accidentally matching an advisor's own happy-path disclaimer text)"
    (let [s (store/mem-store {"tour-1" tour-1})]
      (doseq [op [:log-tour-record :schedule-tour-operation
                  :coordinate-vendor-settlement :flag-traveler-safety-concern]]
        (let [proposal (adv/infer nil {:op op :tour-id "tour-1"
                                        :patch {:concern "routine observation" :estimated-amount 100}})
              verdict (gov/check {:tour-id "tour-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default mock-advisor proposal for op " op " must never self-trip scope-exclusion"))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "default mock-advisor proposal for op " op " must always be within the allowlist")))))))

(deftest traveler-safety-concern-always-escalates-even-when-otherwise-clean
  (testing ":flag-traveler-safety-concern is always high-stakes/escalate, regardless of confidence"
    (let [s (store/mem-store {"tour-1" tour-1})
          concern (assoc (clean-proposal :flag-traveler-safety-concern "tour-1") :confidence 0.99)
          verdict (gov/check {} nil concern s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-vendor-settlement-always-escalates
  (testing "a coordinate-vendor-settlement proposal above the cost threshold escalates even when governor-clean and high confidence"
    (let [s (store/mem-store {"tour-1" tour-1})
          expensive (assoc (clean-proposal :coordinate-vendor-settlement "tour-1")
                           :value {:estimated-amount 2500} :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-vendor-settlement-does-not-force-escalation
  (testing "a coordinate-vendor-settlement proposal under the cost threshold is not forced to escalate on cost grounds alone"
    (let [s (store/mem-store {"tour-1" tour-1})
          routine (assoc (clean-proposal :coordinate-vendor-settlement "tour-1")
                        :value {:estimated-amount 400} :confidence 0.9)
          verdict (gov/check {} nil routine s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict))))))

(deftest low-confidence-escalates
  (testing "confidence below the floor escalates any otherwise-clean proposal"
    (let [s (store/mem-store {"tour-1" tour-1})
          uncertain (assoc (clean-proposal :log-tour-record "tour-1") :confidence 0.4)
          verdict (gov/check {} nil uncertain s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest clean-high-confidence-proposal-is-ok
  (testing "a clean, high-confidence, low-cost, registered-tour proposal is fully ok"
    (let [s (store/mem-store {"tour-1" tour-1})
          clean (clean-proposal :log-tour-record "tour-1")
          verdict (gov/check {} nil clean s)]
      (is (true? (:ok? verdict)))
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict))))))
