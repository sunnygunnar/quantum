(ns
  ^{:doc "Logic-related functions. fn-not, fn-and, splice-or,
          ifn, whenf1, rcomp, fn->, condpc, and the like. Extremely useful
          and used everywhere in the quantum library."
    :attribution "alexandergunnarson"}
  quantum.core.logic
  (:refer-clojure :exclude
    [= and not or ifs
     if-let when-let])
  (:require
    [clojure.core                :as core]
    [quantum.untyped.core.fn     :as ufn
      :refer [fn1 fn-> fn->> fn']]
    [quantum.untyped.core.form.evaluate
      :refer [case-env]]
    [quantum.untyped.core.logic  :as u]
    [quantum.untyped.core.vars   :as var
      :refer [defalias defaliases]])
#?(:cljs
  (:require-macros
    [quantum.core.logic          :as self
      :refer [fn-not]])))

; Java `switch` is implemented using an array and then points to the code.
; Java String `switch` is implemented using a map8

; not      1 0     ; complement
; and      0 0 0 1 ; conjunction
; nand     1 1 1 0 ; Sheffer stroke
; or       0 1 1 1 ; disjunction
; nor      1 0 0 0 ; Peirce's arrow
; xor      0 1 1 0
; xnor     1 0 0 1
; implies? 1 1 0 1

(defalias u/default)

;; ===== Logical operators ===== ;;

(defaliases u
  = ref=
  not
#?@(:clj
 [and nand
  or nor
  xor xnor
  implies?]))

#?(:clj
(defmacro and*
  "Like `clojure.core/and`, but avoids intermediate let bindings and only ever returns a boolean."
  ([] true)
  ([x] `(if ~x true false))
  ([x & xs] `(if ~x (and* ~@xs) false))))

#?(:clj
(defmacro or*
  "Like `clojure.core/or`, but avoids intermediate let bindings and only ever returns a boolean."
  ([] false)
  ([x] `(if ~x true false))
  ([x & xs] `(if ~x true (or* ~@xs)))))

;; ===== Function-logical operators ===== ;;

#?(:clj
(defaliases u
  fn= fn-not=
  fn-not
  fn-and fn-nand
  fn-or fn-nor
  fn-xor fn-xnor
  fn-implies?))

;___________________________________________________________________________________________________________________________________
;==================================================={ BOOLEANS + CONDITIONALS }=====================================================
;==================================================={                         }=====================================================

; difference = (and a (not b))

; TODO maybe eliminate `volatile!`?
#?(:clj
(defmacro some-but-not-more-than-n
  "`some-but-not-more-than-n` where `n`=1 is equivalent to
   `(and (or ...) (not (and ...)))`. However, it performs
   one O(n) check rather than two."
  [n & args]
  (assert (integer? n) {:n n})
  `(let [and?# (volatile! true)]
     (and (or ~@(take n args) (vreset! and?# false) ~@(drop n args))
          (or (not @and?#) (not (and ~@(drop n args))))))))

#?(:clj (defmacro exactly-1 [& args] `(some-but-not-more-than-n 1 ~@args)))
; TODO `exactly-n`

(def falsey? (some-fn false? nil?))
(def truthy? (fn-not falsey?))

(defn splice-or  [obj compare-fn & coll]
  (some   #_seq-or  (partial compare-fn obj) coll))
(defn splice-and [obj compare-fn & coll]
  (every? #_seq-and (partial compare-fn obj) coll))

(defn bool
  {:todo ["Deprecate or incorporate"]}
  [v]
  (cond
    (= v 0) false
    (= v 1) true
    :else
      (throw (#?(:clj  IllegalArgumentException.
                 :cljs js/Error.)
               (str "Value not booleanizable: " v)))))

#?(:clj
(defmacro cond*
  "`cond` meets `case`.
   Like `case`, takes test pairs with an optional trailing (unpaired) clause.
   If all preds are compile-time constants, transforms into `case`.
   Otherwise behaves more like `cond`, evaluating each test in order:
     If a pred matches,
       Returns the corresponding expression
     Else
       If there is a trailing (unpaired) clause,
         That clause is returned, like the last arg to `case`.
       Else
         Throws an error that no clause matches, like `case`."
  [& args]
  (throw (ex-info "TODO" nil))))

;; ===== `cond(f|c|p)` ===== ;;

#?(:clj (defaliases u ifs condf condf1 condf& condfc is? condpc))

;; ===== `if(n|c|p)` ===== ;;

#?(:clj
(defaliases u
  ifn ifn-> ifn->> ifn1
  ifc ifc-> ifc->> ifc1
  ifp ifp-> ifp->> ifp1))

;; ===== `when(f|c|p)` ===== ;;

#?(:clj
(defaliases u
  whenf whenf-> whenf->> whenf1
  whenc whenc-> whenc->> whenc1
  whenp whenp-> whenp->> whenp1))

;; ===== Conditional `let` bindings ===== ;;

#?(:clj
(defaliases u
  if-let   if-not-let
  when-let when-not-let
  cond-let
  and-let nand-let
  or-let  nor-let
  xor-let xnor-let))

;; ===== `coll-(or|and)` ===== ;;

#?(:clj (defaliases u coll-or coll-and))
