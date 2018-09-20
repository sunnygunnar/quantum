(ns quantum.test.untyped.core.type
        (:require
          [clojure.core                               :as core]
          [quantum.untyped.core.test
            :refer [deftest testing is is= throws]]
          [quantum.untyped.core.type                  :as t
            :refer [& | !]]
          [quantum.untyped.core.type.reifications     :as utr
 #?@(:cljs [:refer [UniversalSetType EmptySetType
                    NotType OrType AndType
                    ProtocolType ClassType
                    ValueType]])]
          [quantum.test.untyped.core.type.compare
            :refer [i|>a+b i|>a0 i|>a1 i|>b0 i|>b1
                    i|a i|b
                    i|<a+b i|<a0 i|<a1 i|<b0 i|<b1
                    i|><0 i|><1 i|><2

                    >a+b >a >b
                    a b
                    <a0 <a1 <b0 <b1
                    ><0 ><1 ><2]])
#?(:clj (:import
          [quantum.untyped.core.type.reifications
             UniversalSetType EmptySetType
             NotType OrType AndType
             ProtocolType ClassType
             ValueType])))

;; ===== Type predicates ===== ;;
;; Declared here instead of in `quantum.untyped.core.type` to avoid dependency

#?(:clj (def boolean?   (t/isa? #?(:clj Boolean :cljs js/Boolean))))
#?(:clj (def byte?      (t/isa? Byte)))
#?(:clj (def short?     (t/isa? Short)))
#?(:clj (def char?      (t/isa? Character)))
#?(:clj (def int?       (t/isa? Integer)))
#?(:clj (def long?      (t/isa? Long)))
#?(:clj (def float?     (t/isa? Float)))
        (def double?    (t/isa? #?(:clj Double :cljs js/Number)))

        (def primitive? (t/or boolean? #?@(:clj [byte? short? char? int? long? float?]) double?))

#?(:clj (def char-seq?  (t/isa? CharSequence)))
        (def string?    (t/isa? #?(:clj String :cljs js/String)))

;; ===== End type predicates ===== ;;

(defn test-equality [genf]
  (let [a (genf) b (genf)]
          (testing "structural equality (`c/=`)"
            (is= a b))
          (testing "hash(eq) equality"
            (is= (hash a) (hash b)))
  #?(:clj (testing "hash(code) equality"
            (is= (.hashCode a) (.hashCode b))))
          (testing "collection equality"
            (is= 1 (count (hash-set a b))))))

(deftest test|universal-set
  (test-equality #(UniversalSetType.)))

(deftest test|empty-set
  (test-equality #(EmptySetType.)))

(deftest test|not
  (test-equality #(! (t/value 1)))
  (testing "simplification"
    (testing "universal/null set"
      (is= (! t/universal-set)
           t/empty-set)
      (is= (! t/empty-set)
           t/universal-set))
    (testing "universal class-set"
      (is= (! t/val?)
           t/nil?)
      (is= (! t/val|by-class?)
           t/nil?))
    (testing "DeMorgan's Law"
      (is= (! (| i|a i|b))
           (& (! i|a) (! i|b)))
      (is= (! (& i|a i|b))
           (| (! i|a) (! i|b)))
      (is= (! (| (! i|a) (! i|b)))
           (&       i|a     i|b))
      (is= (! (& (! i|a) (! i|b)))
           (|       i|a     i|b)))))

(deftest test|-
  (testing "="
    (is= (t/- a a)
         t/empty-set))
  (testing "<"
    (is= (t/- a >a)
         t/empty-set))
  (testing "<>"
    (is= (t/- a b)
         a))
  (testing ">"
    (is= (t/- (| a b) a)
         b)
    (is= (t/- (| a b long?) a)
         (| b long?)))
  (testing "><"
    ))

(deftest test|or
  (test-equality #(| a b))
  (test-equality #(| (t/value 1) (t/value 2)))
  (testing "simplification"
    (testing "via single-arg"
      (is= (| a)
           a))
    (testing "via identity"
      (is= (| a a)
           a)
      (is= (| (| a a) a)
           a)
      (is= (| a (| a a))
           a)
      (is= (| (| a b) (| b a))
           (| a b))
      (is= (| (| a b ><0) (| a ><0 b))
           (| a b ><0)))
    (testing "via universal class + nil"
      (is= t/universal-set (| (t/isa? Object) (t/value nil)))
      (is= t/universal-set (| (t/value nil)   (t/isa? Object)))
      (is= t/universal-set (| (t/isa? Object) (t/value nil)   (t/value 1)))
      (is= t/universal-set (| (t/isa? Object) (t/value 1)     (t/value nil)))
      (is= t/universal-set (| (t/value nil)   (t/isa? Object) (t/value 1)))
      (is= t/universal-set (| (t/value nil)   (t/value 1)     (t/isa? Object)))
      (is= t/universal-set (| (t/value 1)     (t/isa? Object) (t/value nil)))
      (is= t/universal-set (| (t/value 1)     (t/value nil)   (t/isa? Object))))
    (testing "nested `or` is expanded"
      (is= (| (| a b) (| ><0 ><1))
           (| a b ><0 ><1))
      (is= (| (| a b) (| ><0 ><1))
           (| a b ><0 ><1)))
    (testing "via `not`"
      (is= (| a (! a))
           t/universal-set)
      (is= (| a b (! a))
           t/universal-set)
      (is= (| a b (| (! a) (! b)))
           t/universal-set))
    (testing "nested"
      (is= (utr/or-type>args (| (| string? double?)
                                char-seq?))
           [double? char-seq?])
      (is= (utr/or-type>args (| (| string? double?)
                                (| double? char-seq?)))
           [double? char-seq?])
      (is= (utr/or-type>args (| (| string? double?)
                                (| char-seq? t/number?)))
           [char-seq? t/number?]))
    (testing "#{<+ =} -> #{<+}"
      (is= (utr/or-type>args (| i|>a+b i|>a0 i|a))
           [i|>a+b i|>a0]))
    (testing "#{<+ >+} -> #{<+}"
      (is= (utr/or-type>args (| i|>a+b i|>a0 i|<a+b i|<a0))
           [i|>a+b i|>a0]))
    (testing "#{>+ =} -> #{=}"
      (is= (| i|<a+b i|<a0 i|a)
           i|a))
    (testing "#{<+ >+ ><+} -> #{<+ ><+}"
      (is= (utr/or-type>args (| i|>a+b i|>a0 i|<a+b i|<a0 i|><0 i|><1))
           [i|>a+b i|>a0 i|><0 i|><1]))
    (testing "#{<+ >+ <>+} -> #{<+ <>+}"
      (is= (utr/or-type>args (| >a <a0 ><0 ><1))
           [>a ><0 ><1]))
    (testing "#{<+ =+ >+ ><+} -> #{<+ ><+}"
      (is= (utr/or-type>args (| i|>a+b i|>a0 i|a i|<a+b i|<a0 i|><0 i|><1))
           [i|>a+b i|>a0 i|><0 i|><1]))
    (testing "#{<+ =+ >+ <>+} -> #{<+ <>+}"
      (is= (utr/or-type>args (| >a a <a0 ><0 ><1))
           [>a ><0 ><1]))))

(deftest test|and
  (test-equality #(& i|a i|b))
  (testing "null set / universal set"
    (is= (& t/universal-set t/universal-set)
         t/universal-set)
    (is= (& t/universal-set t/empty-set)
         t/empty-set)
    (is= (& t/empty-set t/universal-set)
         t/empty-set)
    (is= (& t/universal-set t/empty-set t/universal-set)
         t/empty-set)
    (is= (& t/universal-set string?)
         string?)
    (is= (& t/universal-set char-seq? string?)
         string?)
    (is= (& t/universal-set string? char-seq?)
         string?)
    (is= (& t/empty-set string?)
         t/empty-set)
    (is= (& t/empty-set char-seq? string?)
         t/empty-set)
    (is= (& t/empty-set string? char-seq?)
         t/empty-set))
  (testing "simplification"
    (testing "via single-arg"
      (is= (& a)
           a))
    (testing "via identity"
      (is= (& a a)
           a)
      (is= (& (! a) (! a))
           (! a))
      (is= (& (& a a) a)
           a)
      (is= (& a (& a a))
           a)
      (is= (& (| string? byte?) (| byte? string?))
           (| string? byte?))
      (is= (& (| a b) (| b a))
           (| a b))
      (is= (& (| a b ><0) (| a ><0 b))
           (| a b ><0)))
    (testing ""
      (is= (utr/and-type>args (& i|a i|b))
           [i|a i|b]))
    (testing "empty-set"
      (is= (& a b)
           t/empty-set)
      (is= (& string? byte?)
           t/empty-set)
      (is= (& a ><0)
           t/empty-set)
      (is= (& a ><0 ><1)
           t/empty-set))
    (testing "nested `and` is expanded"
      (is= (& (& a b) (& ><0 ><1))
           (& a b ><0 ><1))
      (is= (& (& a b) (& ><0 ><1))
           (& a b ><0 ><1)))
    (testing "and + not"
      (is= (& a (! a))
           t/empty-set)
      (is= (& a (! b))
           a)
      (is= (& (! b) a)
           a)
      (testing "+ or"
        (is= (& (! a) a b)
             t/empty-set)
        (is= (& a (! a) b)
             t/empty-set)
        (is= (& a b (! a))
             t/empty-set)
        (is= (& (| a b) (! a))
             b)
        ;; TODO fix impls
        #_(is= (& (! a) (| a b))
             b)
        (is= (& (| a b) (! b) (| b a))
             a)
        (is= (& (| a b) (! b) (| ><0 b))
             t/empty-set))
      (is= (& primitive? (! boolean?))
           (| byte? short? char? int? long? float? double?)))
    (testing "#{<+ =} -> #{=}"
      (is= (& i|>a+b i|>a0 i|a)
           i|a))
    (testing "#{>+ =+} -> #{>+}"
      (is= (utr/and-type>args (& i|<a+b i|<a0 i|a))
           [i|<a+b i|<a0]))
    (testing "#{<+ >+} -> #{>+}"
      (is= (utr/and-type>args (& i|>a+b i|>a0 i|<a+b i|<a0))
           [i|<a+b i|<a0]))
    (testing "#{<+ >+ ∅+} -> #{>+ ∅+}"
      (is= (utr/and-type>args (& i|>a+b i|>a0 i|<a+b i|<a0 i|><0 i|><1))
           [i|<a+b i|<a0 i|><0 i|><1]))
    (testing "#{<+ =+ >+ ∅+} -> #{>+ ∅+}"
      (is= (utr/and-type>args (& i|>a+b i|>a0 i|a i|<a+b i|<a0 i|><0 i|><1))
           [i|<a+b i|<a0 i|><0 i|><1]))))

(deftest test|protocol
  (test-equality #(t/isa? utr/PType)))

(deftest test|class
  (test-equality #(t/isa? Object)))

(deftest test|value
  (test-equality #(t/value 1))
  (testing "hash equality"
    (is= (hash (t/value 1)) (hash (t/value 1)))
    (is= 1 (count (hash-set (t/value 1)
                            (t/value 1))))))
