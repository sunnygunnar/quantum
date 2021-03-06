(ns quantum.core.async.pool
  (:refer-clojure :exclude
    [for, update, assoc-in, key val, when-let])
  (:require
    [clojure.core                :as core]
    [com.stuartsierra.component  :as comp]
#?(:cljs
    [servant.core                :as servant])
    [quantum.core.async          :as async
      :refer [close! go-loop put!]]
    [quantum.core.core           :as qcore]
    [quantum.core.data.map       :as map]
    [quantum.core.data.set       :as set]
    [quantum.core.data.validated :as dv]
    [quantum.core.fn
      :refer [<- fn1 fn& fnl, fn-> fn->>, call with-do]]
    [quantum.core.collections    :as c
      :refer [for, kw-map, update, assoc-in, join, key val, updates
              map-keys+, map-vals']]
    [quantum.core.error          :as err
      :refer [>ex-info catch-all TODO]]
    [quantum.core.log            :as log]
    [quantum.core.logic
      :refer [fn-and default whenp1 whenf1 ifp1 when-let]]
    [quantum.core.macros         :as macros
      :refer [defnt]]
    [quantum.core.reflect        :as refl]
    [quantum.core.refs           :as refs
      :refer [fref]]
    [quantum.core.spec           :as s
      :refer [validate]]
    [quantum.core.resources      :as res]
    [quantum.core.time.core      :as time]
    [quantum.measure.convert     :as uconv]
    [quantum.core.type           :as t
      :refer [atom?]]
    [quantum.core.vars           :as var])
#?(:cljs
  (:require-macros
    [servant.macros              :as servant
      :refer [defservantfn]]))
#?(:clj
  (:import
    [java.util.concurrent
       Executor ExecutorService ScheduledExecutorService ThreadPoolExecutor Executors
       ForkJoinPool ForkJoinWorkerThread ForkJoinPool$ForkJoinWorkerThreadFactory
       ThreadFactory
       LinkedBlockingQueue SynchronousQueue])))

#?(:clj
(defn pool>map [^ExecutorService pool]
  (let [base {:shut-down?  (.isShutdown   pool)
              :terminated? (.isTerminated pool)}
        specific (cond (instance? ForkJoinPool pool)
                       (let [^ForkJoinPool pool pool]
                         {:status  {:quiescent?         (.isQuiescent              pool)
                                    :shut-down?         (.isShutdown               pool)
                                    :terminated?        (.isTerminated             pool)
                                    :terminating?       (.isTerminating            pool)}
                          :threads {:active-threads     (.getActiveThreadCount     pool)
                                    :running-threads    (.getRunningThreadCount    pool)
                                    :queued-submissions (.getQueuedSubmissionCount pool)
                                    :queued-tasks       (.getQueuedTaskCount       pool)}
                          :config  {:async-mode?        (.getAsyncMode             pool)
                                    :parallelism        (.getParallelism           pool)
                                    :pool-size          (.getPoolSize              pool)}})
                       (instance? ThreadPoolExecutor pool)
                       (let [^ThreadPoolExecutor pool pool]
                         {:status  {:shut-down?                  (.isShutdown              pool)
                                    :terminated?                 (.isTerminated            pool)
                                    :terminating?                (.isTerminating           pool)}
                          :threads {:active-threads              (.getActiveCount          pool)
                                    :queued-tasks                (-> pool .getQueue count)
                                    :completed-tasks             (.getCompletedTaskCount   pool)
                                    :tasks                       (.getTaskCount            pool)}
                          :config  {:core-pool-size              (.getCorePoolSize         pool)
                                    :largest-pool-size           (.getLargestPoolSize      pool)
                                    :max-pool-size               (.getMaximumPoolSize      pool)
                                    :allows-core-thread-timeout? (.allowsCoreThreadTimeOut pool)
                                    :keep-alive-time             (.getKeepAliveTime        pool java.util.concurrent.TimeUnit/MILLISECONDS)}}))]
    (merge base specific))))

; ===== SCHEDULING ===== ;

#?(:clj
(defrecord JavaScheduler
  [^ScheduledExecutorService pool threads]
  comp/Lifecycle
  (comp/start [this]
    (validate threads (fn1 t/integer?))
    (assoc this :pool (Executors/newScheduledThreadPool (long threads))))
  (comp/stop [this]
    (let [_ (.shutdownNow pool)]
      (assoc this :pool nil)))))

#?(:clj (res/register-component! ::java-scheduler map->JavaScheduler []))

#?(:clj
(defrecord
  ^{:doc "Why busy waiting?
          http://www.rationaljava.com/2015/10/measuring-microsecond-in-java.html
          The only way to pause for anything less than a millisecond accurately is by busy waiting.
          Thread.sleep(1) is only 75% accurate
          LockSupport only begins to get accurate at 100us
          By contrast, busy waiting on >=10us is almost 100% accurate.
          The disadvantage, of course, is that busy waiting will tie up a CPU."}
  BusyWaitScheduler
  [queue interrupted? shut-down? busy-waiter]
  comp/Lifecycle
  (comp/start [this]
    (let [shut-down?   (atom false)
          queue        (atom (map/sorted-rank-map))
          interrupted? (atom false)
          busy-waiter
            (future
              (log/pr ::debug "Started busy waiter.")
              (while (not (or @interrupted?
                              (and @shut-down? (empty? @queue))))
                (catch-all
                  (let [now                 (System/nanoTime)
                        [prevs [_ curr-fs]] (map/split-key now @queue)]
                    (when (or (c/contains? prevs) (c/contains? curr-fs))
                      (doseq [[_ prev-fs] prevs]
                        (doseq [prev-f prev-fs] (prev-f)))
                      (when curr-fs
                        (doseq [curr-f curr-fs] (curr-f)))
                      ; TODO simplistic in that it potentially drops/overwrites old ones
                      ; also if it fails to execute, it should still remove from queue
                      (swap! queue (fn-> (#(reduce dissoc % (keys prevs)))
                                         (dissoc now)))))
                  e (log/pr :warn e)))
              (log/pr ::debug "BusyWaitScheduler finished running."))]
      (merge this (kw-map queue interrupted? shut-down? busy-waiter))))
  (comp/stop [this]
    (reset! interrupted? true)
    (reset! shut-down?   true)
    this)))

#?(:clj (res/register-component! ::busy-wait-scheduler map->BusyWaitScheduler []))

#?(:clj
(defnt schedule!
  ([^JavaScheduler scheduler at f]
    (validate at (s/and number? (fn1 >= 0))
              f  fn?)
    (let [wait (max 0 ; Negative delay goes to 0
                    (- at (System/nanoTime)))
          scheduler* (-> scheduler :pool
                         (validate (fnl instance? ScheduledExecutorService)))]
      (.schedule ^ScheduledExecutorService scheduler* ^Callable f
                 (long wait) (time/->timeunit :ns))))
  ([^BusyWaitScheduler scheduler at f]
    (validate at (s/and number? (fn1 >= 0))
              f  fn?)
    (let [shut-down? (-> scheduler :shut-down? (validate atom?) deref)
          queue      (-> scheduler :queue      (validate atom?))]
      (if shut-down?
          false
          (do (swap! queue update (long at) (fn-> (c/ensurec []) (conj f)))
              true))))))

; ===== THREADPOOLS ===== ;

#?(:cljs (def web-workers-set-up? (atom false)))

#?(:cljs (defalias bootstrap-worker servant.worker/bootstrap))

#?(:clj
(defnt ^ThreadFactory thread-factory [^string? name-] ; TODO fix type hint to be automatic
  (let [ct (atom -1)]
    (reify ThreadFactory
      (^Thread newThread [this ^Runnable runnable]
        (doto (Thread. runnable) ; TODO instrument so there is logging before and after
              (.setName (str name- ":" (swap! ct inc)))))))))

#?(:clj
(let [constructor ; TODO move this to quantum.core.reflect
       (.unreflectConstructor refl/lookup
         (doto (.getDeclaredConstructor ForkJoinWorkerThread
                 (into-array [ForkJoinPool]))
               (.setAccessible true)))]
  (defn ^ForkJoinWorkerThread ->fork-join-worker-thread [^ForkJoinPool pool]
    (.invokeExact constructor pool))))

#?(:clj
(defnt ->fixed-pool [#{long int} num-threads ^string? name-base]
  (ThreadPoolExecutor. num-threads num-threads
    0 (time/->timeunit :ms)
    (SynchronousQueue.)
    (thread-factory (or name-base (str (gensym "fixed-pool")))))))

#?(:clj
(defnt ->fork-join-pool [^string? name-base]
  (let [name-base' (or name-base (str (gensym "fork-join-pool")))]
    (ForkJoinPool.
      (min 0x7fff #_ForkJoinPool/MAX_CAP (.availableProcessors (Runtime/getRuntime)))
      (let [ct (atom -1)]
        (reify ForkJoinPool$ForkJoinWorkerThreadFactory
          (^ForkJoinWorkerThread newThread [this ^ForkJoinPool pool]
            (doto (->fork-join-worker-thread pool) ; TODO instrument so there is logging before and after
                  (.setName (str name-base' ":" (swap! ct inc)))))))
      nil false))))

#?(:clj
(defnt ->cached-pool [^string? name-base]
  (ThreadPoolExecutor. 0 Integer/MAX_VALUE
     60 (time/->timeunit :sec)
     (SynchronousQueue.)
     (thread-factory name-base))))

#?(:clj
(defnt shut-down!
  "Allows previously submitted tasks to execute before terminating."
  ([^ExecutorService   x] (.shutdown x))
  ([^JavaScheduler     x] (-> x :pool shut-down!))
  ([^BusyWaitScheduler x] (-> x :shut-down? (reset! true)))))

#?(:clj
(defnt shut-down-now!
  "Prevents waiting tasks from starting and attempts to stop currently executing tasks."
  ([^ExecutorService   x] (.shutdownNow x))))

; SHUT DOWN ALL FUTURES
; ; DOESN'T ACTUALLY WORK
; (import 'clojure.lang.Agent)
; (import 'java.util.concurrent.Executors)
; (defn shut-down-all-futures! []
;   (shutdown-agents)
;   (.shutdownNow Agent/soloExecutor)
;   (set! Agent/soloExecutor (Executors/newCachedThreadPool)))

#?(:clj
(defnt await-termination! ; TODO include timeouts
  ([^ExecutorService   x] (shut-down! x) (.awaitTermination x Integer/MAX_VALUE (time/->timeunit :millis)))
  ([^JavaScheduler     x] (shut-down! x) (-> x :pool await-termination!))
  ([^BusyWaitScheduler x] (shut-down! x) @(:busy-waiter x))))

#?(:clj
(defnt await-termination-now! ; TODO include timeouts
  ([^ExecutorService   x] (shut-down-now! x) (.awaitTermination x Integer/MAX_VALUE (time/->timeunit :millis)))))

#?(:clj
(res/defcomponent
  ^{:doc "Do not construct directly. Use `->threadpool-manager` instead.
          Attempts to manage all known threadpools. Should probably be a singleton
          but this is not currently enforced.

          Assumes that references to core threadpools are not mutated (or at least that,
          in the case of agent and future threadpools, they shut down and fully terminated
          before mutating). Note that mutating the reference to the core.async threadpool,
          when `replace-core-threadpools-opts` is provided, means that the queue and the
          threadpool references that the ThreadpoolManager stores will not reflect changes
          to or usage of the new core.async threadpool. In any case, reference-mutation of
          these core threadpools can lose pre- and post- Thread execution reporting if such
          reporting is not provided to potential replacement core threadpools.

          In summary, if you ask the ThreadpoolManager to manage threadpools, let it do its
          job (at least when it comes to core threadpools).

          START:
          Assumes that it is passed a \"blank slate\", or at least a stopped state.

          STOP:
          Only shuts down generated threadpools. Provided, non-core threadpools
          will be shut down if `[:shut-down-opts :provided]` is true."
    :todo #{"Make more customizable in terms of the config of each core threadpool, if replaced"}}
  ThreadpoolManager
  [config all core provided generated]
  ([this]
    (log/pr ::debug "Starting ThreadpoolManager...")
    (let [{:keys [replace-core-pools-opts shut-down-opts pools]} config
          default-core-pools
          ; `fref` to ensure accurate reads if they ever change
          {:clojure/agent
             {:pool ; ThreadPoolExecutor
                (fref clojure.lang.Agent/pooledExecutor)
              :queue
                (fref (.getQueue ^ThreadPoolExecutor clojure.lang.Agent/pooledExecutor))}
           :clojure/future
             {:pool ; ThreadPoolExecutor
                (fref clojure.lang.Agent/soloExecutor)
              :queue
                (fref (.getQueue ^ThreadPoolExecutor clojure.lang.Agent/pooledExecutor))}
           :clojure/core.async
             {} ; private via closure in `clojure.core.async.impl.exec.threadpool/thread-pool-executor`
           :clojure/reducers
             {:pool
               (fref @@#'clojure.core.reducers/pool)}}
          core-pools
           (if replace-core-pools-opts
               (let [wait-for-shutdown (:wait-for-shutdown replace-core-pools-opts)
                     ^ThreadPoolExecutor agent-pool  clojure.lang.Agent/pooledExecutor
                     ^ThreadPoolExecutor future-pool clojure.lang.Agent/soloExecutor]
                 ; There is always the possibility that the core threadpools could be
                 ; tampered with in the meantime, but we ignore this and do not lock them

                 ; ----- AGENT POOL ----- ;
                 (.shutdown agent-pool)
                 ; This has to be in place anyway in case `wait-for-shutdown` fails
                 (set! clojure.lang.Agent/pooledExecutor
                   (->fixed-pool (+ 2 (.availableProcessors (Runtime/getRuntime)))
                                 "quantum.core.async.pool:send-pool"))
                 (when wait-for-shutdown
                   (.awaitTermination agent-pool (long (/ (time/->nanos wait-for-shutdown) 2)) (time/->timeunit :ns)) ; TODO use div:int
                   (when-not (.isTerminated agent-pool)
                     (throw (>ex-info "Agent threadpool took too long to terminate"))) )

                 ; ----- FUTURE POOL ----- ;
                 (.shutdown future-pool)
                 ; This has to be in place anyway in case `wait-for-shutdown` fails
                 (set! clojure.lang.Agent/soloExecutor
                   (->cached-pool "quantum.core.async.pool:(send-off|future)-pool"))
                 (when wait-for-shutdown
                   (.awaitTermination future-pool (long (/ (time/->nanos wait-for-shutdown) 2)) (time/->timeunit :ns)) ; TODO use div:int
                   (when-not (.isTerminated future-pool)
                     (throw (>ex-info "Future threadpool took too long to terminate"))) )

                 ; ----- ASYNC POOL ----- ;
                 ; We can't have reporting on any previous core.async interactions
                 ; and we can't wait for it to shut down
                 #_(.shutdown core-async-pool)
                 (let [num-threads (or (-> replace-core-pools-opts :clojure:core:async :max-threads)
                                       (long @@#'clojure.core.async.impl.exec.threadpool/pool-size))
                       !core-async-queue (SynchronousQueue.)
                       core-async-pool
                         ; Fixed threadpool
                         (->fixed-pool num-threads "quantum.core.async.pool:core.async-pool")]
                   (var/reset-var! #'clojure.core.async.impl.dispatch/executor
                     (delay (reify clojure.core.async.impl.protocols/Executor
                              (clojure.core.async.impl.protocols/exec [this r]
                                (.execute ^ThreadPoolExecutor core-async-pool ^Runnable r)))))

                   ; ----- REDUCERS POOL ----- ;
                   (let [reducers-pool (->fork-join-pool "quantum.core.async.pool:reducers-pool")]
                     (var/reset-var! #'clojure.core.reducers/pool (delay reducers-pool))
                   ; TODO:
                   ; :fiber ^FiberScheduler     (DefaultFiberScheduler/getInstance) ; (-> _ .getExecutor) is ForkJoinPool / ExecutorService

                   (-> default-core-pools
                       (assoc-in [:clojure/core.async :pool ] core-async-pool
                                 [:clojure/core.async :queue] !core-async-queue)))))
               default-core-pools)
          [provided-pools generable-pools]
            (->> pools (c/split-into (fn-> val :pool fn?) hash-map))
          generated-pools
            (->> generable-pools (map-vals' (update :pool call)))
          this' (-> this
                    (assoc :all       (merge provided-pools generated-pools core-pools)
                           :core      core-pools
                           :provided  provided-pools
                           :generated generated-pools))]
      (log/pr ::debug "Started ThreadpoolManager.")
      this'))
  ([this]
    (log/pr ::debug "Stopping ThreadpoolManager...")
    (with-do
      (let [cancel? (-> config :shut-down-opts :cancel?)
            shut-down-pools! (fn->> (map-vals' (update :pool (ifp1 cancel? shut-down-now! shut-down!))))]
        (-> this
            (updates :provided  (whenp1 (-> config :shut-down-opts :provided) shut-down-pools!)
                     :generated shut-down-pools!)))
      (log/pr ::debug "Stopped ThreadpoolManager.")))))

#?(:clj (defn validate-pools-map [x] (t/+map? x))) ; TODO more validation

#?(:clj
(dv/def-map threadpool-manager:config ; TODO merge with ThreadpoolManager
  ; TODO deduplicate code
  :opt-un    [(def :this/replace-core-pools-opts
                :opt-un [(def :this/wait-for-shutdown (fn1 time/duration?))
                         (def :this/clojure:core:async
                           :opt-un [(def :this/max-threads (fn1 t/integer?))])])
              (def :this/shut-down-opts (fn-> keys (set/subset? #{:provided :cancel?})))
              (def :this/pools          validate-pools-map)]))

#?(:clj
(defn ->threadpool-manager [opts]
  (map->ThreadpoolManager {:config (->threadpool-manager:config opts)})))

#?(:clj (swap! qcore/*registered-components assoc ::threadpool-manager
          #(comp/using (->threadpool-manager %) [::log/log])))

#?(:cljs
(defrecord Threadpool [thread-ct threads script-src]
  comp/Lifecycle
    (start [this]
      ; Bootstrap the web workers if that hasn't been done already
      (let [thread-ct (or thread-ct 2)]
        (when (and (web-worker?)
                   ((fn-and t/integer? pos?) thread-ct)
                   (not @web-workers-set-up?))
          (log/pr :always "Bootstrapping web workers")
          ; Run the setup code for the web workers
          (bootstrap-worker)
          (reset! web-workers-set-up? true))

        ; We need to make sure that only the main thread/script will spawn the servants.
        (if (servant/webworker?)
            this
            (do (log/pr :debug "Spawning" thread-ct "-thread web-worker threadpool")
                (validate script-src string?
                          thread-ct  (s/and t/integer? pos?))
                (assoc this
                  ; Returns a buffered channel of web workers
                  :threads (servant/spawn-servants thread-ct script-src))))))
    (stop  [this]
      (when threads
        (log/pr :debug "Destroying" thread-ct "-thread web-worker threadpool")
        (servant/kill-servants threads thread-ct))
      this)))

#?(:cljs (defservantfn dispatch "The global web worker dispatch fn" [f] (f)))

#?(:clj
     (defn ->pool [type num-threads & [name-base]]
       (case type
         :fixed            (->fixed-pool num-threads name-base)
         :fork-join        (->fork-join-pool name-base)
         :fork-join/fibers (co.paralleluniverse.fibers.FiberForkJoinScheduler.
                             (or name-base (name (gensym))) num-threads nil
                             co.paralleluniverse.common.monitoring.MonitorType/JMX false)))
   :cljs
     (defn ->pool [{:keys [thread-ct script-src] :as opts}]
       (validate thread-ct (s/or* nil? t/integer?))
       (validate script-src string?)
       (when (pos? thread-ct) (map->Threadpool opts))))

#?(:cljs (swap! qcore/registered-components assoc ::threadpool
            #(comp/using (->pool %) [::log/log])))

#?(:clj (def pool? (fnl instance? ExecutorService)))

; ===== Interval Executor ===== ;

(declare add-task!)

#_(t/def ::task
    (s/keys :req-un
      [(spec :ident    ident?)
       (spec :f        fn?)
       (spec :interval (spec pos-int? "In millis"))]))

(res/defcomponent IntervalExecutor
  ^{:doc "For each task in `tasks`, executes `f` at a fixed interval of `interval`.
          TODO may be superseded by a higher-resolution Java executor in Clojure, but
          this implementation is cross-platform.
          Internally, a `go-loop` is used for every task."}
  [config
   ;; TODO make `chan` the primary point of contact for components
   ;; It's used to ensure that resources are stopped being used before cleanup starts
   chan #_chan? stop-ch #_promise?
   *tasks #_(t/of atom? (t/of map? ident?
                          (t/and ::task
                            (t/keys :req-un [(spec :stop-ch promise?)]))))]
  ([this]
    (let [chan'    (async/chan 100) ; `100` is arbitrary
          *tasks'  (atom {})
          stop-ch' (async/each! chan'
                     (fn [msg]
                       (err/catch-all
                         (let [[kind payload] msg]
                           (case kind
                             :add    (let [{:as task :keys [ident f interval]} payload
                                           stop-ch (async/do-interval f interval)]
                                       (swap! *tasks' (fn [tasks]
                                                        (some-> tasks (get ident) :stop-ch async/request-stop!)
                                                        (assoc tasks ident (assoc task :stop-ch stop-ch)))))
                             :remove (let [ident payload]
                                       (swap! *tasks' (fn [tasks]
                                                        (some-> tasks (get ident) :stop-ch async/request-stop!)
                                                        (dissoc tasks ident)))))))))
          this'    (assoc this :chan chan' :stop-ch stop-ch' :*tasks *tasks')]
      (doseq [{:keys [ident f interval]} (:tasks config)]
        (add-task! this' (kw-map ident f interval)))
      this'))
  ([this]
    (close! chan)
    (async/request-stop! stop-ch)
    (->> @*tasks c/vals+ (c/map+ :stop-ch) (c/each #(some-> % async/request-stop!)))
    (assoc this :chan nil :stop-ch nil :*tasks nil)))

(defnt add-task!    [^IntervalExecutor x task  #_::task] (async/put! (:chan x) [:add    task]))
(defnt remove-task! [^IntervalExecutor x ident #_ident?] (async/put! (:chan x) [:remove ident]))

(defn >interval-executor [{:as config :keys [tasks #_(t/of ::task)]}]
  (map->IntervalExecutor {:config config}))

(res/register-component! ::interval-executor >interval-executor [])

; ===== Distributor ===== ;

(defrecord Distributor
  [name
   work-queue
   cache
   max-threads
   thread-registrar
   threadpool
   distributor-fn
   interrupted?
   logging-key
   log]
  comp/Lifecycle (stop [this] (reset! interrupted? true) this))

#?(:clj
(defn ->distributor
  {:usage '(->distributor inc {:cache true
                               :memoize-only-first-arg? true
                               :max-threads 8 :name "distrib"})
   :todo ["Add thread types options"
          "Add validators to component atoms"
          "Register distributor and ensure uniquity"]}
  [f {:keys [cache memoize-only-first-arg? threadpool max-threads
             max-work-queue-size name logging-key] :as opts}]
  (validate max-threads         (s/or* nil? integer?)
            max-work-queue-size (s/or* nil? integer?)
            name                (s/or* nil? string? )
            threadpool          (s/or* nil? (fnl instance? ThreadPoolExecutor))
            f                   fn?
            logging-key         (s/or* nil? keyword?))
  (let [cache-f        (if (true? cache)
                           (atom {})
                           cache) ; TODO bounded or auto-invalidating cache?
        log            (atom [])
        distributor-fn (atom (if cache-f
                                 (memoize f cache-f memoize-only-first-arg?) ; It doesn't cache errors, by default
                                 f))
        name-f         (or name (-> "distributor" gensym core/name keyword))
        max-threads-f  (or max-threads (-> (Runtime/getRuntime) (.availableProcessors)))
        thread-registrar (atom {})
        work-queue     (if max-work-queue-size
                          (async/chan (async/buffer max-work-queue-size))
                          (async/chan)) ; Unbounded queues don't factor in to core.async
        threadpool-f   (atom (or threadpool (->pool :fixed max-threads-f)))
        threadpool-interrupted?
          (doto (atom false)
            (set-validator! (fn1 t/boolean?))
            (add-watch :interrupt-monitor
              (fn [_ _ _ newv]
                (when (true? newv)
                  (catch-all (shut-down! @threadpool-f))))))
        distributor-f  (Distributor.
                         name-f
                         work-queue
                         cache-f
                         max-threads-f
                         thread-registrar
                         threadpool-f
                         distributor-fn
                         threadpool-interrupted?
                         logging-key
                         log)]
    (dotimes [i max-threads-f]
      (let [thread-name  (keyword (str (core/name name-f) "-" i))
            interrupted? (atom false)]
        (assoc! thread-registrar thread-name {:interrupted? interrupted?})
        ; TODO FIX
        (TODO)
        (loop #_async-loop #_{:type       :thread
                     :id         thread-name
                     :threadpool @threadpool-f}
          []
          (when-let
            [[val- queue-]    (async/alts!! [work-queue (async/timeout 500)])
             [timestamp work] val-]
            (try
              (catch-all (apply @distributor-fn work)
                e
                (do (log/ppr logging-key e)
                    (conj! log [(time/now:epoch-millis) thread-name e]))))) ; 500 because it may be wise to be in parked rather than always checking for interrupt
          (when-not (or @threadpool-interrupted? @interrupted?)
            (recur)))))
    distributor-f)))

#?(:clj
(defn distribute!*
  {:usage '(distribute!* offer! (->distributor) [1 2 3 5 6] {:cache? true})}
  [enqueue-fn distributor & inputs]
  (validate distributor (fnl instance? Distributor))
  (enqueue-fn (:work-queue distributor) [(time/now:epoch-millis) inputs])))

#?(:clj (defn distribute!   [distributor & inputs] (apply distribute!* async/offer! distributor inputs)))
#?(:clj (defn distribute>!! [distributor & inputs] (apply distribute!* async/>!!-protocol    distributor inputs)))

#?(:clj
(defn distribute-all! [distributor inputs-set & [apply?]]
  (for [inputs inputs-set]
    (if apply?
        (apply distribute! distributor inputs)
        (distribute! distributor inputs)))))

