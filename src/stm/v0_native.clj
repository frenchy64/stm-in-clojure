;; version 0 - Clojure's native STM
;; this is just a wrapper around Clojure's native STM, to be able to
;; run tests based on the MC-STM's API using the built-in STM

(ns stm.v0-native)

(def mc-ref ref)
(def mc-deref deref)
(def mc-ref-set ref-set)
(def mc-alter alter)
(def mc-commute commute)
(def mc-ensure ensure)

(defmacro mc-dosync [& exps]
  `(dosync ~@exps))
