(ns quantum.untyped.core.fn
  (:refer-clojure :exclude [comp constantly])
  (:require
    [clojure.core                :as core]
    [quantum.untyped.core.form.evaluate
      :refer [case-env compile-if]]
    [quantum.untyped.core.form   :as uform
      :refer [arity-builder gen-args max-positional-arity unify-gensyms]]
    [quantum.untyped.core.vars   :as uvar
      :refer [defalias defmacro-]]))

;; ===== `fn<i>`: Positional functions ===== ;;

#?(:clj (defmacro fn0 [  & args] `(fn fn0# [f#  ] (f# ~@args))))
#?(:clj (defmacro fn1 [f & args] `(fn fn1# [arg#] (~f arg# ~@args)))) ; analogous to ->
#?(:clj (defmacro fnl [f & args] `(fn fnl# [arg#] (~f ~@args arg#)))) ; analogous to ->>

;; ===== `fn&`: Partial functions ===== ;;

#?(:clj
(defmacro fn&* [arity f & args]
  (let [f-sym (gensym) ct (count args)
        macro? (-> f resolve meta :macro)]
    `(let [~f-sym ~(when-not macro? f)]
     (fn ~@(for [i (range (if arity arity       0 )
                          (if arity (inc arity) 10))]
             (let [args' (vec (repeatedly i #(gensym)))]
               `(~args' (~(if macro? f f-sym) ~@args ~@args'))))
         ; Add variadic arity if macro
         ~@(when (and (not macro?)
                      (nil? arity))
             (let [args' (vec (repeatedly (+ ct 10) #(gensym)))]
               [`([~@args' & xs#] (apply ~f-sym ~@args ~@args' xs#))])))))))

#?(:clj (defmacro fn&  [f & args] `(fn&* nil ~f ~@args)))
#?(:clj (defmacro fn&0 [f & args] `(fn&* 0   ~f ~@args)))
#?(:clj (defmacro fn&1 [f & args] `(fn&* 1   ~f ~@args)))
#?(:clj (defmacro fn&2 [f & args] `(fn&* 2   ~f ~@args)))
#?(:clj (defmacro fn&3 [f & args] `(fn&* 3   ~f ~@args)))

;; ===== `fn'`: Fixed/constant functions ===== ;;

#?(:clj
(defmacro- fn'|generate []
  (let [v-sym 'v]
    `(defn ~'fn'
       "Exactly the same as `core/constantly`, but uses efficient positional
        arguments when possible rather than varargs every time."
       [~v-sym]
       (~'fn ~@(arity-builder (core/constantly v-sym) (core/constantly v-sym)))))))

(fn'|generate)
(defalias constantly fn')

#?(:clj
(defmacro fn'*|arities
  [arities-ct & body]
  (let [f (gensym "this")]
   `(~'fn ~f ~@(arity-builder
                 (fn [args] (if (empty? args) `(do ~@body) `(~f)))
                 (fn' `(~f))
                 0 arities-ct)))))

#?(:clj
(defmacro fn'*
  "Like `fn'` but re-evaluates the body each time."
  [& body] `(fn'*|arities 4 ~@body))) ; conservative to limit generated code size

;; ===== `comp`: Compositional functions ===== ;;

(defalias comp core/comp)
;; TODO demacro
#?(:clj (defmacro rcomp [& args] `(comp ~@(reverse args))))

;; ===== `aritoid` ===== ;;

#?(:clj
(defmacro aritoid
  ;; TODO use `arity-builder`
  "Combines fns as arity-callers."
  {:attribution "alexandergunnarson"
   :equivalent `{(aritoid vector identity conj)
                 (fn ([]      (vector))
                     ([x0]    (identity x0))
                     ([x0 x1] (conj x0 x1)))}}
  [& fs]
  (let [genned  (repeatedly (count fs) #(gensym "f"))
        fs-syms (vec (interleave genned fs))]
   `(let ~fs-syms
      (fn ~'aritoid ~@(for [[i f-sym] (map-indexed vector genned)]
                        (let [args (vec (repeatedly i #(gensym "x")))]
                         `(~args (~f-sym ~@args)))))))))

;; ===== Arrow macros and functions ===== ;;

#?(:clj
(defmacro <-
  "Converts a ->> to a ->
   Note: syntax modified from original."
   {:attribution "thebusby.bagotricks"
    :usage       `(->> (range 10) (map inc) (<- doto println) (reduce +))}
  ([x] `(~x))
  ([op & body] `(~op ~(last body) ~@(butlast body)))))

#?(:clj
(defmacro <<-
  "Converts a -> to a ->>"
   {:attribution "alexandergunnarson"
    :usage       `(-> 1 inc (/ 4) (<<- - 2))}
  ([x] `(~x))
  ([x op & body] `(~op ~@body ~x))))

#?(:clj
(defmacro fn->
  "Equivalent to `(fn [x] (-> x ~@body))`"
  {:attribution "thebusby.bagotricks"}
  [& body] `(fn fn-># [x#] (-> x# ~@body))))

#?(:clj
(defmacro fn->>
  "Equivalent to `(fn [x] (->> x ~@body))`"
  {:attribution "thebusby.bagotricks"}
  [& body] `(fn fn->># [x#] (->> x# ~@body))))

;; ===== Common fixed-function values ===== ;;

(def fn-nil   (fn' nil  ))
(def fn-false (fn' false))
(def fn-true  (fn' true ))
