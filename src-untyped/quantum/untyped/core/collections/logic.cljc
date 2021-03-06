(ns quantum.untyped.core.collections.logic
  (:refer-clojure :exclude
    [== every? not-any? not-every? some])
  (:require
    [clojure.core      :as core]
    [quantum.untyped.core.compare :as ucomp
      :refer [== not==]]
    [quantum.untyped.core.core    :as ucore
      :refer [>sentinel]]
    [quantum.untyped.core.fn
      :refer [rcomp]]
    [quantum.untyped.core.reducers
      :refer [educe]]
    [quantum.untyped.core.vars
      :refer [defalias]]))

(ucore/log-this-ns)

;; `seq-or`

(defn seq-or|rf #_> #_boolean?
  ([] (seq-or|rf identity))
  ([pred]
    (fn ([]      false)
        ([ret]   ret)
        ([_ x]   (and (pred x)   (reduced true)))
        ([_ k v] (and (pred k v) (reduced true))))))

(defn seq-or
  "∃: A faster version of `some` using `educe` instead of `seq`."
  ([xs] (educe (seq-or|rf) xs))
  ([pred xs] (educe (seq-or|rf pred) xs)))

(defalias some|rf seq-or|rf)

(defalias some seq-or)

(defn apply-or [xs] (seq-or xs))

;; `seq-nor`

#_(def seq-nor|rf ...)

#_(defalias not-any?|rf seq-nor|rf)

(def seq-nor (rcomp seq-or not))

(defalias not-any? seq-nor)

;; `seq-and`

(defn seq-and|rf #_> #_boolean?
  ([] (seq-and|rf identity))
  ([pred]
    (fn ([]      true) ; vacuously
        ([ret]   ret)
        ([_ x]   (or (pred x)   (reduced false)))
        ([_ k v] (or (pred k v) (reduced false))))))

(defn seq-and
  "∀: A faster version of `every?` using `educe` instead of `seq`."
  ([xs] (educe (seq-and|rf) xs))
  ([pred xs] (educe (seq-and|rf pred) xs)))

(defalias every?|rf seq-and|rf)

(defalias every? seq-and)

(defn apply-and [xs] (seq-and xs))

;; `seq-and-2`

(defn seq-and-2
  "`seq-and` for pairwise comparisons."
  ([pred xs]
    (reduce (fn [a b] (or (pred a b) (reduced false))) (first xs) (rest xs))))

(defalias every?-2 seq-and-2)

;; `seq-nand`

#_(def seq-nand|rf ...)

(def seq-nand (rcomp seq-and not))

#_(defalias not-every?|rf seq-nand|rf)

(defalias not-every? seq-nand)



(defonce init-sentinel   (>sentinel))
(defonce failed-sentinel (>sentinel))

(defn incremental-every?
  "Similarly to `incremental-apply`, applies binary comparator ->`pred`
   to reducible ->`xs`, incrementally, using `reduce`."
  [pred xs]
  (let [ret (reduce
              (fn [prev x]
                (if (== prev init-sentinel)
                    (if (pred x)      x (reduced failed-sentinel))
                    (if (pred prev x) x (reduced failed-sentinel))))
              init-sentinel
              xs)]
    (not== ret failed-sentinel)))

(defn every-val
  "Computes what every value in `xs` is equivalent to (via `=`), or the provided
   `not-equivalent` value if they are not all equivalent."
  [not-equivalent xs]
  (reduce (fn [ret x]
            (cond (identical? ret init-sentinel) x
                  (not= x ret)                   (reduced not-equivalent)
                  :else                          ret))
          init-sentinel
          xs))
