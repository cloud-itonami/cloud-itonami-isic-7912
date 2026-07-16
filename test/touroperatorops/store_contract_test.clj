(ns touroperatorops.store-contract-test
  "Contract tests for `touroperatorops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [touroperatorops.store :as store]))

(deftest mem-store-tour-lookup
  (testing "MemStore can store and retrieve tours by ID (string keys)"
    (let [tours {"t1" {:tour-id "t1" :name "Tour 1" :registered? true :verified? true}}
          s (store/mem-store tours)]
      (is (some? (store/tour s "t1")))
      (is (nil? (store/tour s "t99"))))))

(deftest mem-store-all-tours
  (testing "MemStore returns all tours in sorted order"
    (let [tours {"t2" {:tour-id "t2" :name "Tour 2"}
                 "t1" {:tour-id "t1" :name "Tour 1"}
                 "t3" {:tour-id "t3" :name "Tour 3"}}
          s (store/mem-store tours)
          all-t (store/all-tours s)]
      (is (= 3 (count all-t)))
      (is (= "t1" (:tour-id (first all-t))))
      (is (= "t3" (:tour-id (last all-t)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-tour-record :tour-id "t1" :value {:participant "test"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-tours
  (testing "MemStore with-tours replaces the tour directory"
    (let [s (store/mem-store {})
          new-tours {"t1" {:tour-id "t1" :name "Tour 1"}}]
      (is (= 0 (count (store/all-tours s))))
      (store/with-tours s new-tours)
      (is (= 1 (count (store/all-tours s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo tours"
    (let [s (store/seed-db)]
      (is (> (count (store/all-tours s)) 0))
      (is (some? (store/tour s "tour-1")))
      (is (some? (store/tour s "tour-2")))
      (is (some? (store/tour s "tour-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for tour-id"
    (let [demo (store/demo-data)
          tours (:tours demo)]
      (doseq [[k v] tours]
        (is (string? k) "keys must be strings")
        (is (string? (:tour-id v)) "tour-id must be string")
        (is (= k (:tour-id v)) "key must match tour-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
