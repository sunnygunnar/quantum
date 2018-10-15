(ns quantum.core.data.string
  "A String is a special wrapper for a char array where different encodings, etc. are possible."
       (:refer-clojure :exclude
         [string?])
       (:require
         [quantum.core.compare.core   :as c?]
         [quantum.core.data.meta      :as meta]
         [quantum.core.data.numeric   :as dn]
         [quantum.core.data.primitive :as p]
         [quantum.core.type           :as t]
         ;; TODO TYPED excise
         [quantum.untyped.core.core   :as ucore])
       (:import
#?(:clj  [com.carrotsearch.hppc CharArrayDeque])
#?(:cljs [goog.string           StringBuffer])))

(ucore/log-this-ns)

;; TODO investigate http://ahmadsoft.org/ropes/ : A rope is a high performance replacement for Strings. The datastructure, described in detail in "Ropes: an Alternative to Strings", provides asymptotically better performance than both String and StringBuffer
;; What about structural sharing with strings?
;; Wouldn't there have to be some sort of compact immutable bit map or something to diff it rather
;; than just making an entirely new string?

;; ===== General string-like entities ===== ;;

#?(:clj (def char-seq? (t/isa? java.lang.CharSequence)))

;; ===== Mutable strings ===== ;;

(def !string? (t/isa? #?(:clj java.lang.StringBuilder :cljs StringBuffer)))

(t/defn ^:inline >!string
  "Creates a mutable string."
  > !string?
  ([]   #?(:clj (StringBuilder.)    :cljs (StringBuffer.)))
  ;; TODO TYPED
  #_([x0] #?(:clj (StringBuilder. x0) :cljs (StringBuffer. x0))))

;; ----- Synchronously mutable strings ----- ;;

#?(:clj (def !sync-string? (t/isa? java.lang.StringBuffer)))

#?(:clj
(t/defn ^:inline >!sync-string
  "Creates a synchronized mutable string."
  {:todo #{"Do the same arity structure as >!string and >string"}}
  > !sync-string?
  ([] (StringBuffer.))))

;; ----- Mutable char deques ----- ;;

; TODO rework. Instead of |condf|, use records to represent
; parsed types and dispatch in |defnt| accordingly

; Currently only for strings
#_(:clj
(defn rreduce [f init ^String s]
  (loop [ret init i (-> s lasti int)] ; int because charAt requires int
    (if (>= i 0)
        (recur (f ret (.charAt s i)) (unchecked-dec i))
        ret))))
; Basically a double-sided StringBuilder
#_(:clj
(defn conjl! [^CharArrayDeque x ^String arg]
  (rreduce
    (fn [^CharArrayDeque ret c]
      (doto ret (.addFirst ^char (char c))))
    x
    arg)))

#_(:clj
(defn conjr! [^CharArrayDeque x ^String arg]
  (reduce
    (fn [^CharArrayDeque ret c]
      (doto ret (.addLast ^char (char c))))
    x
    arg)))

#_(:clj
(defnt concat!
  ([^com.carrotsearch.hppc.CharArrayDeque x arg] (conjr! x arg))
  ([^string? x arg] (conjl! arg x))
  #_([^fn? x arg] (conjl! ))))

#_(:clj
(defnt paren+
  ([^string? arg] (fn [sb] (conjl! sb "(") (conjr! sb arg) (conjr! sb ")")))
  ([^fn?     arg] (fn [sb] (conjl! sb "(") (conjr! sb (arg sb)) (conjr! sb ")")))))

;(conjl! sb (concat+ "abc" (paren+ (bracket+ "def"))))
;abc([def])

; class Operations extends PersistentVector.
; That way you have both contraint-classes and non-obfuscation of data.
; Data can be obfuscated by being too general, too.
;(bracket+ s) => (-> operations
;                    (conjr (*fn "]"))
;                    (conjl (*fn "[")))

; They can be broken down into operations
;(conjl! ")")
;(conjl! "]")
;(conjl! "def")
;(conjl! "[")
;(conjl! "(")
;(conjl! "abc")

#_(:clj
(defn sp+ [& args]
  (fn [sb]
    (doseqi [arg args n]
      (conjl! sb arg)
      (when (< n (-> args count dec))
        (conjl! sb " "))))))

;; ===== Immutable strings ===== ;;

(def string? (t/isa? #?(:clj java.lang.String :cljs js/String)))

;; TODO TYPED — `str` macro in CLJS has some secrets
(t/defn >string
  "Creates an immutable string."
  {:incorporated '{clojure.core/str "9/27/2018"
                   cljs.core/str    "9/27/2018"}}
  > string?
         ([] "")
         ([x p/nil?] "")
         ([x string?] x)
#?(:cljs ([x !string?] (.toString x)))
#?(:clj  ([x p/boolean? > (t/assume string?)] (Boolean/toString   x)))
#?(:clj  ([x p/byte?    > (t/assume string?)] (Byte/toString      x)))
#?(:clj  ([x p/short?   > (t/assume string?)] (Short/toString     x)))
#?(:clj  ([x p/char?    > (t/assume string?)] (Character/toString x)))
#?(:clj  ([x p/int?     > (t/assume string?)] (Integer/toString   x)))
#?(:clj  ([x p/long?    > (t/assume string?)] (Long/toString      x)))
#?(:clj  ([x p/float?   > (t/assume string?)] (Float/toString     x)))
#?(:clj  ([x p/double?  > (t/assume string?)] (Double/toString    x)))
#?(:clj  ([x t/ref?] (-> x .toString >string))
   :cljs ([x t/any?     > (t/assume string?)] (.join #js [x] "")))
         ;; TODO TYPED refine this
       #_([x ? & xs ...]
           (loop [sb (-> x >string >!string) more ys]
             (if more
                 (recur (.append sb (str (first more))) (next more))
                 (>string sb)))))

;; TODO TYPED add t/fn
(def radix?
  (t/fn [x integer?]
    (comp/<= #?(:clj Character/MIN_RADIX :cljs 2) x #?(:clj Character/MAX_RADIX :cljs 36))))

(t/extend-defn! ?c/compare
#?(:clj (^:in [a string?, b string?] (.compareTo a b))))

(t/extend-defn! ?c/=
#?(:clj (^:in [a string?, b string?] (.equals a b))))

(t/extend-defn! dn/>boolean
  ([x (t/value "true")] true)
  ([x (t/value "false")] false))

(t/extend-defn! dn/>byte
  ([x string?]
    #?(:clj  (Byte/parseByte x)
              ;; NOTE could use `js/parseInt` but it's very 'unsafe'
              ;; TODO implement based on `Byte/parseByte`
       :cljs (throw (ex-info "Parsing not implemented" {:string x}))))
  ([x string?, radix radix?]
    #?(:clj  (Byte/parseByte x (>int radix))
             ;; NOTE could use `js/parseInt` but it's very 'unsafe'
             ;; TODO implement based on `Byte/parseByte`
       :cljs (throw (ex-info "Parsing not implemented" {:string x})))))

(t/extend-defn! dn/>short
  ([x string?]
    #?(:clj  (Short/parseShort x)
              ;; NOTE could use `js/parseInt` but it's very 'unsafe'
              ;; TODO implement based on `Short/parseShort`
       :cljs (throw (ex-info "Parsing not implemented" {:string x}))))
  ([x string?, radix radix?]
    #?(:clj  (Short/parseShort x (>int radix))
             ;; NOTE could use `js/parseInt` but it's very 'unsafe'
             ;; TODO implement based on `Short/parseShort`
       :cljs (throw (ex-info "Parsing not implemented" {:string x})))))

(t/extend-defn! dn/>int
  ([x string?]
    #?(:clj  (Integer/parseInteger x)
             ;; NOTE could use `js/parseInt` but it's very 'unsafe'
             ;; TODO implement based on `Integer/parseInteger`
       :cljs (throw (ex-info "Parsing not implemented" {:string x}))))
  ([x string?, radix radix?]
    #?(:clj  (Integer/parseInteger x (p/>int radix))
             ;; NOTE could use `js/parseInt` but it's very 'unsafe'
             ;; TODO implement based on `Integer/parseInteger`
       :cljs (throw (ex-info "Parsing not implemented" {:string x})))))

(t/extend-defn! dn/>long
  ([x string?]
    #?(:clj  (Long/parseLong x)
              ;; NOTE could use `js/parseInt` but it's very 'unsafe'
              ;; TODO implement based on `Long/parseLong`
       :cljs (throw (ex-info "Parsing not implemented" {:string x}))))
  ([x string?, radix radix?]
    #?(:clj  (Long/parseLong x (>int radix))
             ;; NOTE could use `js/parseInt` but it's very 'unsafe'
             ;; TODO implement based on `Long/parseLong`
       :cljs (throw (ex-info "Parsing not implemented" {:string x})))))

(t/extend-defn! dn/>float
  ([x string?]
    #?(:clj  (Float/parseFloat x)
              ;; NOTE could use `js/parseFloat` but it's very 'unsafe'
              ;; TODO implement based on `Float/parseFloat`
       :cljs (throw (ex-info "Parsing not implemented" {:string x})))))

(t/extend-defn! dn/>double
  ([x string?]
    #?(:clj  (Double/parseDouble x)
             ;; NOTE could use `js/parseFloat` but it's very 'unsafe'
             ;; TODO implement based on `Double/parseDouble`
       :cljs (throw (ex-info "Parsing not implemented" {:string x})))))

;; ----- Metable immutable strings ----- ;;

;; TODO TYPED `t/deftype`
#?(:clj
(deftype MetableString [^String s ^clojure.lang.IPersistentMap _meta]
  clojure.lang.IObj
    (meta        [this]       _meta)
    (withMeta    [this meta'] (MetableString. s meta'))
  CharSequence
    (charAt      [this i]     (.charAt s i))
    (length      [this]       (.length s))
    (subSequence [this a b]   (.subSequence s a b))
  Object
    (toString    [this]       s)
  fipp.ednize/IOverride
  fipp.ednize/IEdn
    (-edn [this] s)))

#?(:clj
(defmethod print-method MetableString [^MetableString x ^java.io.Writer w]
  (print-method (.toString x) w)))

(def metable-string? #?(:clj (t/isa? MetableString) :cljs string?))

(t/defn >metable-string
  > metable-string?
  ([s string?] #?(:clj (MetableString. s nil) :cljs s))
  ([s string?, meta' meta/meta?]
    #?(:clj (MetableString. s meta') :cljs (meta/with-meta s new-meta))))
