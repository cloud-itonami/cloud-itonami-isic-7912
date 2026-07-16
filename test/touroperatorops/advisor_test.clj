(ns touroperatorops.advisor-test
  "Unit tests of `touroperatorops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [touroperatorops.advisor :as adv]
            [touroperatorops.store :as store]))

(def db (store/seed-db))

(deftest propose-tour-record-shape
  (testing "tour-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-tour-record
                           :tour-id "tour-1"
                           :patch {:participant "Nakamura party" :check-in "2026-07-14"}})]
      (is (= :log-tour-record (:op p)))
      (is (= "tour-1" (:tour-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :tour-id)))))

(deftest propose-tour-operation-schedule-shape
  (testing "tour-operation-schedule proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-tour-operation
                           :tour-id "tour-2"
                           :patch {:item "guide assignment" :urgency "routine"}})]
      (is (= :schedule-tour-operation (:op p)))
      (is (= "tour-2" (:tour-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-vendor-settlement-shape
  (testing "vendor-settlement proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-vendor-settlement
                           :tour-id "tour-1"
                           :patch {:vendor "highlands-guide-co" :estimated-amount 400}})]
      (is (= :coordinate-vendor-settlement (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-traveler-safety-concern-shape
  (testing "traveler-safety-concern proposal always proposes, never actuates"
    (let [p (adv/infer db {:op :flag-traveler-safety-concern
                           :tour-id "tour-1"
                           :patch {:concern "altitude sickness reported near basecamp"}})]
      (is (= :flag-traveler-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-tour-record :schedule-tour-operation
                :coordinate-vendor-settlement :flag-traveler-safety-concern]]
      (let [p (adv/infer db {:op op :tour-id "tour-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-tour-record :schedule-tour-operation
                :coordinate-vendor-settlement :flag-traveler-safety-concern]]
      (let [p (adv/infer db {:op op :tour-id "tour-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest unknown-op-returns-empty-proposal
  (testing "an op outside the four-op set produces an unrecognized (empty) proposal shape, left for the governor to reject"
    (let [p (adv/infer db {:op :not-a-real-op :tour-id "tour-1" :patch {}})]
      (is (empty? p)))))
