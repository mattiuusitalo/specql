(ns specql.impl.sproc
  "Macros and support code for defining stored procedures as functions"
  (:require [specql.impl.catalog :as catalog]
            [specql.impl.registry :as registry]
            [specql.impl.composite :as composite]
            [specql.impl.util :refer :all]
            [clojure.spec.alpha :as s]
            [specql.data-types :refer [db-type?]]
            [specql.transform :as xf]
            [clojure.string :as str]
            [clojure.java.jdbc :as jdbc]))

(defn- return-handler [tir tir-sym {:keys [type category] :as returns} kw]
  (cond

    ;; An array,
    (= "A" category)
    `(fn [row#]
       (let [s# (str (val (first row#)))]
         (when-not (str/blank? s#)
           (composite/parse ~tir-sym ~returns s#))))

    ;; A db specified type, take the value as is
    (db-type? kw)
    `(comp val first)

    ;; Map the column names to namespaced keywords
    :default
    (let [cols (:columns (get tir kw))
          row (gensym "row")
          r (gensym "result")]
      `(fn [~'row]
         (as-> {} ~'r
           ~@(for [[kw {name :name xf ::xf/transform :as col}] cols
                   :let [resultset-kw (keyword name)]]
               (if xf
                 `(assoc ~'r ~kw (xf/from-sql ~xf (~resultset-kw ~'row)))
                 `(assoc ~'r ~kw (~resultset-kw ~'row)))))))))

(defn- sproc [name sproc-info]
  (let [db-sym (gensym "db")
        tir @registry/table-info-registry
        sproc-args (:args sproc-info)
        arg-type-keywords (mapv #(registry/type-keyword-by-name tir (:type %)) sproc-args)
        args (mapv (comp symbol :name) sproc-args)
        tir-sym (gensym "tir")
        returns (:returns sproc-info)
        return-type-keyword (registry/type-keyword-by-name tir (:type returns))]
    (assert (every? some? arg-type-keywords) "Unknown argument type (FIXME: better error)")
    (assert (some? return-type-keyword)
            (str "Unknown return type: " (:type returns) ". Call define-tables to define the type!"))
    `(defn ~name
       ~(or (:comment sproc-info) "")
       [~db-sym ~@args]
       (let [~tir-sym @registry/table-info-registry]
         ;; Assert all argument values are valid
         ~@(map (fn [arg kw {category :category}]
                  (if (= "A" category)
                    `(assert-spec (s/coll-of ~kw) ~arg)
                    `(assert-spec ~kw ~arg)))
                args arg-type-keywords sproc-args)

         (let [sql# (str "SELECT * FROM " (q ~(:name sproc-info)) "("
                         (str/join ","
                                   [~@(map (fn [{:keys [type category element-type]}]
                                             (str "?::" type)) sproc-args)]) ")")
               args# [~@(map (fn [kw arg arg-type]
                               (if (db-type? kw)
                                 ;; A db defined type, pass as is
                                 arg

                                 ;; Serialize as composite
                                 `(let [type# (get ~tir-sym ~kw)]
                                    (composite/stringify
                                     ~tir-sym (merge ~arg-type
                                                     (select-keys type# #{::xf/transform}))
                                     (transform-value-to-sql type# ~arg) true))))
                             arg-type-keywords args sproc-args)]
               sql-and-args# (into [sql#] args#)]
           ;;(println "SQL: " (pr-str sql-and-args#))
           (jdbc/with-db-transaction [db# ~db-sym]
             (~(if (:single? returns) first doall)
              (map ~(return-handler tir tir-sym returns return-type-keyword)
                   (jdbc/query db# sql-and-args#)))))))))

(defmacro define-stored-procedures [db & procedures]
  (with-open [si (catalog/->schema-info (eval db))]
    `(do ~@(doall
            (for [[fn-name options] procedures
                  :let [sp-name (or (:specql.core/name options)
                                    (name fn-name))
                        info (catalog/sproc-info si
                                                 (or (:specql.core/name options)
                                                     (name fn-name)))]]
              (do
                (assert info (str "No stored procedure found for name '" sp-name "'."
                                  (when-not (:specql.core/name options)
                                    " Hint: add :specql.core/name option if the procedure name is different from the var name.")))
                  ;; TODO:
                  ;; - resolve all arg specs to keywords (verify that they have been defined
                  ;; before the sproc is being defined)
                (sproc fn-name info)))))))
