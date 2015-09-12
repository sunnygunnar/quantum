(ns quantum.core.graph
  (:require-quantum [ns fn logic type macros coll err log])
  #?(:clj
    (:require [clojure.java.io :as io]
      [loom.alg         :as g.alg]
      [loom.graph       :as g.graph]
      [loom.alg-generic :as g.gen-alg])))

(def graph g.graph/graph)
(def transpose g.graph/transpose)
; reverse-graph is same as g.graph/transpose

(def inverse-map ; some better way of doing this?
  {+ -
   - +
   / *
   * /})

(defn inverse
  {:todo "Make this better. Inverse of complex functions"}
  [f]
  (or (get inverse-map f)
      (throw+ (Err. :undefined "Inverse not defined for function" f))))

(def base-map
  {+ 0
   - 0
   / 1
   * 1})

(defn base [f]
  (or (get base-map f)
      (throw+ (Err. :undefined "Base not defined for function" f))))

; alg.generic
#?(:clj (in-ns 'loom.alg-generic))

; {:modified "Changed to use an fn for each weight-aggregation operation.
;               Weights won't always be simply added - they might be multiplied, for instance."
;  :contributor "Alex Gunnarson"}

#?(:clj 
(defn dijkstra-traverse
  "Returns a lazy-seq of [current-node state] where state is a map in the
  format {node [distance predecessor]}. When f is provided, returns
  a lazy-seq of (f node state) for each node"
  ([successors dist start]
    (dijkstra-traverse successors dist start vector))
  ([successors dist start f]
    (dijkstra-traverse successors dist start f +))
  ([successors dist start f waf]
     (letfn [(step [[state pq]]
               (when-let [[dist-su _ u :as fpq] (first pq)]
                 (cons
                  (f u state)
                  (lazy-seq
                   (step
                    (reduce
                     (fn [[state pq] v]
                       (let [dist-suv (waf dist-su (dist u v))
                             dist-sv (first (state v))]
                         (if (and dist-sv (>= dist-suv dist-sv))
                           [state pq]
                           (let [pq (if dist-sv
                                      (disj pq [dist-sv (hash v) v])
                                      pq)]
                             [(assoc state v [dist-suv u])
                              (conj pq [dist-suv (hash v) v])]))))
                     [state (disj pq fpq)]
                     (successors u)))))))]
       (step [{start [(quantum.core.graph/base waf) nil]}
              ;; Poor man's priority queue. Caveats:
              ;; 1) Have to keep it in sync with current state
              ;; 2) Have to include hash codes for non-Comparable items
              ;; 3) O(logn) operations
              ;; Tried clojure.contrib.priority-map but it wasn't any faster
              (sorted-set [(quantum.core.graph/base waf) (hash start) start])])))))

#?(:clj
(defn dijkstra-span
  "Finds all shortest distances from start, where successors and dist
  are functions called as (successors node) and (dist node1 node2).
  Returns a map in the format {node {successor distance}}"
  ([successors dist start] (dijkstra-span successors dist start +))
  ([successors dist start waf]
    (reduce
     (fn [span [n [d p]]]
       (if p
         (assoc-in span [p n] d)
         span))
     {}
     (second (last (dijkstra-traverse successors dist start vector waf)))))))

#?(:clj (in-ns 'loom.alg))

#?(:clj
(defn dijkstra-traverse
  "Returns a lazy-seq of [current-node state] where state is a map in
  the format {node [distance predecessor]}. When f is provided,
  returns a lazy-seq of (f node state) for each node"
  ([g            ] (gen/dijkstra-traverse (graph/successors g) (graph/weight g)
                     (first (nodes g))))
  ([g start      ] (gen/dijkstra-traverse (graph/successors g) (graph/weight g) start))
  ([g start f    ] (gen/dijkstra-traverse (graph/successors g) (graph/weight g) start f))
  ([g start f waf] (gen/dijkstra-traverse (graph/successors g) (graph/weight g) start f waf))))

#?(:clj
(defn- can-relax-edge?
  "Tests for whether we can improve the shortest path to v found so far
   by going through u."
  [[u v :as edge] weight costs waf]
  (let [vd (get costs v)
        ud (get costs u)
        aggregated (waf ud weight)] ; used fn here
    (> vd aggregated))))

#?(:clj
(defn- relax-edge
  "If there's a shorter path from s to v via u,
    update our map of estimated path costs and
   map of paths from source to vertex v"
  [[u v :as edge] weight [costs paths :as estimates] waf]
  (let [ud (get costs u)
        aggregated (waf ud weight)]
    (if (can-relax-edge? edge weight costs waf)
      [(assoc costs v aggregated) (assoc paths v u)]
      estimates))))

#?(:clj
(defn- relax-edges
  "Performs edge relaxation on all edges in weighted directed graph"
  [g start estimates waf]
  (->> (edges g)
       (reduce (fn [estimates [u v :as edge]]
                 (relax-edge edge (graph/weight g u v) estimates waf))
               estimates))))

#?(:clj 
(defn bellman-ford
  "Given a weighted, directed graph G = (V, E) with source start,
   the Bellman-Ford algorithm produces map of single source shortest
   paths and their costs if no negative-weight cycle that is reachable
   from the source exists, and false otherwise, indicating that no
   solution exists."
  ([g start] (bellman-ford g start +))
  ([g start waf] ; @waf is weight-aggregation-function
    (let [initial-estimates (init-estimates g start)
          ;;relax-edges is calculated for all edges V-1 times
          [costs paths] (reduce (fn [estimates _]
                                  (relax-edges g start estimates waf))
                                initial-estimates
                                (-> g nodes count dec range))
          edges (edges g)]
      (if (some
           (fn [[u v :as edge]]
             (can-relax-edge? edge (graph/weight g u v) costs waf))
           edges)
        false
        [costs
         (->> (keys paths)
              ;;remove vertices that are unreachable from source
              (remove #(= Double/POSITIVE_INFINITY (get costs %)))
              (reduce
               (fn [final-paths v]
                 (assoc final-paths v
                        ;; follows the parent pointers
                        ;; to construct path from source to node v
                        (loop [node v
                               path ()]
                          (if node
                            (recur (get paths node) (cons node path))
                            path))))
               {}))])))))

#?(:clj 
(defn- bellman-ford-transform
  "Helper function for Johnson's algorithm. Uses Bellman-Ford to remove negative weights."
  ([wg] (bellman-ford-transform +))
  ([wg waf] ; @waf is weight-aggregation-function
    (let [q (first (drop-while (partial graph/has-node? wg) (repeatedly gensym)))
          es (for [v (graph/nodes wg)] [q v 0])
          ; "Add-edges" just associates edges with a graph; it doesn't add their weights together
          bf-results (bellman-ford (graph/add-edges* wg es) q waf)]
      (if bf-results
        (let [[dist-q _] bf-results
              new-es (map (juxt first second (fn [[u v]]
                                               (waf (weight wg u v)
                                                    ((quantum.core.graph/inverse waf)
                                                       (dist-q u)
                                                       (dist-q v)))))
                          (graph/edges wg))]
          (graph/add-edges* wg new-es))
        false)))))

#?(:clj
(defn johnson
  "Finds all-pairs shortest paths using Bellman-Ford to remove any negative edges before
  using Dijkstra's algorithm to find the shortest paths from each vertex to every other.
  This algorithm is efficient for sparse graphs.
  If the graph is unweighted, a default weight of 1 will be used. Note that it is more efficient
  to use breadth-first spans for a graph with a uniform edge weight rather than Dijkstra's algorithm.
  Most callers should use shortest-paths and allow the most efficient implementation be selected
  for the graph."
  ([g] (johnson g +))
  ([g waf]
    (let [g (if (and (weighted? g) (some (partial > 0) (map (graph/weight g) (graph/edges g))))
              (bellman-ford-transform g waf)
              g)]
      (if (false? g)
        false
        (let [dist (if (weighted? g)
                     (weight g)
                     (fn [u v] (when (graph/has-edge? g u v) 1)))]
          (reduce (fn [acc node]
                    (assoc acc node (gen/dijkstra-span (successors g) dist node waf)))
                  {}
                  (nodes g))))))))

; I don't know why this is necessary
#?(:clj
(defn bf-all-pairs-shortest-paths
  "Uses bf-span on each node in the graph."
  [g]
  (reduce (fn [spans node]
            (assoc spans node (bf-span g node)))
          {}
          (nodes g))))

#?(:clj
(defn all-pairs-shortest-paths
  "Finds all-pairs shortest paths in a graph. Uses Johnson's algorithm for weighted graphs
  which is efficient for sparse graphs. Breadth-first spans are used for unweighted graphs."
  ([g] (all-pairs-shortest-paths g +))
  ([g waf]
    (if (weighted? g)
      (johnson g waf)
      (bf-all-pairs-shortest-paths g waf)))))

#?(:clj (in-ns 'quantum.core.graph))

#?(:clj (defalias graph            g.graph/graph))
#?(:clj (defalias undirected-graph graph))
#?(:clj (defalias digraph          g.graph/digraph))
#?(:clj (defalias directed-graph   digraph))
#?(:clj (defalias weighted-digraph g.graph/weighted-digraph))
#?(:clj (defalias nodes            g.graph/nodes))
#?(:clj (defalias edges            g.graph/edges))

#?(:clj (defalias all-pairs-shortest-paths g.alg/all-pairs-shortest-paths))

(defn root-node-paths* [m depv]
  (->> (for [k sub m]
         (if (map? sub)
             (root-node-paths* sub (conj depv k))
             [[(conj depv k) sub]]))
       (apply concat)))

(defn+ root-node-paths
  "Outputs paths from the root nodes to all their respective
   leaf nodes. Keeps only the root node and the leaf node,
   as well as the immediate parent of the leaf node.

   Very useful for conversion tables."
  {:in {:zs          {:ps           {:ns         1000000000000N}
                      :zs           {:as         1000N
                                     :ys         1/1000}
                      :ns           {:mcs        1000000000000000N}
                      :as           {:fs         1000000N}
                      :fs           {:ps         1000000000N}}
       :common-years {:days         {:fortnights 14/365
                                     :leap-years 366/365
                                     :weeks      7/365}
                      :common-years {:days       1/365}
                      :years        {:centuries  7305/73
                                     :millennia  73050/73}}}
   :out {[:common-years :centuries ] 7305/73
         [:common-years :days      ] 1/365
         [:common-years :fortnights] 14/365
         [:common-years :leap-years] 366/365
         [:common-years :millennia ] 73050/73
         [:common-years :weeks     ] 7/365
         [:zs           :as        ] 1000N
         [:zs           :fs        ] 1000000N
         [:zs           :mcs       ] 1000000000000000N
         [:zs           :ns        ] 1000000000000N
         [:zs           :ps        ] 1000000000N
         [:zs           :ys        ] 1/1000}}
  [m]
  (->> (root-node-paths* m [])
       (map (juxt (compr (extern (mfn 1 first)) (juxt (extern (mfn 1 first)) (extern (mfn 1 last)))) (extern (mfn 1 second))))
       (into (sorted-map))))



; ======================== CLOJURE/ALGO.GRAPH ========================

;;  Basic Graph Theory Algorithms
;;
;;  straszheimjeffrey (gmail)
;;  Created 23 June 2009


; (ns 
;   ^{:author "Jeffrey Straszheim",
;      :doc "Basic graph theory algorithms"}
;   clojure.algo.graph
;   (use [clojure.set :only (union)]))


; (defrecord DirectedGraph
;   [nodes       ; The nodes of the graph, a collection
;    neighbors]) ; A function that, given a node, returns a collection of
;                ; neighbor nodes.

; (defn get-neighbors
;   "Get the neighbors of a node."
;   {:source "clojure.algo.graph"
;    :attribution "Jeffrey Straszheim"}
;   [g n]
;   ((:neighbors g) n))

; ;; Graph Modification

; (defn reverse-graph
;   "Given a directed graph, return another directed graph with the
;    order of the edges reversed."
;   {:source "clojure.algo.graph"
;    :attribution "Jeffrey Straszheim"}
;   [g]
;   (let [op (fn [rna idx]
;              (let [ns (get-neighbors g idx)
;                    am (fn [m val]
;                         (assoc m val (conj (get m val #{}) idx)))]
;                (reduce am rna ns)))
;         rn (reduce op {} (:nodes g))]
;     (DirectedGraph. (:nodes g) rn)))

; (defn add-loops
;   "For each node n, add the edge n->n if not already present."
;   [g]
;   (struct directed-graph
;           (:nodes g)
;           (into {} (map (fn [n]
;                           [n (conj (set (get-neighbors g n)) n)]) (:nodes g)))))

; (defn remove-loops
;   "For each node n, remove any edges n->n."
;   [g]
;   (struct directed-graph
;           (:nodes g)
;           (into {} (map (fn [n]
;                           [n (disj (set (get-neighbors g n)) n)]) (:nodes g)))))


; ;; Graph Walk

; (defn lazy-walk
;   "Return a lazy sequence of the nodes of a graph starting a node n.  Optionally,
;    provide a set of visited notes (v) and a collection of nodes to
;    visit (ns)."
;   ([g n]
;      (lazy-walk g [n] #{}))
;   ([g ns v]
;      (lazy-seq (let [s (seq (drop-while v ns))
;                      n (first s)
;                      ns (rest s)]
;                  (when s
;                    (cons n (lazy-walk g (concat (get-neighbors g n) ns) (conj v n))))))))

; (defn transitive-closure
;   "Returns the transitive closure of a graph.  The neighbors are lazily computed.
;    Note: some version of this algorithm return all edges a->a
;    regardless of whether such loops exist in the original graph.  This
;    version does not.  Loops will be included only if produced by
;    cycles in the graph.  If you have code that depends on such
;    behavior, call (-> g transitive-closure add-loops)"
;   [g]
;   (let [nns (fn [n]
;               [n (delay (lazy-walk g (get-neighbors g n) #{}))])
;         nbs (into {} (map nns (:nodes g)))]
;     (struct directed-graph
;             (:nodes g)
;             (fn [n] (force (nbs n))))))
          
                
; ;; Strongly Connected Components

; (defn- post-ordered-visit
;   "Starting at node n, perform a post-ordered walk."
;   [g n [visited acc :as state]]
;   (if (visited n)
;     state
;     (let [[v2 acc2] (reduce (fn [st nd] (post-ordered-visit g nd st))
;                             [(conj visited n) acc]
;                             (get-neighbors g n))]
;       [v2 (conj acc2 n)])))
  
; (defn post-ordered-nodes
;   "Return a sequence of indexes of a post-ordered walk of the graph."
;   [g]
;   (fnext (reduce #(post-ordered-visit g %2 %1)
;                  [#{} []]
;                  (:nodes g))))

; (defn scc
;   "Returns, as a sequence of sets, the strongly connected components
;    of g."
;   [g]
;   (let [po (reverse (post-ordered-nodes g))
;         rev (reverse-graph g)
;         step (fn [stack visited acc]
;                (if (empty? stack)
;                  acc
;                  (let [[nv comp] (post-ordered-visit rev
;                                                      (first stack)
;                                                      [visited #{}])
;                        ns (remove nv stack)]
;                    (recur ns nv (conj acc comp)))))]
;     (step po #{} [])))

; (defn component-graph
;   "Given a graph, perhaps with cycles, return a reduced graph that is acyclic.
;    Each node in the new graph will be a set of nodes from the old.
;    These sets are the strongly connected components.  Each edge will
;    be the union of the corresponding edges of the prior graph."
;   ([g]
;      (component-graph g (scc g)))
;   ([g sccs]
;      (let [find-node-set (fn [n]
;                            (some #(if (% n) % nil) sccs))
;            find-neighbors (fn [ns]
;                             (let [nbs1 (map (partial get-neighbors g) ns)
;                                   nbs2 (map set nbs1)
;                                   nbs3 (apply union nbs2)]
;                               (set (map find-node-set nbs3))))
;            nm (into {} (map (fn [ns] [ns (find-neighbors ns)]) sccs))]
;        (struct directed-graph (set sccs) nm))))

; (defn recursive-component?
;   "Is the component (recieved from scc) self recursive?"
;   [g ns]
;   (or (> (count ns) 1)
;       (let [n (first ns)]
;         (some #(= % n) (get-neighbors g n)))))

; (defn self-recursive-sets
;   "Returns, as a sequence of sets, the components of a graph that are
;    self-recursive."
;   [g]
;   (filter (partial recursive-component? g) (scc g)))
                          

; ;; Dependency Lists

; (defn fixed-point
;   "Repeatedly apply fun to data until (equal old-data new-data)
;    returns true.  If max iterations occur, it will throw an
;    exception.  Set max to nil for unlimited iterations."
;   [data fun max equal]
;   (let [step (fn step [data idx]
;                (when (and idx (= 0 idx))
;                  (throw (Exception. "Fixed point overflow")))
;                (let [new-data (fun data)]
;                  (if (equal data new-data)
;                    new-data
;                    (recur new-data (and idx (dec idx))))))]
;     (step data max)))
                  
; (defn- fold-into-sets
;   [priorities]
;   (let [max (inc (apply max 0 (vals priorities)))
;         step (fn [acc [n dep]]
;                (assoc acc dep (conj (acc dep) n)))]
;     (reduce step
;             (vec (replicate max #{}))
;             priorities)))
            
; (defn dependency-list
;   "Similar to a topological sort, this returns a vector of sets. The
;    set of nodes at index 0 are independent.  The set at index 1 depend
;    on index 0; those at 2 depend on 0 and 1, and so on.  Those withing
;    a set have no mutual dependencies.  Assume the input graph (which
;    much be acyclic) has an edge a->b when a depends on b."
;   [g]
;   (let [step (fn [d]
;                (let [update (fn [n]
;                               (inc (apply max -1 (map d (get-neighbors g n)))))]
;                  (into {} (map (fn [[k v]] [k (update k)]) d))))
;         counts (fixed-point (zipmap (:nodes g) (repeat 0))
;                             step
;                             (inc (count (:nodes g)))
;                             =)]
;     (fold-into-sets counts)))
    
; (defn stratification-list
;   "Similar to dependency-list (see doc), except two graphs are
;    provided.  The first is as dependency-list.  The second (which may
;    have cycles) provides a partial-dependency relation.  If node a
;    depends on node b (meaning an edge a->b exists) in the second
;    graph, node a must be equal or later in the sequence."
;   [g1 g2]
;   (assert (= (-> g1 :nodes set) (-> g2 :nodes set)))
;   (let [step (fn [d]
;                (let [update (fn [n]
;                               (max (inc (apply max -1
;                                                (map d (get-neighbors g1 n))))
;                                    (apply max -1 (map d (get-neighbors g2 n)))))]
;                  (into {} (map (fn [[k v]] [k (update k)]) d))))
;         counts (fixed-point (zipmap (:nodes g1) (repeat 0))
;                             step
;                             (inc (count (:nodes g1)))
;                             =)]
;     (fold-into-sets counts)))


; ;; End of file