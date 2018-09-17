(ns quantum.core.type
  "This is this the namespace upon which all other fully-typed namespaces rest."
  (:refer-clojure :exclude
    [* - and any? fn fn? isa? or ref seq? symbol? var?])
  (:require
    [quantum.untyped.core.type.defnt :as udefnt]
    [quantum.untyped.core.type       :as ut]
    ;; TODO TYPED prefer e.g. `deft-alias`
    [quantum.untyped.core.vars
      :refer [defalias defaliases]]))

(defalias udefnt/fnt)
(defalias udefnt/defnt)

(defaliases ut
  ;; Generators
  ? * isa? fn ref value
  ;; Combinators
  and or -
  ;; Predicates
  any?
  nil?
  none?
  ref?
  fn?
  metable?
  seq?
  symbol?
  var?
  with-metable?)


;; TODO TYPED move
#_(defnt ^boolean nil?
  ([^Object x] (quantum.core.Numeric/isNil x))
  ([:else   x] false))

;; TODO TYPED move
#_(:clj (defalias nil? core/nil?))

;; TODO TYPED move
#_(defnt ^boolean not'
  ([^boolean? x] (Numeric/not x))
  ([x] (if (nil? x) true))) ; Lisp nil punning

;; TODO TYPED move
#_(defnt ^boolean true?
  ([^boolean? x] x)
  ([:else     x] false))

;; TODO TYPED move
#_(:clj (defalias true? core/true?))

;; TODO TYPED move
#_(defnt ^boolean false?
  ([^boolean? x] (not' x))
  ([:else     x] false))

;; TODO TYPED move
#_(:clj (defalias false? core/false?))
