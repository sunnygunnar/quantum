(ns
  ^{:doc "Asynchronous and thread-related functions."
    :attribution "alexandergunnarson"}
  quantum.core.async
  (:refer-clojure :exclude
    [locking
     promise deliver, delay force, realized? future repeatedly count
     reduce, for
     conj!, contains?
     map, map-indexed])
  (:require
    [clojure.core                      :as core]
    [com.stuartsierra.component        :as component]
    [clojure.core.async                :as async]
    [clojure.core.async.impl.protocols :as asyncp]
#?@(#_:clj
 #_[[co.paralleluniverse.pulsar.async  :as async+]
    [co.paralleluniverse.pulsar.core   :as pasync]])
    [quantum.core.collections          :as coll
      :refer [contains? count red-for break repeatedly
              reduce, #_->objects
              doseqi, red-for, for
              conj!
              map, map-indexed map-indexed+
              seq-and]]
    [quantum.core.data.vector          :as vec
      :refer [!+vector|sized]]
    [quantum.core.error                :as err
      :refer [>ex-info TODO catch-all]]
    [quantum.core.fn
      :refer [fnl fn1 fn-nil]]
    [quantum.core.log                  :as log]
    [quantum.core.logic                :as logic
      :refer [fn-and fn-or fn-not condpc whenc]]
    [quantum.core.macros               :as macros
      :refer [defnt]]
    [quantum.core.refs                 :as refs]
    [quantum.core.spec                 :as s
      :refer [validate]]
    [quantum.core.system               :as sys]
    [quantum.core.type           :as t
      :refer [val?]]
    [quantum.core.vars                 :as var
      :refer [defalias defmalias]]
    [quantum.untyped.core.form.evaluate :as ufeval
      :refer [case-env]]
    [quantum.untyped.core.string
      :refer [istr]]
    [quantum.untyped.core.type.predicates
      #?@(:cljs [:refer [defined?]])])
#?(:cljs
  (:require-macros
    [cljs.core.async.macros            :as asyncm]
    [quantum.core.async                :as self
      :refer [go]]))
#?(:clj
  (:import
    clojure.core.async.impl.channels.ManyToManyChannel
    [java.util.concurrent Future FutureTask TimeUnit]
    quantum.core.data.queue.LinkedBlockingQueue
    [co.paralleluniverse.strands.channels SendPort]
    co.paralleluniverse.strands.channels.ReceivePort
    co.paralleluniverse.fibers.Fiber
    co.paralleluniverse.strands.Strand)))

(log/this-ns)

; ===== LOCKS AND SEMAPHORES ===== ;

; `monitor-enter`, `monitor-exit`

; TODO incorporate/link all locking strategies in `net.openhft.chronicle.algo.locks.*`
#?(:clj (defalias locking core/locking))

; ===== CORE.ASYNC ETC. ===== ;

#?(:clj (defmalias go      clojure.core.async/go      cljs.core.async.macros/go))
#?(:clj (defmalias go-loop clojure.core.async/go-loop cljs.core.async.macros/go-loop))
#_(:clj (defalias  async go)) ; TODO fix this

#?(:clj
(defmacro <?*
  "Takes a value from a core.async channel, throwing the value if it
   is an error."
  [c takef]
 `(let [result# (~takef ~c)]
    (if (err/error? result#)
        (throw result#)
        result#))))

#?(:clj (defmacro <!?  [c] `(<?* ~c <!)))
#?(:clj (defmacro <!!? [c] `(<?* ~c <!!)))

#?(:clj (defmacro try-go [& body] `(go (catch-all (do ~@body) e# e#))))

(deftype QueueCloseRequest [])
(deftype TerminationRequest [])

(defalias buffer async/buffer)

; TODO (SynchronousQueue.) <-> (chan)
(defnt chan*
  "(chan (buffer n)) or (chan n) are the same as (channel n :block   ) or (channel n).
   (chan (dropping-buffer n))    is  the same as (channel n :drop    )
   (chan (sliding-buffer n))     is  the same as (channel n :displace)"
  ;([] (async+/chan)) ; can't have no-arg |defnt|
  ([#{#?(:clj integer? :cljs number?) #?(:clj clojure.core.async.impl.buffers.FixedBuffer)} n]
   (async/chan n)
   #_(async+/chan n))
  ([^keyword? type]
    (case type
      #_:std     #_(async+/chan)
      :queue   #?(:clj  (LinkedBlockingQueue.)
                  :cljs (TODO))
      :casync  (async/chan)))
  ([^keyword? type n]
    (case type
     #_:std     #_(async+/chan n)
      :casync  (async/chan n)
      :queue   #?(:clj  (LinkedBlockingQueue. ^Integer n)
                  :cljs (TODO))))) ; TODO reflection here

(defn chan
  ([         ] (async/chan) #_(async+/chan))
  ([arg0     ] (chan* arg0     ))
  ([arg0 arg1] (chan* arg0 arg1)))

; ----- DELAY ----- ;

(defalias delay core/delay)
(defalias force core/force)

(def timeout async/timeout) ; `defalias` here results in "java.lang.ClassCastException: clojure.lang.AFunction$1 cannot be cast to clojure.lang.IFn$LO"

#?(:clj (defalias thread async/thread))

;(defn current-strand [] (Strand/currentStrand))
#?(:clj (defn current-strand [] (Thread/currentThread)))
;(defn current-fiber  [] (or (Fiber/currentFiber) (current-strand)))

;(defalias buffer              #?(:clj async+/buffer              :cljs async/buffer             ))
;(defalias dropping-buffer     #?(:clj async+/dropping-buffer     :cljs async/dropping-buffer    ))
;(defalias sliding-buffer      #?(:clj async+/sliding-buffer      :cljs async/sliding-buffer     ))
;(defalias unblocking-buffer?  #?(:clj async+/unblocking-buffer?  :cljs async/unblocking-buffer? ))

(defalias poll! async/poll!)

(defalias take! async/take!)

(defalias <! async/<!)

#?(:clj
(defnt <!! ; receive
  ([^LinkedBlockingQueue x  ] (.take x))
  ([^LinkedBlockingQueue x n] (.poll x n TimeUnit/MILLISECONDS))
  ([^default             x  ] (async/<!! x))
  ([^default             x n] (first (async/alts!! [x (timeout n)])))
  #_([^co.paralleluniverse.strands.channels.ReceivePort   c  ] (async+/<! c))))

#?(:clj
(defnt empty!
  ([^LinkedBlockingQueue q] (.clear q))
  ([^m2m-chan?           c] (TODO)))) ; `drain!` TODO

(defalias offer! async/offer!)

(defalias put! async/put!)

(defalias >! async/>!)

#?(:clj
(defnt >!! ; send
  ([^LinkedBlockingQueue x v] (.put x v))
  ([^default             x v] (async/>!! x v))
  #_([^co.paralleluniverse.strands.channels.ReceivePort   x obj] (async+/>! x obj))))

(defnt message?
  ([^quantum.core.async.QueueCloseRequest  obj] false)
  ([^quantum.core.async.TerminationRequest obj] false)
  ([                    obj] (when (val? obj) true)))

(def close-req? (fnl instance? QueueCloseRequest))

(declare peek!!)

#?(:clj
(defnt peek!!
  "Blocking peek."
  ([^LinkedBlockingQueue q]         (.blockingPeek q))
  ([^LinkedBlockingQueue q timeout] (.blockingPeek q timeout (. TimeUnit MILLISECONDS)))
  ([^m2m-chan?           c] (TODO))))

(declare interrupt!)

#?(:clj
(defnt interrupt!
  ([#{Thread}         x] (.interrupt x)) ; `join` after interrupt doesn't work
  ([#{Process Future} x] nil))) ; .cancel?

#?(:clj
(defnt interrupted?*
  ([#{Thread Strand}  x] (.isInterrupted x))
  ([#{Process Future} x] (TODO))))

;#?(:clj
;(defn interrupted?
;  ([ ] (.isInterrupted ^Strand (current-strand)))
;  ([x] (interrupted?* x))))

(declare interrupted?)

#?(:clj ; TODO CLJS
(defnt close!
  ([^Thread              x] (.stop    x))
  ([^Process             x] (.destroy x))
  ([#{Future FutureTask} x] (.cancel x true))
  ([^default             x] (asyncp/close! x))
  ([#{LinkedBlockingQueue ReceivePort SendPort} x] (.close x))))

#?(:clj ; TODO CLJS
(defnt closed?
  ([^Thread                            x] (not (.isAlive x)))
  ([^Process                           x] (try (.exitValue x) true
                                            (catch IllegalThreadStateException _ false)))
  ([#{Fiber Future FutureTask}         x] (or (.isCancelled x) (.isDone x)))
  ([#{LinkedBlockingQueue ReceivePort} x] (.isClosed x))
  ([^boolean                           x] x)
  ([^default                           x] (asyncp/closed? x))))

#?(:clj (def open? (fn-not closed?))) ; TODO CLJS

#?(:clj
(defnt realized? ; TODO CLJS
  ([^clojure.lang.IPending x] (.isRealized x))
  #_([^co.paralleluniverse.strands.channels.QueueObjectChannel x] ; The result of a Pulsar go-block
    (-> x .getQueueLength (> 0)))
  ([#{Future FutureTask Fiber} x] (.isDone x))))

(defn ?offer!
  "If offering nil, will close the channel."
  [x v] (if (val? v) (offer! x v) (close! x)))

#?(:clj
(defmacro wait!
  "`wait` within a `go` block"
  [millis]
  `(<! (timeout ~millis))
  #_(case-env
    :clj
    #_(if (Fiber/currentFiber)
          (Fiber/sleep  ~millis)
          (Strand/sleep ~millis)))))

#?(:clj (defnt wait!! "Blocking wait" [^long millis] (Thread/sleep millis)))

; MORE COMPLEX OPERATIONS

; ----- ALTS ----- ;

(defalias alts! async/alts!)

; For some reason, having lots of threads with core.async/alts!! "clogs the tubes", as it were
; Possibly because of deadlocking?
; So we're moving away from core.async, but keeping the same concepts
#?(:clj
(defn alts!!-queue [chans timeout] ; Unable to mark ^:suspendable because of synchronization
  (loop []
    (let [result (red-for [c   chans
                           ret nil]
                   (locking c ; Because it needs to have a consistent view of when it's empty and take accordingly
                     (when (contains? c)
                       (reduced [(<!! c) c]))))]
      (whenc result nil?
        (do (wait!! 5)
            (recur)))))))

#?(:clj
(defnt alts!!
  "Takes the first available value from a chan."
  {:todo #{"Implement timeout"}
   :attribution "alexandergunnarson"}
  ([chans] (async/alts!! chans))
  ([^keyword? type chans]
    (alts!! type chans nil))
  #_([^sequential? chans]
    (async+/alts!! chans))
  #_([^sequential? chans timeout]
    (async+/alts!! chans timeout))
  ([^keyword? type chans timeout]
    (case type
      ;:std     (if timeout
      ;             (async+/alts!! chans timeout)
      ;             (async+/alts!! chans))
      :queue   (alts!!-queue chans (or timeout Integer/MAX_VALUE))
      :casync  (if timeout
                   (async/alts!! chans timeout)
                   (async/alts!! chans))))))

; ----- FUTURE ----- ;

#?(:clj
(defmacro future
  "`future` for Clojure aliases `clojure.core/future`.

   For ClojureScript, obviously there is no `cljs.core/future`, but this replicates some of the
   behavior of `clojure.core/future`, with some differences:

   Since the context of each of these 'threads'/web workers is totally separate from the main (UI)
   thread, then messages passed back and forth will be all these threads know of each other.
   This is why web-worker code that attempts to generate side-effects on application state doesn't
   work or can produce strange effects.

   However, web workers can produce side-effects on e.g. local browser cache or create HTTP requests."
  [& body]
  (case-env
    :clj  `(clojure.core/future ~@body)
    :cljs `(servant.core/servant-thread global-threadpool
             servant.core/standard-message dispatch (fn [] ~@body)))))

; TODO incorporate
; future-call #'clojure.core/future-call,
; future-cancel #'clojure.core/future-cancel,
; future-cancelled? #'clojure.core/future-cancelled?,
; future-done? #'clojure.core/future-done?,

; ----- THREAD-LOCAL ----- ;

#?(:clj
(defn thread-local*
  {:from "flatland.useful.utils"}
  [init]
  (let [generator (proxy [ThreadLocal] [] ; TODO this is slow
                    (initialValue [] (init)))]
    (reify clojure.lang.IDeref
      (deref [this] (.get generator))))))

#?(:clj
(defmacro thread-local
  "Takes a body of expressions, and returns a java.lang.ThreadLocal object.
   (see http://download.oracle.com/javase/6/docs/api/java/lang/ThreadLocal.html).
   To get the current value of the thread-local binding, you must deref (@) the
   thread-local object. The body of expressions will be executed once per thread
   and future derefs will be cached."
  {:from "flatland.useful.utils"}
  [& body]
  `(thread-local* (fn [] ~@body))))

; ----- MISC ----- ;

#?(:clj
(defmacro seq<!
  "Given `ports`, a seq, calls `<!` on each one in turn.
   Lets each port initiate — if e.g. `go` blocks, will
   initiate concurrently. Aggregates the results into a vector."
  [ports]
  `(let [ret# (core/transient [])]
     (core/doseq [p# ~ports] (core/conj! ret# (<! p#)))
     (core/persistent! ret#))))

#?(:clj (defn seq<!! [ports] (map (fn1 <!!) ports)))

(defalias timeout async/timeout)

#?(:clj
(defmacro wait-until*
  "Waits until the value of `pred` becomes truthy."
  ([sleepf pred]
    (let [max-num (case-env :clj 'Long/MAX_VALUE :cljs 'js/Number.MAX_SAFE_INTEGER)]
      `(wait-until* ~sleepf ~max-num ~pred)))
  ([sleepf timeout pred]
   `(loop [timeout# ~timeout]
      (if (<= timeout# 0)
          (throw (>ex-info :timeout ~(istr "Operation timed out after ~{timeout} milliseconds") ~timeout))
          (when-not ~pred
            (~sleepf 10) ; Sleeping so as not to take up thread time
            (recur (- timeout# 11)))))))) ; Takes a tiny bit more time

#?(:clj (defmacro wait-until!  [& args] `(wait-until* sleep!  ~@args)))
#?(:clj (defmacro wait-until!! [& args] `(wait-until* sleep!! ~@args)))

#?(:clj
(defmacro try-times* [waitf max-n wait-millis & body]
 `(let [max-n#       ~max-n
        wait-millis# ~wait-millis]
    (loop [n# 0 error-n# nil]
      (if (> n# max-n#)
          (throw (>ex-info :max-tries-exceeded nil
                       {:tries n# :last-error error-n#}))
          (let [[error# result#]
                  (try [nil (do ~@body)]
                    (catch ~(err/env>generic-error &env) e#
                      (~waitf wait-millis#)
                      [e# nil]))]
            (if error#
                (recur (inc n#) error#)
                result#)))))))

#?(:clj (defmacro try-times!  [& args] `(try-times* wait!  ~@args)))
#?(:clj (defmacro try-times!! [& args] `(try-times* wait!! ~@args)))

#?(:cljs
(def supports-web-workers? (defined? (.-Worker usys/global))))

(defn web-worker?
  "Checks whether the current thread is a WebWorker."
  []
  #?(:clj  false
     :cljs (and (-> sys/global .-self)
                (-> sys/global .-self .-document undefined?)
                (or (nil? sys/os) (= sys/os "web")))))

#_(:clj
(defn chunk-doseq
  "Like `fold` but for `doseq`.
   Also configurable by thread names and threadpool, etc."
  [coll {:keys [total thread-count chunk-size threadpool thread-name chunk-fn] :as opts} f]
  (let [total-f (or total (count coll))
        chunks (coll/partition-all (or chunk-size
                                       (/ total-f
                                          (min total-f (or thread-count 10))))
                 (if total (take total coll) coll))]
    (doseqi [chunk chunks i]
      (let [thread-id (keyword (str thread-name "-" i))]
        (async (mergel {:id thread-id} opts)
          ((or chunk-fn fn-nil) chunk i)
          (doseqi [piece chunk n]
            (f piece n chunk i chunks))))))))

; ----- PROMISE ----- ;

; TODO CLJS
(deftype Promise [c]
  #?@(:clj [clojure.lang.IDeref
              (deref [_] (<!! c))
            clojure.lang.IBlockingDeref
              (deref [_ timeout-ms timeout-val]
                (let [[v _] (alts!! [c (timeout timeout-ms)])]
                  (or v timeout-val)))])
            clojure.lang.IPending
              (isRealized [_] (val? (async/poll! c)))
            clojure.lang.IFn ; deliver
              (invoke [_ x] (async/offer! c x))
            asyncp/ReadPort
              (take! [_ handler] (asyncp/take! c handler))
            asyncp/WritePort
              (put! [_ x handler] (asyncp/put! c x handler))
            asyncp/Channel
              (close! [_] (asyncp/close! c))
              (closed? [_] (asyncp/closed? c)))

(defn readable-chan?  [x] (satisfies? asyncp/ReadPort x))
(defn writeable-chan? [x] (satisfies? asyncp/WritePort x))
(defn closeable-chan? [x] (satisfies? asyncp/Channel x))

(defn promise
  "A cross between a clojure `promise` and an `async/promise-chan`."
  ([] (promise nil))
  ([xf] (promise xf nil))
  ([xf ex-handler] (Promise. (async/promise-chan xf ex-handler))))

(deftype MultiplexedPromise [cs #_vector?]
  #?@(:clj [clojure.lang.IDeref
              (deref [_] (map deref cs))
            clojure.lang.IBlockingDeref
              (deref [_ timeout-ms timeout-val]
                (map (fn1 deref timeout-ms timeout-val) cs))])
            clojure.lang.IPending
              (isRealized [_] (seq-and (fn1 realized?) cs))
            clojure.lang.IFn ; deliver
              (invoke [_ x] (map (fn [c] (c x)) cs))
            asyncp/ReadPort
              (take! [_ handler]
                (let [promises (for [_ cs] (promise))
                      _ (->> cs
                             (map-indexed+
                               (fn [i c]
                                 (let [p      (get promises i)
                                       polled (poll! c)]
                                   (if (val? polled)
                                       (offer! p polled)
                                       (async/take! c (fn [resp] (?offer! p resp)))))))
                             coll/doreduce)
                      all-realized? (seq-and (fn1 realized?) promises)]
                  (if all-realized?
                      (refs/->derefable (map poll! promises))
                      (do (go ((asyncp/commit handler) (seq<! promises)))  ; TODO reflection on .nth but .nth is not found in `macros/macroexpand-all` ...
                          nil))))
            asyncp/WritePort
              (put! [_ x handler]
                (let [promises (for [_ cs] (promise))
                      all-puts-succeeeded?
                        (->> cs
                             (map-indexed+
                               (fn [i c]
                                 (async/put! c x (fn [resp] (?offer! (get promises i) resp)))))
                             seq-and)]
                  (go ((asyncp/commit handler) (seq<! promises))) ; TODO reflection on .nth but .nth is not found in `macros/macroexpand-all` ...
                  (refs/->derefable all-puts-succeeeded?)))
            asyncp/Channel
              (close!  [_] (map asyncp/close!  cs))
              (closed? [_] (map asyncp/closed? cs)))

(defnt promise? ([#{Promise MultiplexedPromise} x] true) ([^default x] false)) ; TODO what about Clojure promises or JS built-in ones?

(defnt deliver>!! [#{Promise MultiplexedPromise} p v] (>!! p v))

(defnt delivered? [#{Promise MultiplexedPromise} p] (realized? p))

(defnt request-stop!   [#{Promise MultiplexedPromise} p] (offer! p true))
(defnt stop-requested? [#{Promise MultiplexedPromise} p] (realized? p)) ; TODO fix this
(defnt stopping?       [#{Promise MultiplexedPromise} p] (and (realized? p) (not (closed? p))))
(defnt stopped?        [#{Promise MultiplexedPromise} p] (and (realized? p) (closed? p)))

(defnt <status:stop-ch [#{Promise MultiplexedPromise} p]
  (if (realized? p)
      (if (closed? p)
          :stopped
          :stopping)
      (if (closed? p)
          :stopped-without-request
          :running)))

(defnt mark-stopped!   [#{Promise MultiplexedPromise} p] (request-stop! p) (close! p))

(defn multiplex-promises
  "Multiplexes n promises such that whatever is done to the multiplexed promise is
   done to all promises (`close!` closes both, a `put!` will go to both, etc.).
   Reads come back as a vector of values taken from the promises in the order
   passed to `multiplex-promises`."
  ([p0] p0)
  ([p0 p1] (MultiplexedPromise. [p0 p1]))
  ([p0 p1 & ps] (MultiplexedPromise. (apply vector p0 p1 ps))))

; ----- Neither `take!` nor `put!` ----- ;

(defn do-interval
  "Calls `f` every `wait-ms`. Returns a stop-chan."
  [f #_fn? wait-ms #_pos-integer?]
  (let [stop-ch   (promise)
        execution (go-loop []
                    (when-not (core/realized? stop-ch) ; TODO issues here prevent using `delivered?`
                      (f)
                      (wait! wait-ms)
                      (recur)))]
    stop-ch))

; ----- `take!`s ----- ;

;; TODO take another look at this given transducers
(defn each!
  "Calls `f` on `take!`n values from `from` until stopped or `from` is closed.
   Returns a stop-chan."
  [from f]
  (let [stop-ch (promise)]
    (go (try
          (loop []
            (let [v (<! from)]
              (when-not (or (nil? v) (core/realized? stop-ch)) ; TODO issues here prevent using `delivered?`
                (f v)
                (recur))))
          (finally (mark-stopped!-protocol stop-ch)))) ; TODO deprotocolize
    stop-ch))

; ----- PIPING ----- ;

(defn pipe!*
  "Like `pipe`, but instead of the `to` chan, returns a promise(-chan) by which the
   pipe process can be stopped.
   Like in other places, the `stop` chan is offered `true` and closed when the pipe
   is closed."
  ([from to       ] (pipe!* from to true))
  ([from to close?]
    (let [stop-ch (promise)]
      (go (try
            (loop []
              (let [v (<! from)]
                (if (or (nil? v) (core/realized? stop-ch)) ; TODO issues here prevent using `delivered?`
                    (when close? (async/close! to)) ; TODO issues with using `close!` here
                    (when (>! to v)
                      (recur)))))
            (finally (mark-stopped!-protocol stop-ch)))) ; TODO deprotocolize
      stop-ch)))

(defrecord
  ^{:doc "`error`   : The actual error that was thrown
          `failure` : The item that e.g. the pipeline was working on when the error occurred"}
  PipelineFailure [error failed]) ; TODO use `ex-info` to capture this ?

; TODO CLJS
#?(:clj
(defn pipeline!*
  "Exactly the same as `pipeline` but the arguments are reordered in a more reasonable
   way, must pass a kind `#{:!! :blocking :compute :! :async}`, and allows for arbitrary
   stopping of the pipeline via the promise(-chan) that is returned.

   When stopped, will not continue to take from `from` chan, but will finish up
   pending jobs before terminating."
  ([kind conc from xf to                  ] (pipeline!* kind conc from xf to true))
  ([kind conc from xf to close?           ] (pipeline!* kind conc from xf to close? nil))
  ([kind conc from xf to close? ex-handler]
     (assert (pos? conc))
     (let [stop-ch (promise)
           ex-handler
             (or ex-handler
                 (fn [ex]
                   (-> (Thread/currentThread)
                       .getUncaughtExceptionHandler
                       (.uncaughtException (Thread/currentThread) ex))
                   nil))
           jobs    (chan conc)
           results (chan conc)
           process (fn [[v p :as job]]
                     (if (nil? job)
                         (do (close! results) nil)
                         (let [res (async/chan 1 xf ex-handler)] ; TODO async/chan -> chan
                           (>!! res v)
                           (close! res)
                           (put! p res)
                           true)))
           async (fn [[v p :as job]]
                   (if (nil? job)
                       (do (close! results) nil)
                       (let [res (chan 1)]
                         (xf v res)
                         (put! p res)
                         true)))]
       (dotimes [_ conc]
         (case kind
           (:!! :blocking) (thread
                             (let [job (<!! jobs)]
                               (when (process job)
                                 (recur))))
           :compute        (go-loop []
                             (let [job (<! jobs)]
                               (when (process job)
                                 (recur))))
           (:! :async)     (go-loop []
                             (let [job (<! jobs)]
                               (when (async job)
                                 (recur))))))
       (go (loop []
             (let [v (<! from)]
               (if (or (nil? v) (and stop-ch (val? (poll! stop-ch))))
                   (async/close! jobs) ; TODO fix this to use this ns/`close!`
                   (let [p (chan 1)]
                     (>! jobs [v p])
                     (>! results p)
                     (recur))))))
       (go-loop []
         (let [p (<! results)]
           (if (nil? p)
               (when close? (async/close! to)) ; TODO fix this to use this ns/`close!`
               (let [res (<! p)]
                 (loop []
                   (let [v (<! res)]
                     (when (and (not (nil? v)) (>! to v))
                       (recur))))
                 (recur)))))
       stop-ch))))

; TODO CLJS
#?(:clj
(defn concur-each!*
  "Concurrent processing of a source chan for side effects à la `each`.
   Similar to `pipeline!*`, but does not offload the results of the concurrent
   processing of the source chan onto a sink chan."
  {:todo #{"Refactor code shared with `pipeline!*` into somewhere else"}}
  ([kind conc from xf           ] (concur-each!* kind conc from xf nil))
  ([kind conc from xf ex-handler]
    (assert (pos? conc))
    (let [stop-ch (promise)
          ex-handler
            (or ex-handler
                (fn [ex]
                  (-> (Thread/currentThread)
                      .getUncaughtExceptionHandler
                      (.uncaughtException (Thread/currentThread) ex))
                  nil))
          jobs    (chan conc)
          process (fn [[v p :as job]]
                    (if (nil? job)
                        nil
                        (let [res (async/chan 1 xf ex-handler)]  ; TODO async/chan -> chan
                          (>!! res v)
                          (close! res)
                          (put! p res)
                          true)))
          async   (fn [[v p :as job]]
                    (if (nil? job)
                        nil
                        (let [res (chan 1)]
                          (xf v res)
                          (put! p res)
                          true)))]
      (dotimes [_ conc]
        (case kind
          (:!! :blocking) (thread
                            (let [job (<!! jobs)]
                              (when (process job) (recur))))
          :compute        (go-loop []
                            (let [job (<! jobs)]
                              (when (process job) (recur))))
          (:! :async)     (go-loop []
                            (let [job (<! jobs)]
                              (when (async job) (recur))))))
      (go-loop []
        (let [v (<! from)]
          (if (or (nil? v) (and stop-ch (val? (poll! stop-ch))))
              (async/close! jobs) ; TODO fix this to use this ns/`close!`
              (let [p (chan 1)]
                (>! jobs [v p])
                (recur)))))
      stop-ch))))

(defn pipe-interruptibly<!
  "Blocking-takes when `process` returns a channel."
  [{:keys [from-ch to-ch failures-ch stop-ch
           processf
           on-exited-via-exception
           on-exited-via-stop
           on-exited-normally]}]
  (let [stop-ch                  (or stop-ch (promise))
        on-exited-normally'      #(do (mark-stopped! stop-ch)
                                      ((or on-exited-normally fn-nil))
                                      nil)
        on-exited-via-stop'      #(do (mark-stopped! stop-ch)
                                      ((or on-exited-via-stop fn-nil))
                                      nil)
        on-exited-via-exception' (fn [e] (mark-stopped! stop-ch)
                                         ((or on-exited-via-exception fn-nil) e)
                                         nil)
        ; Catches issues with specifically the `processf` function
        on-exception             (if failures-ch
                                     (fn [e x] (put! failures-ch (PipelineFailure. e x)) nil)
                                     (fn [e _] (on-exited-via-exception' e)))]
    (go (loop []
          (let [[x ch] (catch-all (alts! [stop-ch from-ch])
                         e (on-exited-via-exception' e))]
            (cond (= ch stop-ch)
                    (on-exited-via-stop')
                  (val? x)
                    (when
                      (catch-all
                        (let [[ret ch']
                                (catch-all
                                  (let [ret (processf x)]
                                    (if (readable-chan? ret)
                                        (alts! [stop-ch ret])
                                        [ret nil]))
                                  e (on-exception e x))]
                          (cond (= ch' stop-ch)
                                  (on-exited-via-stop')
                                (and (val? ret) to-ch)
                                  (do (put! to-ch ret) true)
                                :else
                                  true))
                        e (on-exited-via-exception' e))
                      (recur))
                  :else
                    (on-exited-normally')))))))

#?(:clj
(defn pipe-interruptibly<!!
  "Like `pipe-interruptibly<!` but uses `thread` instead of `go`."
  {:todo #{"Combine code with `pipe-interruptibly<!`"}}
  [{:keys [from-ch to-ch failures-ch stop-ch
           processf
           on-exited-via-exception
           on-exited-via-stop
           on-exited-normally]}]
  (let [stop-ch                  (or stop-ch (promise))
        on-exited-normally'      #(do (mark-stopped! stop-ch)
                                      ((or on-exited-normally fn-nil))
                                      nil)
        on-exited-via-stop'      #(do (mark-stopped! stop-ch)
                                      ((or on-exited-via-stop fn-nil))
                                      nil)
        on-exited-via-exception' (fn [e] (mark-stopped! stop-ch)
                                         ((or on-exited-via-exception fn-nil) e)
                                         nil)
        ; Catches issues with specifically the `processf` function
        on-exception             (if failures-ch
                                     (fn [e x] (put! failures-ch (PipelineFailure. e x)) nil)
                                     (fn [e _] (on-exited-via-exception' e)))]
    (thread
      (loop []
        (let [[x ch] (catch-all (alts!! [stop-ch from-ch])
                       e (on-exited-via-exception' e))]
          (cond (= ch stop-ch)
                  (on-exited-via-stop')
                (val? x)
                  (when
                    (catch-all
                      (let [[ret ch']
                              (catch-all
                                (let [ret (processf x)]
                                  (if (readable-chan? ret)
                                      (alts!! [stop-ch ret])
                                      [ret nil]))
                                e (on-exception e x))]
                        (cond (= ch' stop-ch)
                                (on-exited-via-stop')
                              (and (val? ret) to-ch)
                                (do (put! to-ch ret) true)
                              :else
                                true))
                      e (on-exited-via-exception' e))
                    (recur))
                :else
                  (on-exited-normally')))))
    stop-ch)))

#?(:clj
(defmacro handle-timeout! [[v c timeout-ms] form & body]
 `(let [timeout# (clojure.core.async/timeout ~timeout-ms) ; TODO use async/
        [~v c-alt#] (alts! [~c timeout#])]
    (~form (= c-alt# timeout#) ~@body))))

#?(:clj (defmacro if-timeout!   [[v c timeout-ms] then else] `(handle-timeout! [~v ~c ~timeout-ms] if   ~then ~else)))
#?(:clj (defmacro when-timeout! [[v c timeout-ms] & body]    `(handle-timeout! [~v ~c ~timeout-ms] when ~@body)))

#?(:cljs
(def request-animation-frame
  (or
   (.-requestAnimationFrame       sys/global)
   (.-webkitRequestAnimationFrame sys/global)
   (.-mozRequestAnimationFrame    sys/global)
   (.-msRequestAnimationFrame     sys/global)
   (.-oRequestAnimationFrame      sys/global)
   (let [t0 (.getTime (js/Date.))]
     (fn [f]
       (js/setTimeout
        #(f (- (.getTime (js/Date.)) t0))
        16.66666))))))
