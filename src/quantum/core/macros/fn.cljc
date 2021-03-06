(ns ^{:doc "Function macros for improving |fn| and |defn|.
            Includes optimization, etc.
            Also includes helper functions for macros which create
            |defn| and |fn| variants."}
  quantum.core.macros.fn
  (:require
#_(:clj
    [co.paralleluniverse.pulsar.core  :as pulsar])
    [quantum.core.error               :as err
      :refer [>ex-info]]
    [quantum.core.fn                  :as fn
      :refer [doto->>]]
    [quantum.core.log                 :as log]
    [quantum.core.logic               :as logic
      :refer [fn-or whenf1]]
    [quantum.core.macros.optimization :as opt
      :refer [extern?]]
    [quantum.untyped.core.collections.tree :as utree
      :refer [postwalk]]
    [quantum.core.vars
      :refer [defalias]]
    [quantum.untyped.core.form.evaluate
      :refer [case-env]]
    [quantum.untyped.core.form.generate :as ufgen]))

(defalias gen-args             ufgen/gen-args)
(defalias arity-builder        ufgen/arity-builder)
(defalias max-positional-arity ufgen/max-positional-arity)

(defn defn-variant-organizer
  "Organizes the arguments for use for a |defn| variant.
   Things like sym, meta, doc, etc."
  [f opts lang ns- sym doc- meta- body [unk & rest-unk]]
  (if unk
      (cond
        (string? unk)
          (f opts lang ns- sym unk  meta- body                rest-unk)
        (map?    unk)
          (f opts lang ns- sym doc- unk   body                rest-unk)
        ((fn-or symbol? keyword? vector? seq?) unk)
          (f opts lang ns- sym doc- meta- (cons unk rest-unk) nil     )
        :else
          (throw (ex-info :illegal-argument
                   {:msg   (str "Invalid arguments to |" sym "|.")
                    :cause unk
                    :args  {:ns-      ns-
                            :sym      sym
                            :doc-     doc-
                            :meta-    meta-
                            :body     body
                            :unk      unk
                            :rest-unk rest-unk}})))
      (f opts lang ns- sym doc- meta- body)))

(defn optimize-defn-variant-body!
  "Optimizes a body similar to |defn|.
   Currently performs the following optimizations:
   - Externs"
  [body externs]
  (log/ppr-hints :macro-expand "ORIG BODY:" body)
  (->> body
       (postwalk
         (whenf1 extern?
           (fn [[extern-sym obj]]
             (let [sym (gensym "externed")]
               (swap! externs conj (list 'def sym obj))
               sym))))
       (doto->> (log/ppr-hints :macro-expand "OPTIMIZED BODY:"))))

#?(:clj
(defmacro fn+*
  ([sym doc- meta- arglist body [unk & rest-unk]]
    (throw (>ex-info "Should not use |fn+*| yet"))
    (if unk
        (cond
          (string? unk)
            `(fn+* ~sym ~unk  ~meta- ~arglist ~body                ~rest-unk)
          (map?    unk)
            `(fn+* ~sym ~doc- ~unk   ~arglist ~body                ~rest-unk)
          (vector? unk)
            `(fn+* ~sym ~doc- ~meta- ~unk     ~rest-unk            nil      )
          (list?   unk)
            `(fn+* ~sym ~doc- ~meta- nil      ~(cons unk rest-unk) nil      )
          :else
            `(throw (>ex-info :illegal-argument "Invalid arguments to |fn+|. " ~unk)))
        (let [_        (log/ppr-hints :macro-expand "ORIG BODY:" body)
              ret-type (->> sym meta :tag)
              suspendable?   (->> sym meta :suspendable)
              interruptible? (->> sym meta :interruptible)
              ret-type-quoted (list 'quote ret-type)
              ;pre-args (->> (list meta-) (remove nil?))
              meta-f   (assoc (or meta- {})
                               :doc doc-)
              meta-f   (if ret-type
                           (assoc meta-f :tag ret-type-quoted)
                           meta-f)
              sym-f    (-> sym
                           (with-meta meta-f))
              externs  (atom [])
              body-f   (if arglist (list (cons arglist body)) body)
              body-f   (->> body-f
                            (postwalk
                              (whenf1 opt/extern?
                                (fn [[extern-sym obj]]
                                  (let [sym (gensym "externed")]
                                    (log/pr :macro-expand "EXTERNED" sym "IN FN+")
                                    (swap! externs conj (list 'def sym obj))
                                    sym)))))
              _        (log/ppr :macro-expand "OPTIMIZED BODY:" body-f)
              fn-sym (gensym)
              args-f
                (for [[arglist-n & body-n] body-f]
                  (list arglist-n
                    (list 'let
                      [`pre#  (list 'log/pr :trace (str "IN "             sym-f))
                       'ret_genned123  (if interruptible?
                                           `(if (quantum.core.async/interrupted?)
                                                (throw (InterruptedException.))
                                                (do ~@body-n))
                                           `(do ~@body-n))
                       `post# (list 'log/pr :trace (str "RETURNING FROM " sym-f))]
                      'ret_genned123)))
              _ (log/ppr :macro-expand "FINAL ARGS TO |defn|:" args-f)
              instrumented-fn
                (case-env
                  :clj  `(if (and ~suspendable?
                                 (System/getProperty "quantum.core.async:allow-suspendable?"))
                             (pulsar/suspendable! ~fn-sym)
                             ~fn-sym)
                  :cljs fn-sym)
              _ (log/ppr :macro-expand "INSTRUMENTED FN:" args-f)]
           `(do ~@(deref externs)
                (let [;~sym-f (with-meta '~sym-f
                      ;         (assoc (or ~meta- {}) :doc ~doc-))
                      meta-f# ~meta-f
                      ~fn-sym (with-meta
                                (fn ~sym-f ~@args-f)
                                meta-f#)]
                 [~instrumented-fn
                  meta-f#])) ; May greatly increase the compilation time depending on how long the fn is.
                   ; avoids eval-list stuff
           )))))

#?(:clj
(defmacro fn+ [sym & body]
  `(first (fn+* ~sym nil nil nil nil ~body))))

#?(:clj
(defmacro defn+ [sym & body]
  `(let [[f# meta#] (fn+* ~sym nil nil nil nil ~body)
         var# (doto (def ~sym f#)
                (alter-meta! map/merge meta#))]
     var#)))

#?(:clj
(defmacro defmethod+
  "Like |defmethod| but creates a named function called |this|
   for purposes of cross-arity recursion."
  {:attribution "alexandergunnarson"}
  [sym type & arities]
  (let [sym* (with-meta sym {:tag "clojure.lang.MultiFn"})]
    `(.addMethod ^MultiFn ~sym* ~type
       (fn+ this ~@arities)))))

#?(:clj
(defmacro defmethods+
  "Like |defmethod+| but uses the same fn for multiple dispatches."
  [sym dispatches & args]
  `(do ~@(for [dispatch dispatches]
         `(defmethod+ ~sym ~dispatch ~@args)))))
