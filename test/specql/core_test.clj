(ns specql.core-test
  (:require [specql.core :refer [define-tables fetch insert!]]
            [specql.op :as op]
            [clojure.test :as t :refer [deftest is testing]]
            [specql.embedded-postgres :refer [with-db datasource db]]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.spec :as s]))


(t/use-fixtures :each with-db)

(def define-db (datasource))

(define-tables define-db
  ["address" :address/address]
  ["employee" :employee/employees]
  ["company" :company/companies]
  ["department" :department/departments])

(deftest tables-have-been-created
  ;; If test data has been inserted, we know that all tables were create
  (is (= [{:companies 2}] (jdbc/query db ["SELECT COUNT(id) AS companies FROM company"]))))

(deftest simple-fetch
  (is (= (fetch db
                ;; Select from :employee/employees table
                :employee/employees

                ;; Return the following columns
                #{:employee/id :employee/name :employee/title}

                ;; Where clauses to match
                {:employee/id 1})

         (list #:employee{:id 1
                          :name "Wile E. Coyote"
                          :title "Super genious"}))))

(deftest query-unknown-table-or-column
  (is (thrown? AssertionError
               (specql.core/fetch db
                                  :foo/bar
                                  #{:foo/baz :foo/quux}
                                  {})))
  (is (thrown? AssertionError
               (specql.core/fetch db
                                  :employee/employees
                                  #{:employee/foo}
                                  {}))))

(deftest query-with-invalid-parameter
  (let [x "foo"]
    (is (thrown-with-msg?
         AssertionError #"val: \"foo\" fails spec: :employee/id"
         (fetch db :employee/employees
                #{:employee/name}
                {:employee/id x})))))

(deftest composite-type-unpacking
  (is (= #:employee{:name "Wile E. Coyote"
                    :address #:address{:street "Desert avenue 1" :postal-code "31173" :country "US"}}
         (first
          (fetch db :employee/employees
                 #{:employee/name :employee/address}
                 {:employee/id 1})))))

(deftest inserting
  (testing "count before newly inserted rows"
    (is (= 3 (count (fetch db :employee/employees
                           #{:employee/id}
                           {})))))

  (testing "inserting two new employees"
    (is (= 4 (:employee/id (insert! db :employee/employees
                                    {:employee/name "Foo"
                                     :employee/employment-started (java.util.Date.)}))))
    (is (= 5 (:employee/id (insert! db :employee/employees
                                    {:employee/name "Bar"
                                     :employee/employment-started (java.util.Date.)})))))

  (testing "trying to insert invalid data"
    ;; Name field is NOT NULL, so insertion should fail
    (is (thrown-with-msg?
         AssertionError #"contains\? % :employee/name"
         (insert! db :employee/employees
                  {:employee/title "I have no name!"
                   :employee/employment-started (java.util.Date.)})))

    (is (thrown-with-msg?
         AssertionError #"val: 42 fails spec"
         (insert! db :employee/employees
                  {:employee/name "Foo"
                   :employee/employment-started (java.util.Date.)
                   :employee/title 42}))))

  (testing "querying for the new employees"
    (is (= #:employee{:id 4 :name "Foo"}
           (first (fetch db :employee/employees
                         #{:employee/id :employee/name}
                         {:employee/id 4})))))

  (testing "insert record with composite value"
    (let [addr #:address {:street "somestreet 123"
                          :postal-code "90123"
                          :country "US"}]
      (is (= 6 (:employee/id
                (insert! db :employee/employees
                         {:employee/name "Quux"
                          :employee/employment-started (java.util.Date.)
                          :employee/address addr}))))

      ;; Read the address back and verify it was properly saved
      (is (= addr (:employee/address
                   (first
                    (fetch db :employee/employees
                           #{:employee/address}
                           {:employee/id 6})))))

      ;; Check that validation failures in composite types are detected
      (is (thrown-with-msg?
           AssertionError #"val: 666 fails"
           (insert! db :employee/employees
                    {:employee/name "Frob"
                     :employee/address (assoc addr
                                              :address/postal-code 666)})))))

  (testing "count after insertions"
    (is (= 6 (count (fetch db :employee/employees
                           #{:employee/id}
                           {}))))))

(deftest query-with-composite-value
  (testing "query companies by visiting address country"
    (is (= 2 (count
              (fetch db :company/companies
                     #{:company/name :company/visiting-address}
                     {:company/visiting-address {:address/country "FI"}}))))))

(deftest query-operators
  ;; There are no companies whose visiting address is not in Finland
  (is (empty?
       (fetch db :company/companies
              #{:company/id}
              {:company/visiting-address {:address/country (op/not= "FI")}})))

  (is (= 1
         (count (fetch db :employee/employees
                       #{:employee/id}
                       {:employee/employment-started (op/< #inst "1997-08-04T02:14:30.798-04:00")}))))

  (is (= "Wile E. Coyote"
         (:employee/name
          (first
           (fetch db :employee/employees
                  #{:employee/name}
                  {:employee/name (op/like "%yo%")})))))

  (is (= #{"Max Syöttöpaine" "Foo Barsky"}
         (into #{} (map :employee/name)
               (fetch db :employee/employees
                      #{:employee/name}
                      {:employee/name (op/not (op/like "%yo%"))}))))

  (is (= #{"Foo Barsky" "Wile E. Coyote"}
         (into #{} (map :employee/name)
               (fetch db :employee/employees
                      #{:employee/name}
                      {:employee/name (op/or (op/like "%yo%")
                                             (op/like "%sky"))}))))

  (is (= #{"Max Syöttöpaine"}
         (into #{} (map :employee/name)
               (fetch db :employee/employees
                      #{:employee/name}
                      {:employee/name (op/and (op/like "%a%")
                                              (op/like "%x%"))})))))
