;; version 3 - MVCC with commute and ensure
;; Improvements over v2:
;; - proper support for commute and ensure
;;
;; Limitations:
;; - a single global commit-lock for all transactions
;;   (= severe bottleneck, but makes it easy to validate and commit)

(ns stm.v3-mvcc-commute
  (:require [stm.RetryEx :refer [retry-ex retry-ex?]]
            [clojure.set :refer [union]]))

;; === MC-STM internals ===

; a thread-local var that holds the current transaction executed by this thread
; if the thread does not execute a transaction, this is set to nil
(def ^:dynamic *current-transaction* nil)

; global counter, incremented every time a transaction commits successfully
(def GLOBAL_WRITE_POINT (atom 0))

; maximum amount of older values stored, per mc-ref
(def MAX_HISTORY 10)

(defn make-transaction
  "create and return a new transaction data structure"
  []
  {:read-point @GLOBAL_WRITE_POINT,
   :in-tx-values (atom {}), ; map: ref -> any value
   :written-refs (atom #{}), ; set of written-to refs
   :commutes (atom {}), ; map: ref -> seq of commute-fns
   :ensures (atom #{}) }) ; set of ensure-d refs

(defn find-entry-before-or-on
  "returns an entry in history-chain whose write-pt <= read-pt,
   or nil if no such entry exists"
  [history-chain read-pt]
  (some (fn [pair]
          (if (and pair (<= (:write-point pair) read-pt))
            pair)) history-chain))

; history lists of mc-refs are ordered youngest to eldest
(def most-recent first)

(defn tx-retry []
  "immediately abort and retry the current transaction"
  (throw (retry-ex)))

(defn tx-read
  "read the value of ref inside transaction tx"
  [tx mc-ref]
  (let [in-tx-values (:in-tx-values tx)]
    (if (contains? @in-tx-values mc-ref)
      (@in-tx-values mc-ref) ; return the in-tx-value
      ; search the history chain for entry with write-point <= tx's read-point
      (let [ref-entry (find-entry-before-or-on @mc-ref (:read-point tx))]
        (if (not ref-entry)
          ; if such an entry was not found, retry
          (tx-retry))
        (let [in-tx-value (:value ref-entry)]
          (swap! in-tx-values assoc mc-ref in-tx-value) ; cache the value
          in-tx-value))))) ; save and return the ref's value

(defn tx-write
  "write val to ref inside transaction tx"
  [tx ref val]
  ; can't set a ref after it has already been commuted
  (if (contains? @(:commutes tx) ref)
    (throw (IllegalStateException. "can't set after commute on " ref)))
  (swap! (:in-tx-values tx) assoc ref val)
  (swap! (:written-refs tx) conj ref)
  val)

(defn tx-ensure
  "ensure ref inside transaction tx"
  [tx ref]
  ; mark this ref as being ensure-d
  (swap! (:ensures tx) conj ref))

(defn tx-commute
  "commute ref inside transaction tx"
  [tx ref fun args]
  ; apply fun to the in-tx-value or
  ; the most recent value if not read/written before
  (let [in-tx-values @(:in-tx-values tx)
        res (apply fun (if (contains? in-tx-values ref)
                         (in-tx-values ref) 
                         (:value (most-recent @ref))) args)]
    ; retain the result as an in-transaction-value
    (swap! (:in-tx-values tx) assoc ref res)
    ; mark the ref as being commuted,
    ; storing fun and args because it will be re-executed at commit time
    (swap! (:commutes tx) (fn [commutes]
                            (assoc commutes ref
                              (cons (fn [val] (apply fun val args))
                                    (commutes ref)))))
    res))

; a single global lock for all transactions to acquire on commit
; we use the monitor of a fresh empty Java object
; all threads share the same root-binding, so will acquire the same lock
(def COMMIT_LOCK (Object.))

(defn tx-commit
  "returns normally if tx committed successfully, throws RetryEx otherwise"
  [tx]
  (let [written-refs @(:written-refs tx)
        ensured-refs @(:ensures tx)
        commuted-refs @(:commutes tx)]
    (when (not-every? empty? [written-refs ensured-refs commuted-refs])
      (locking COMMIT_LOCK
        ; validate both written-refs and ensured-refs
        ; Note: no need to validate commuted-refs
        (doseq [ref (union written-refs ensured-refs)]
          (when (> (:write-point (most-recent @ref))
                   (:read-point tx))
            (tx-retry)))
        
        ; if validation OK, re-apply commutes based on its most recent value
        (doseq [[commuted-ref commute-fns] commuted-refs]
          ; if a ref has been written to (by set/alter as well as commute),
          ; its in-transaction-value will be correct so we don't need to set it
          (when-not (contains? written-refs commuted-ref)
            (swap! (:in-tx-values tx) assoc commuted-ref
              ; apply each commute-fn to the result of the previous commute-fn,
              ; starting with the most recent value
              ((reduce comp identity commute-fns)
                (:value (most-recent @commuted-ref))))))

        (let [in-tx-values @(:in-tx-values tx)
              new-write-point (inc @GLOBAL_WRITE_POINT)]
                    
          ; make in-tx-value of all written-to or commuted refs public
          (doseq [ref (union written-refs (keys commuted-refs))]
            (swap! ref (fn [history-chain]
                         ; add a new entry to the front of the history list...
                         (cons {:value (in-tx-values ref)
                                :write-point new-write-point}
                               ; ... and remove the eldest
                               (butlast history-chain)))))
          (swap! GLOBAL_WRITE_POINT inc)))))) ; make the new write-point public
      ; Note: if a transaction didn't write or ensure any refs,
      ; it automatically commits

; this function is a little more complicated than it needs to be because
; it can't tail-recursively call itself from within a catch-clause.
; The inner let either returns the value of the transaction, wrapped in a map,
; or nil, to indicate that the transaction must be retried.
; The outer let tests for nil and if so, calls the function tail-recursively
(defn tx-run
  "runs zero-argument fun as the body of transaction tx."
  [tx fun]
  (let [res (binding [*current-transaction* tx]
              (try
                (let [result (fun)]
                  (tx-commit tx)
                  ; commit succeeded, return result
                  {:result result}) ; wrap result, as it may be nil
                (catch Exception e
                  (when-not (retry-ex? e)
                    (throw e)))))]
    (if res
      (:result res)
      (recur (make-transaction) fun)))) ; tx aborted, retry with fresh tx

; default empty history list, shared by all fresh mc-refs
(def DEFAULT_HISTORY_TAIL (repeat (dec MAX_HISTORY) nil))

;; === MC-STM public API ===

; mc-ref is now a list of length MAX_HISTORY, containing
; {:value, :write-point} pairs, potentially followed by trailing nil values.
; Pairs are ordered latest :write-point first, oldest :write-point last
(defn mc-ref [val]
  (atom (cons {:value val :write-point @GLOBAL_WRITE_POINT}
              DEFAULT_HISTORY_TAIL)))

(defn mc-deref [ref]
  (if (nil? *current-transaction*)
    ; reading a ref outside of a transaction
    (:value (most-recent @ref))
    ; reading a ref inside a transaction
    (tx-read *current-transaction* ref)))

(defn mc-ref-set [ref newval]
  (if (nil? *current-transaction*)
    ; writing a ref outside of a transaction
    (throw (IllegalStateException. "can't set mc-ref outside transaction"))
    ; writing a ref inside a transaction
    (tx-write *current-transaction* ref newval)))
    
(defn mc-alter [ref fun & args]
  (mc-ref-set ref (apply fun (mc-deref ref) args)))

(defn mc-commute [ref fun & args]
  (if (nil? *current-transaction*)
    (throw (IllegalStateException. "can't commute mc-ref outside transaction"))
    (tx-commute *current-transaction* ref fun args)))

(defn mc-ensure [ref]
  (if (nil? *current-transaction*)
    (throw (IllegalStateException. "can't ensure mc-ref outside transaction"))
    (tx-ensure *current-transaction* ref)))

(defmacro mc-dosync [& exps]
  `(mc-sync (fn [] ~@exps)))

(defn mc-sync [fun]
  (if (nil? *current-transaction*)
    (tx-run (make-transaction) fun)
    (fun))) ; nested dosync blocks implicitly run in the parent transaction
