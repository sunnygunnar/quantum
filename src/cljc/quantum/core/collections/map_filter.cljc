(ns
  ^{:doc
      "Various collections functions.

       Includes better versions of the following than clojure.core:

       for, doseq, repeat, repeatedly, range, merge,
       count, vec, reduce, into, first, second, rest,
       last, butlast, get, pop, peek ...

       and more.

       Many of them are aliased from other namespaces like
       quantum.core.collections.core, or quantum.core.reducers."
    :attribution "Alex Gunnarson"}
  quantum.core.collections.map-filter
  (:refer-clojure :exclude
    [for doseq reduce
     contains?
     map pmap map-indexed filter remove
     repeat repeatedly
     interpose
     range
     take take-while
     drop  drop-while
     subseq
     key val
     merge sorted-map sorted-map-by
     into
     count
     empty empty?
     split-at
     first second rest last butlast get pop peek
     select-keys
     zipmap
     reverse
     conj
     conj! assoc! dissoc! disj!
     boolean?])
  (:require
    [clojure.core                  :as core]
    [quantum.core.data.map         :as map
      :refer [map-entry]]
    [quantum.core.collections.core :as coll
      :refer [key val conj first rest conj! lasti contains?]]
    [quantum.core.fn               :as fn
      :refer [juxt-kv <- rcomp defcurried]]
    [quantum.core.macros           :as macros
      :refer[defnt]]
    [quantum.core.reducers         :as red
      :refer[indexed+ join' reduce defeager]]
    [quantum.core.type             :as type]
    [quantum.core.loops            :as loops
      :refer [reducei doseqi lfor]]
    [quantum.core.vars             :as var
      :refer [defalias]]))

(defcurried each ; like doseq
  "Applies f to each item in coll, returns nil"
  {:attribution "transduce.reducers"}
  [f coll]
  (reduce (fn [_ x] (f x) nil) nil coll))

; ============================ MAP ============================ ;

(defeager map         red/map+)
(defeager map-indexed red/map-indexed+)

(defn map-keys* [f-coll] (fn [f coll] (->> coll (f-coll (juxt (rcomp key f) val)))))
(def  map-keys+ (map-keys* map+))
(def  map-keys' (map-keys* map'))
(def  map-keys  (map-keys* map ))
(def  lmap-keys (map-keys* lmap))

(defn map-vals* [f-coll] (fn [f coll] (->> coll (f-coll (juxt key (rcomp val f))))))
(def  map-vals+ (map-vals* map+))
(def  map-vals' (map-vals* map'))
(def  map-vals  (map-vals* map ))
(def  lmap-vals (map-vals* lmap))

; ============================ FILTER ============================ ;

(defeager filter red/filter+)

(defn ffilter
  "Returns only the first result of a |filter| operation."
  {:todo ["Allow parallelization"]}
  [pred coll]
  (->> coll (filter+ pred) first))

(defn ffilteri
  {:todo ["Allow parallelization"]
   :in   '[(fn-eq? "4") ["a" "d" "t" "4" "10"]]
   :out  [3 "4"]}
  [pred coll]
  (->> coll indexed+ (ffilter (rcomp val pred))))

(defn filteri
  {:todo ["Use reducers"]}
  [pred coll]
  (if (type/should-transientize? coll)
      (persistent!
        (loops/reducei
          (fn [ret elem-n n]
            (if (pred elem-n)
                (conj! ret (map-entry n elem-n))
                ret))
          (transient [])
          coll))
      (loops/reducei
        (fn [ret elem-n n]
          (if (pred elem-n)
              (conj ret (map-entry n elem-n))
              ret))
        []
        coll)))

(defnt ^clojure.lang.MapEntry last-filteri*
  {:todo ["Use a delayed reduction as the base!" "Allow parallelization"]
   :in   '[["a" "d" "t" "4" "4" "10"] (fn-eq? "4")]
   :out  [4 "4"]}
  ([^indexed? coll pred]
    (->> coll rseq (ffilteri pred)
         (<- update 0 (partial - (lasti coll)))))
  ([coll pred]
    (loops/reducei
      (fn [ret elem-n index-n]
        (if (pred elem-n)
            (map-entry index-n elem-n)
            ret))
      (map-entry nil nil)
      coll)))

#?(:clj  (definline last-filteri [pred coll] `(last-filteri* ~coll ~pred))
   :cljs (defn      last-filteri [pred coll]  (last-filteri*  coll  pred)))

(defeager remove red/remove+)
;___________________________________________________________________________________________________________________________________
;=================================================={  FILTER + REMOVE + KEEP  }=====================================================
;=================================================={                          }=====================================================
; TODO remove duplication
(defn filter-keys* [f-coll] (fn [pred coll] (->> coll (f-coll (rcomp key pred)))))
(def  filter-keys+ (filter-keys* filter+))
(def  filter-keys' (filter-keys* filter'))
(def  filter-keys  (filter-keys* filter ))
(def  lfilter-keys (filter-keys* lfilter))

(defn filter-vals* [f-coll] (fn [pred coll] (->> coll (f-coll (rcomp val pred)))))
(def  filter-vals+ (filter-vals* filter+))
(def  filter-vals' (filter-vals* filter'))
(def  filter-vals  (filter-vals* filter ))
(def  lfilter-vals (filter-vals* lfilter))

(defn remove-keys* [f-coll] (fn [pred coll] (->> coll (f-coll (rcomp key pred)))))
(def  remove-keys+ (remove-keys* remove+))
(def  remove-keys' (remove-keys* remove'))
(def  remove-keys  (remove-keys* remove ))
(def  lremove-keys (remove-keys* lremove))

(defn remove-vals* [f-coll] (fn [pred coll] (->> coll (f-coll (rcomp val pred)))))
(def  remove-vals+ (remove-vals* remove+))
(def  remove-vals' (remove-vals* remove'))
(def  remove-vals  (remove-vals* remove ))
(def  lremove-vals (remove-vals* lremove))

; Distinct can be seen as sort of a filter

(defn ldistinct-by
  "Returns a lazy sequence of the elements of coll, removing any elements that
  return duplicate values when passed to a function f."
  {:attribution "medley.core"}
  [f coll]
  (let [step (fn step [xs seen]
               (lazy-seq
                ((fn [[x :as xs] seen]
                   (when-let [s (seq xs)]
                     (let [fx (f x)]
                       (if (contains? seen fx)
                         (recur (rest s) seen)
                         (cons x (step (rest s) (conj seen fx)))))))
                 xs seen)))]
    (step coll #{})))

#?(:clj
(defn ldistinct-by-java
  "Returns elements of coll which return unique
   values according to f. If multiple elements of coll return the same
   value under f, the first is returned"
  {:attribution "prismatic.plumbing"
   :performance "Faster than |distinct-by|"}
  [f coll]
  (let [s (java.util.HashSet.)] ; instead of #{}
    (lfor [x coll
           :let [id (f x)]
           :when (not (.contains s id))]
     (do (.add s id)
         x)))))
