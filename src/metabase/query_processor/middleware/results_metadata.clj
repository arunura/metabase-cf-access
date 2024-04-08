(ns metabase.query-processor.middleware.results-metadata
  "Middleware that stores metadata about results column types after running a query for a Card,
   and returns that metadata (which can be passed *back* to the backend when saving a Card) as well
   as a checksum in the API response."
  (:require
   [metabase.driver :as driver]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.query-processor.reducible :as qp.reducible]
   [metabase.query-processor.schema :as qp.schema]
   [metabase.query-processor.store :as qp.store]
   [metabase.sync.analyze.query-results :as qr]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   #_{:clj-kondo/ignore [:discouraged-namespace]}
   [toucan2.core :as t2]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                   Middleware                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO -
;;
;; 1. Is there some way we could avoid doing this every single time a Card is ran? Perhaps by passing the current Card
;;    metadata as part of the query context so we can compare for changes
;;
;; 2. Consider whether the actual save operation should be async as with
;;    [[metabase.query-processor.middleware.process-userland-query]]
(defn- record-metadata! [{{:keys [card-id]} :info, :as query} metadata]
  (try
    ;; At the very least we can skip the Extra DB call to update this Card's metadata results
    ;; if its DB doesn't support nested queries in the first place
    (when (and metadata
               driver/*driver*
               (driver/database-supports? driver/*driver* :nested-queries (lib.metadata/database (qp.store/metadata-provider)))
               card-id
               ;; don't want to update metadata when we use a Card as a source Card.
               (not (:qp/source-card-id query)))
      (t2/update! :model/Card card-id {:result_metadata metadata}))
    ;; if for some reason we weren't able to record results metadata for this query then just proceed as normal
    ;; rather than failing the entire query
    (catch Throwable e
      (log/error e "Error recording results metadata for query"))))

(defn- merge-final-column-metadata
  "Because insights are generated by reducing functions, they start working before the entire query metadata is in its
  final form. Some columns come back without type information, and thus get an initial base type of `:type/*` (unknown
  type); in this case, the `annotate` middleware scans the first few values and infers a base type, adding that
  information to the column metadata in the final result.

  This function merges inferred column base types added by `annotate` into the metadata generated by `insights`."
  [final-col-metadata insights-col-metadata]
  ;; the two metadatas will both be in order that matches the column order of the results
  (mapv
   (fn [{final-base-type :base_type, :as final-col} {our-base-type :base_type, :as insights-col}]
     (merge
      (select-keys final-col [:id :description :display_name :semantic_type :fk_target_field_id
                              :settings :field_ref :name :base_type :effective_type
                              :coercion_strategy :visibility_type])
      insights-col
      (when (= our-base-type :type/*)
        {:base_type final-base-type})))
   final-col-metadata
   insights-col-metadata))

(mu/defn ^:private insights-xform :- fn?
  [orig-metadata :- [:maybe :map]
   record!       :- ifn?
   rf            :- ifn?]
  (qp.reducible/combine-additional-reducing-fns
   rf
   [(qr/insights-rf orig-metadata)]
   (fn combine [result {:keys [metadata insights]}]
     (let [metadata (merge-final-column-metadata (-> result :data :cols) metadata)]
       (record! metadata)
       (rf (cond-> result
             (map? result)
             (update :data
                     assoc
                     :results_metadata {:columns metadata}
                     :insights         insights)))))))

(mu/defn record-and-return-metadata! :- ::qp.schema/rff
  "Post-processing middleware that records metadata about the columns returned when running the query. Returns an rff."
  [{{:keys [skip-results-metadata?]} :middleware, :as query} :- ::qp.schema/query
   rff                                                       :- ::qp.schema/rff]
  (if skip-results-metadata?
    rff
    (let [record! (partial record-metadata! query)]
      (fn record-and-return-metadata!-rff* [metadata]
        (insights-xform metadata record! (rff metadata))))))
