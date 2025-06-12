(ns stm.RetryEx)

(defn retry-ex [] (ex-info "Retry Ex" {::retry-ex true}))
(defn retry-ex? [e] (boolean (-> e ex-data ::retry-ex)))
