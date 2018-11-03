(ns quantum.untyped.core.data.reactive
  "Most of the content adapted from `reagent.ratom` 2018-10-20. Note that `lynaghk/reflex` was the
   source of the Reagent Atom and Reaction (and before that https://knockoutjs.com/documentation/computedObservables.html, and before that probably
   something else), and it makes do with 78 LOC (!) whereas we grapple with nearly 400 for
   presumably very similar functionality. Perhaps someday this code can be compressed.

   Includes `Reference` and `Reaction`; may include `Subscription` at some point.

   Currently only safe for single-threaded use; needs a rethink to accommodate concurrent
   modification/access and customizable queueing strategies.
   - We could either introduce concurrency-safe versions of `Reaction` and `Reference`, or we
     could introduce a global single thread on which `Reaction`s and `Reference`s are modified,
     but from which any number of threads can read, in a clojure.async sort of way."
        (:require
          [clojure.core                               :as core]
          [clojure.set                                :as set]
          [quantum.untyped.core.async                 :as uasync]
          [quantum.untyped.core.data.vector
            :refer [alist alist== alist-conj! alist-count alist-empty! alist-get]]
          [quantum.untyped.core.error                 :as uerr]
          [quantum.untyped.core.form.generate.deftype :as udt]
          [quantum.untyped.core.log                   :as ulog]
          [quantum.untyped.core.logic
            :refer [ifs]]
          [quantum.untyped.core.refs                  :as uref]
          [quantum.untyped.core.vars
            :refer [defonce-]])
#?(:clj (:import [java.util ArrayList])))

;; ===== Internal functions for reactivity ===== ;;

(def ^:dynamic *ref-context* nil)

(def ^:dynamic #?(:clj *debug?* :cljs ^boolean *debug?*) false)

(defonce- *running (core/atom 0))

(defonce global-queue (alist))

(defn- check-watches [old new]
  (when (true? *debug?*) (swap! *running + (- (count new) (count old))))
  new)

(defn norx-deref [rx]
  (binding [*ref-context* nil]
    #?(:clj  (.deref ^clojure.lang.IDeref rx)
       :cljs (-deref ^non-native          rx))))

(defprotocol PWatchable
  (getWatches [this])
  (setWatches [this v]))

(defn- add-w! [^quantum.untyped.core.data.reactive.PWatchable x k f]
  (let [w (.getWatches x)]
    (.setWatches x (check-watches w (assoc w k f)))
    x))

(defn- remove-w! [^quantum.untyped.core.data.reactive.PWatchable x k]
  (let [w (.getWatches x)]
    (.setWatches x (check-watches w (dissoc w k)))
    x))

(defn- conj-kv! [#?(:clj ^ArrayList xs :cljs xs) k v]
  (-> xs (alist-conj! k) (alist-conj! v)))

(defn- notify-w! [^quantum.untyped.core.data.reactive.PWatchable x old new]
  ;; Unlike Reagent, we do not copy to an array-list because in order to do so, we have to traverse
  ;; the map anyway if the watches have changed. Plus we avoid garbage (except for the closure).
  ;; Reagent optimizes for the case that watches will more rarely change than not. It would be nice
  ;; to avoid that tradeoff by having a sufficiently fast reduction.
  (when-some [w #?(:clj ^clojure.lang.IKVReduce (.getWatches x) :cljs ^non-native (.getWatches x))]
    (#?(:clj .kvreduce :cljs -kv-reduce) w (fn [_ k f] (f k x old new)) nil))
  x)

#?(:cljs
(defn- pr-ref! [a writer opts s]
  (-write writer (str "#<" s " "))
  (pr-writer (binding [*ref-context* nil] (-deref ^non-native a)) writer opts)
  (-write writer ">")))

;; ===== Reference ===== ;;

(defprotocol PReactive)

(defprotocol PHasCaptured
  (getCaptured [this])
  (setCaptured [this v]))

(defn- notify-deref-watcher!
  "Add `derefed` to the `captured` field of `*ref-context*`.

  See also `in-context`"
  [derefed]
  (when-some [context *ref-context*]
    (let [^quantum.untyped.core.data.reactive.PHasCaptured r context]
      (if-some [c (.getCaptured r)]
        (alist-conj! c derefed)
        (.setCaptured r (alist derefed))))))

;; TODO use `loop` with array for interceptors rather than creating a closure every time
(defn- gen-call|rf [r oldv]
  (fn ([newv'] newv')
      ([newv' [k f]] (f r k oldv newv'))
      ([newv'  k f]  (f r k oldv newv'))))

;; Note that `interceptors` are all deref-capturing
(udt/deftype Reference [^:! state meta validator ^:! watches ^:! interceptors]
  {;; IPrintWithWriter
   ;;   (-pr-writer [a w opts] (pr-ref a w opts "Reference:"))
   PReactive nil
   ?Equals {=     ([this that] (identical? this that))}
   ?Deref  {deref ([this]
                    (notify-deref-watcher! this)
                    state)}
   uref/PMutableReference
     {get  ([this] (norx-deref this))
      set! ([this newv]
             (when-not (nil? validator)
               (assert (validator newv) "Validator rejected reference state"))
             (let [oldv state]
               (if (identical? oldv newv)
                   newv
                   (let [oldv state]
                     (set! state (if (nil? interceptors)
                                     newv
                                     ;; TODO room for optimization here — e.g. use array for interceptors, with `loop`
                                     (reduce-kv (gen-call|rf this oldv) newv interceptors)))
                     (when-not (nil? watches) (notify-w! this oldv newv))
                     newv))))}
   ?Watchable {add-watch!    ([this k f] (add-w!    this k f))
               remove-watch! ([this k]   (remove-w! this k))}
   PWatchable {getWatches    ([this]     watches)
               setWatches    ([this v]   (set! watches v))}
   uref/PInterceptable
     {add-interceptor! ([this k f] (set! interceptors (assoc interceptors k f)))}
   ?Meta      {meta          ([_] meta)
               with-meta     ([_ meta'] (Reference. state meta' validator watches interceptors))}
#?@(:cljs [?Hash {hash    ([_] (goog/getUid this))}])})

(defn !
  "Reactive '!' (single-threaded mutable reference). Like `ref/!`, except that it keeps track of
   derefs."
  ([x] (Reference. x nil nil nil nil))
  ([x validator] (Reference. x nil validator nil nil)))

;; ===== Reaction ("Computed Observable") ===== ;;

;; Similar to java.io.Closeable
;; TODO move
(defprotocol PDisposable
  (dispose      [this])
  (addOnDispose [this f]))

(defn dispose!        [x]   (dispose      x))
(defn add-on-dispose! [x f] (addOnDispose x f))

(declare flush! run-reaction! update-watching!)

;; Note that `interceptors` are all deref-capturing
(udt/deftype Reaction
  [^:! ^boolean ^:get       alwaysRecompute
   ^:!          ^:get ^:set caught
   ^:!                      captured
   ^:! ^boolean ^:get ^:set computed
                            enqueue-fn
                            eq-fn
                            f
       ^boolean             no-cache?
   ^:!                      on-dispose
   ^:!                      on-dispose-arr
                            queue
   ^:!          ^:get ^:set state
   ^:!          ^:get ^:set watching ; i.e. 'dependents'
   ^:!                      watches       ; TODO consider a mutable map for `watches`
   ^:!          ^:get       interceptors] ; TODO consider a mutable map for `interceptors`
  {;; IPrintWithWriter
   ;;   (-pr-writer [a w opts] (pr-ref a w opts (str "Reaction " (hash a) ":")))
   ?Equals {= ([this that] (identical? this that))}
#?@(:cljs [?Hash {hash ([this] (goog/getUid this))}])
   PReactive  nil
   ?Deref     {deref ([this]
                       (if-not (nil? caught)
                         (throw caught)
                         (let [non-reactive? (nil? *ref-context*)]
                           (when non-reactive? (flush! queue))
                           (if (and non-reactive? alwaysRecompute)
                               (when-not computed
                                 (let [old-state state]
                                   (set! state
                                     (if (nil? interceptors)
                                         (f)
                                         (reduce-kv (gen-call|rf this old-state) (f) interceptors)))
                                   (when-not (or (nil? watches) (eq-fn old-state state))
                                     (notify-w! this old-state state))))
                               (do (notify-deref-watcher! this)
                                   (when-not computed (run-reaction! this false))))
                           state)))}
   uref/PMutableReference {get ([this] (norx-deref this))}
   ?Watchable {add-watch!    ([this k f] (add-w! this k f))
               remove-watch! ([this k]
                               (let [was-empty? (empty? watches)]
                                 (remove-w! this k)
                                 (when (and (not was-empty?)
                                            (empty? watches)
                                            (true? alwaysRecompute))
                                   (.dispose this))))}
   PWatchable {getWatches ([this]   watches)
               setWatches ([this v] (set! watches v))}
   uref/PInterceptable
     {add-interceptor! ([this k f] (set! interceptors (assoc interceptors k f)))}
   PHasCaptured
     {getCaptured ([this]   captured)
      setCaptured ([this v] (set! captured v))}
   PDisposable
     {dispose
       ([this]
         (let [s state, wg watching]
           (set! watching        nil)
           (set! state           nil)
           (set! alwaysRecompute #?(:clj (boolean true)  :cljs true))
           (set! computed        #?(:clj (boolean false) :cljs false))
           (doseq [w (set wg)] (#?(:clj remove-watch :cljs -remove-watch) w this))
           (set! interceptors    nil)
           (when (some? on-dispose) (on-dispose s))
           (when-some [a on-dispose-arr]
             (dotimes [i (long (alist-count a))] ((alist-get a i) this)))))
      addOnDispose
        ([this f]
          ;; f is called with the reaction as argument when it is no longer active
          (if-some [a on-dispose-arr]
            (alist-conj! a f)
            (set! on-dispose-arr (alist f))))}})

(defn- deref-capture!
  "When `f` is executed, if `(f)` and/or `interceptors` deref any reactive references, they are then
   added to `(.-captured rx)` (i.e. `*ref-context*`). Then calls `update-watching!` on `rx` with any
   `deref`ed reactive references captured, if any differ from the `watching` field of `rx`. Sets
   the `computed` flag on `rx` to true.

   Inside `update-watching!` along with adding the references in `(-.watching rx)` of reaction, the
   reaction is also added to the list of watches on each of the references that `f`+`interceptors`
   deref.

   See `notify-deref-watcher!` to know how `*ref-context*` is updated."
  [^Reaction rx]
  (.setCaptured rx nil)
  (let [oldv         (.getState rx)
        interceptors (.getInterceptors rx)
        newv         (binding [*ref-context* rx]
                       (if (nil? interceptors)
                           ((.-f rx))
                           (reduce-kv (gen-call|rf rx oldv) ((.-f rx)) interceptors)))
        c            (.getCaptured rx)]
    (.setComputed rx true)
    ;; Optimize common case where derefs occur in same order
    (when-not (alist== c (.getWatching rx)) (update-watching! rx c))
    newv))

(defn- try-capture! [^Reaction rx]
  (uerr/catch-all
    (do (.setCaught rx nil)
        (deref-capture! rx))
    e
    (do (.setState  rx e)
        (.setCaught rx e)
        (.setComputed rx true))))

(defn- run-reaction! [^Reaction rx check?]
  (let [old-state (.getState rx)
        new-state (if check?
                      (try-capture!   rx)
                      (deref-capture! rx))]
    (when-not (.-no-cache? rx)
      (.setState rx new-state)
      (when-not (or (nil? (.getWatches rx))
                    ((.-eq-fn rx) old-state new-state))
        (notify-w! rx old-state new-state)))
    new-state))

(defn- handle-reaction-change! [^Reaction rx sender oldv newv]
  (when-not (or (identical? oldv newv) (not (.getComputed rx)))
    (if (.getAlwaysRecompute rx)
        (do (.setComputed rx false)
            ((.-enqueue-fn rx) (.-queue rx) rx))
        (run-reaction! rx false))))

(defn- update-watching! [^Reaction rx derefed]
  (let [new (set derefed) ; TODO incrementally calculate `set`
        old (set (.getWatching rx))] ; TODO incrementally calculate `set`
    (.setWatching rx derefed)
    (doseq [w (set/difference new old)] ; TODO optimize
      (#?(:clj add-watch    :cljs -add-watch)    w rx handle-reaction-change!))
    (doseq [w (set/difference old new)] ; TODO optimize
      (#?(:clj remove-watch :cljs -remove-watch) w rx))))

(defn- run-reaction-from-queue! [^Reaction rx]
  (when-not (or (.getComputed rx) (nil? (.getWatching rx)))
    (run-reaction! rx true)))

(defn flush! [queue]
  (loop [i 0]
    (let [ct (-> queue alist-count long)]
      ;; NOTE: We avoid `pop`-ing in order to reduce churn but in theory it presents a memory issue
      ;;       due to the possible unboundedness of the queue
      ;; NOTE: In the Reagent version, every time a new "chunk" of the queue is worked on, that
      ;;       chunk is scheduled for re-render
      ;; I.e. took care of all queue entries and reached a stable state
      (if-let [reached-last-index? (>= i ct)]
        (alist-empty! queue)
        (let [remaining-ct (unchecked-subtract ct i)]
          (dotimes [i* remaining-ct]
            (run-reaction-from-queue! (alist-get queue (unchecked-add i i*))))
          ;; `recur`s because sometimes the queue gets added to in the process of running rx's
          (recur (+ i remaining-ct)))))))

(defn- default-enqueue! [queue rx]
  ;; Immediate run without touching the queue
  (run-reaction-from-queue! rx))

(def ^:dynamic *enqueue!* default-enqueue!)

(def ^:dynamic *queue* global-queue)

(defn ^Reaction >!rx
  ([f] (>!rx f nil))
  ([f {:keys [always-recompute? enqueue-fn eq-fn no-cache? on-dispose queue]}]
    (Reaction. (if (nil? always-recompute?) false always-recompute?)
               nil
               nil
               false
               (or enqueue-fn *enqueue!*)
               (or eq-fn =)
               f
               (if (nil? no-cache?) false no-cache?)
               on-dispose
               nil
               (or queue *queue*)
               nil nil nil nil)))

#?(:clj (defmacro !rx "Creates a single-threaded reaction." [& body] `(>!rx (fn [] ~@body))))

#?(:clj (defmacro !eager-rx [& body] `(>!rx (fn [] ~@body) {:always-recompute? true})))

#?(:clj
(defmacro !run-rx
  "Runs body immediately, and runs again whenever reactive references deferenced in the body
   change. Body should side effect."
  [& body] `(doto (!rx ~@body) deref)))

;; ===== Track ===== ;;

(udt/deftype TrackableFn [f ^:! ^:get ^:set rxCache])

(declare cached-reaction)

;; For perf test in `quantum.test.untyped.core.data.reactive`. TODO excise?
(udt/deftype Track
  [^TrackableFn trackable-fn, args, ^:! ^:get ^:set ^quantum.untyped.core.data.reactive.Reaction rx]
  {;; IPrintWithWriter
   ;;   (-pr-writer [a w opts] (pr-ref a w opts "Track:"))
   PReactive nil
   ?Deref  {deref ([this]
                    (if (nil? rx)
                        (cached-reaction #(apply (.-f trackable-fn) args)
                          trackable-fn args this nil)
                        #?(:clj (.deref rx) :cljs (-deref ^non-native rx))))}}
   ?Equals {=     ([_ other]
                    (and (instance? Track other)
                         (-> ^Track other .-trackable-fn .-f (= (.-f trackable-fn)))
                         (-> ^Track other .-args             (= args))))}
   ?Hash   {hash  ([_] (hash [f args]))})

(defn- cached-reaction [f ^TrackableFn trackable-fn k ^Track t destroy-fn]
  (let [          m (.getRxCache trackable-fn)
                  m (if (nil? m) {} m)
        ^Reaction r (m k nil)]
    (cond
      (some? r) #?(:clj (.deref r) :cljs (-deref ^non-native r))
      (nil? *ref-context*) (f)
      :else (let [r (>!rx f
                      {:on-dispose
                        (fn [x]
                          (when (true? *debug?*) (swap! *running dec))
                          (as-> (.getRxCache trackable-fn) cache
                            (dissoc cache k)
                            (.setRxCache trackable-fn cache))
                          (when (some? t)
                            (.setRx t nil))
                          (when (some? destroy-fn)
                            (destroy-fn x)))
                       ;; Inherits the queue
                       :queue (some-> t .getRx .-queue)})
                  v #?(:clj (.deref r) :cljs (-deref ^non-native r))]
              (.setRxCache trackable-fn (assoc m k r))
              (when (true? *debug?*) (swap! *running inc))
              (when (some? t)
                (.setRx t r))
              v))))

(defn ^Track >track [f args] (Track. (TrackableFn. f nil) args nil))

(defn >track! [f args opts]
  (let [t (>track f args)
        r (>!rx (fn [] #?(:clj (.deref t) :cljs (-deref ^non-native t)))
                {:queue (or (:queue opts) global-queue)})]
    @r
    r))

(defn #?(:clj reactive? :cljs ^boolean reactive?) [x] (satisfies? PReactive x))
