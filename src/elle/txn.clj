(ns elle.txn
  "Functions for cycle analysis over transactional workloads."
  (:require [bifurcan-clj [core :as b]
                          [graph :as bg]
                          [set :as bs]]
            [clojure [datafy :refer [datafy]]
                     [pprint :refer [pprint]]
                     [set :as set]]
            [clojure.tools.logging :refer [info warn]]
            [clojure.java.io :as io]
            [dom-top.core :refer [loopr]]
            [elle [core :as elle]
                  [consistency-model :as cm]
                  [graph :as g]
                  [util :as util]
                  [viz :as viz]]
            [jepsen [history :as h]
                    [txn :as txn :refer [reduce-mops]]]
            [jepsen.history.fold :refer [loopf]]
            [tesser.core :as t]
            [unilog.config :refer [start-logging!]])
  (:import (elle.graph PathState)
           (io.lacuna.bifurcan IGraph
                               LinearMap
                               Map)
           (jepsen.history Op)))


(start-logging! {:console "%p [%d] %t - %c %m%n"})

(defn op-mops
  "A lazy sequence of all [op mop] pairs from a history."
  [history]
  (mapcat (fn [op] (map (fn [mop] [op mop]) (:value op))) history))

(t/deftransform keep-op-mops
  "A tesser fold over a history. For every op, and every mop in that op, calls
  `(f op mop])`. Passes non-nil results to downstream transforms."
  [f]
  (assoc downstream :reducer
         (fn reducer [acc ^Op op]
           (reduce (fn mop [acc mop]
                     (let [res (f op mop)]
                       (if (nil? res)
                         acc
                         (reducer- acc res))))
                   acc
                   (.value op)))))

(defn ok-keep
  "Given a function of operations, returns a sequence of that function applied
  to all ok operations. Returns nil iff every invocation of f is nil. Uses a
  concurrent fold internally."
  [f history]
  (->> (t/filter h/ok?)
       (t/keep f)
       (t/into [])
       (h/tesser history)
       seq))

(defn all-keys
  "A sequence of all unique keys in the given history."
  [history]
  (->> history op-mops (map (comp second second)) distinct))

(def integer-types
  #{Byte Short Integer Long})

(defn assert-type-sanity
  "I cannot begin to convey the confluence of despair and laughter which I
  encountered over the course of three hours attempting to debug this issue.

  We assert that all keys have the same type, and that at most one integer type
  exists. If you put a mix of, say, Ints and Longs into this checker, you WILL
  question your fundamental beliefs about computers"
  [history]
  (let [int-val-types (->> (op-mops history)
                           (map (comp class last second))
                           (filter integer-types)
                           distinct)]
    (assert (<= (count int-val-types) 1)
            (str "History includes a mix of integer types for transaction values: " (pr-str int-val-types) ". You almost certainly don't want to do this.")))
  (let [key-types (->> (op-mops history)
                       (map (comp class second second))
                       distinct)]
    (assert (<= (count key-types) 1)
            (str "History includes a mix of different types for transaction keys: " (pr-str key-types) ". You almost certainly don't want to do this."))))

(defn failed-writes
  "Returns a map of keys to maps of failed write values to the operations which
  wrote them. Used for detecting aborted reads."
  [write? history]
  (h/fold history
          (loopf {:name :failed-writes}
                 ([failed {}]
                  [^Op op]
                  (recur
                    (if (h/fail? op)
                      (loopr [failed' failed]
                             [[f k v :as mop] (.value op)]
                             (if (write? f)
                               (recur (update failed' k assoc v op))
                               (recur failed')))
                      failed)))
                 ([failed {}]
                  [failed']
                  (recur (merge-with merge failed failed'))))))

(defn failed-write-indices
  "Returns a map of keys to maps of failed write values to the :index's of the
  operations which wrote them. Used for detecting aborted reads. This version
  is significantly more memory-efficient, since it does not require retaining
  every failed operation for the entire pass."
  [write? history]
  (h/fold history
          (loopf {:name :failed-writes}
                 ([failed {}]
                  [^Op op]
                  (recur
                    (if (h/fail? op)
                      (loopr [failed' failed]
                             [[f k v :as mop] (.value op)]
                             (if (write? f)
                               (recur (update failed' k assoc v (.index op)))
                               (recur failed')))
                      failed)))
                 ([failed {}]
                  [failed']
                  (recur (merge-with merge failed failed'))))))

(defn intermediate-writes
  "Returns a map of keys to maps of intermediate write values to the operations
  which wrote them. Used for detecting intermediate reads."
  [write? history]
  (h/fold history
          (loopf {:name :intermediate-writes}
                 ([im {}]
                  [^Op op]
                  ; Find intermediate writes for this particular txn by
                  ; producing two maps: intermediate keys to values, and
                  ; final keys to values in this txn. We shift elements
                  ; from final to intermediate when they're overwritten.
                  (recur (loopr [im'   im
                                 final {}]
                                [[f k v] (.value op)]
                                (if (write? f)
                                  (if-let [e (final k)]
                                    ; We have a previous write of k
                                    (recur (assoc-in im' [k e] op)
                                           (assoc final k v))
                                    ; No previous write
                                    (recur im' (assoc final k v)))
                                  ; Something other than an append
                                  (recur im' final))
                                im')))
                 ([im {}]
                  [im']
                  (recur (merge-with merge im im'))))))

(defn intermediate-write-indices
  "Returns a map of keys to maps of intermediate write values to the :index's
  of operations which wrote them. Used for detecting intermediate reads."
  [write? history]
  (h/fold history
          (loopf {:name :intermediate-writes}
                 ([im {}]
                  [^Op op]
                  ; Find intermediate writes for this particular txn by
                  ; producing two maps: intermediate keys to values, and
                  ; final keys to values in this txn. We shift elements
                  ; from final to intermediate when they're overwritten.
                  (recur (loopr [im'   im
                                 final {}]
                                [[f k v] (.value op)]
                                (if (write? f)
                                  (if-let [e (final k)]
                                    ; We have a previous write of k
                                    (recur (assoc-in im' [k e] (.index op))
                                           (assoc final k v))
                                    ; No previous write
                                    (recur im' (assoc final k v)))
                                  ; Something other than an append
                                  (recur im' final))
                                im')))
                 ([im {}]
                  [im']
                  (recur (merge-with merge im im'))))))

(defn lost-update-cases
  "Takes a function write? which returns true iff an operation is a write, and
  a history. Returns a seq of error maps describing any lost updates found.
  Assumes writes are unique. Each map is of the form:

    {:key   The key in common
     :value The value read by all transactions
     :txns  A collection of completion ops for transactions which collided on
            this key and value.}

  Lost Update is a bit of a weird beast. I don't actually *have* a general
  definition of it: even in Adya, it's defined as 'Histories which look like
  H_lu', where H_lu is:

    w0(x0)
           r1(x0, 10)                          w1(x1, 14) c1
                      r2(x0, 10) w2(x2, 15) c2

    [x0 << x2 << x1]

  It stands to reason that the version order x2 << x1 and the precise order of
  T1 and T2 isn't necessary here: the essential problem is that T1 and T2 both
  read x0 and wrote different values of x, presumably based on x0. If this ever
  happens it could lead to the loss of the logical update T1 or T2 is
  performing.

  In cyclic terms, this must manifest as write-read edges from some transaction
  T0 to both T1 and T2 on the same key, (since both must read T0's write of x).

    +--wr--> T1
    |
    T0
    |
    +--wr--> T2

  WLOG, let x1 << x2. If we have the complete version order for x, we must also
  have a write-write edge from T1 -> T2 since both wrote x. However, since T2
  observed the state x0 which was overwritten by T1, we also have an rw edge
  from T2->T1. This forms a G2-item cycle:

    +--wr--> T1 <-+
    |        |    |
    T0       ww   rw
    |        V    |
    +--wr--> T2 --+

  We can already detect G2-item. However, we may not detect some instances of
  lost update because we may be missing some of these edges. Our version order
  inference is conservative, and especially for rw-registers may fail to
  capture many edges between versions. Even for list-append, if one of the
  writes is never read that version won't show up in the version order at all.

  What actually *matters* here is that two transactions read the same x0 and
  both wrote x, and committed. The precise order of writes to x doesn't matter.
  We can detect this directly, in linear time and space, by scanning the set of
  committed transactions and looking for any pair which both read some x=x0 and
  write x."
  [write? history]
  (loopr [; A map of keys to values to txns which read that key and value and
          ; wrote that key.
          txns (transient {})]
         [op history]
         (recur
           (if (not= :ok (:type op))
             txns
             (loopr [; A map of keys to the first value read
                     reads  (transient {})
                     ; And a set of keys we've written, so we don't
                     ; double-count
                     writes (transient #{})
                     txns   txns]
                    [[f k v] (:value op)]
                    (let [read-value (get reads k ::not-found)]
                      (if (and (write? f) (not (contains? writes k)))
                        ; We wrote k for the first time
                        (if (= read-value ::not-found)
                          ; Didn't read k; don't care
                          (recur reads writes txns)
                          ; We read and wrote k; this is relevant to our search
                          (let [txns-k    (get txns k (transient {}))
                                txns-k-v  (get txns-k read-value (transient []))
                                txns-k-v' (conj! txns-k-v op)
                                txns-k'   (assoc! txns-k read-value txns-k-v')]
                            (recur reads
                                   (conj! writes k)
                                   (assoc! txns k txns-k'))))
                        ; We read k
                        (if (= read-value ::not-found)
                          ; And this is our first (i.e. external) read of k
                          (recur (assoc! reads k v) writes txns)
                          ; We already read k
                          (recur reads writes txns))))
                    ; Return txns and move to next op
                    txns)))
         ; Now search for collisions
         (loopr [cases (transient [])]
                [[k v->txns]  (persistent! txns)
                 [v txns]     (persistent! v->txns)]
                (let [txns (persistent! txns)]
                  (recur
                    (if (<= 2 (count txns))
                      (conj! cases {:key   k
                                    :value v
                                    :txns  txns})
                      cases)))
                (let [cases (persistent! cases)]
                  (when (seq cases) cases)))))

(def cycle-explainer
  "This cycle explainer wraps elle.core's cycle explainer, and categorizes
  cycles based on what kinds of edges they contain; e.g. an all-ww cycle is
  :G0, one with realtime, ww, and wr edges is :G1c-realtime, etc."
  (reify elle/CycleExplainer
    (explain-cycle [_ pair-explainer cycle]
      (let [ex (elle/explain-cycle elle/cycle-explainer pair-explainer cycle)
            ; What types of relationships are involved here?
            type-freqs (frequencies (map :type (:steps ex)))
            realtime  (:realtime  type-freqs 0)
            process   (:process   type-freqs 0)
            ww        (:ww        type-freqs 0)
            wr        (:wr        type-freqs 0)
            rw        (:rw        type-freqs 0)
            ; Any predicate edges?
            predicate? (boolean (seq (filter :predicate? (:steps ex))))
            ; Are any pair of rw's together?
            rw-adj?   (->> (:steps ex)
                           (cons (last (:steps ex))) ; For last->first edge
                           (map :type)
                           (partition 2 1)        ; Take pairs
                           (filter #{[:rw :rw]})  ; Find an rw, rw pair
                           seq
                           boolean)
            ; We compute a type based on data dependencies alone
            data-dep-type (cond (= 1 rw) "G-single"
                                (< 1 rw) (if rw-adj?
                                           (if predicate?
                                             "G2"
                                             "G2-item")
                                           "G-nonadjacent")
                                (< 0 wr) "G1c"
                                (< 0 ww) "G0"
                                true (throw (IllegalStateException.
                                              (str "Don't know how to classify"
                                                   (pr-str ex)))))
            ; And tack on a -process or -realtime tag if there are those types
            ; of edges.
            subtype (cond (< 0 realtime) "-realtime"
                          (< 0 process)  "-process"
                          true           "")]
        ; (prn :type (keyword (str data-dep-type subtype)))
        (assoc ex :type (keyword (str data-dep-type subtype)))))

    (render-cycle-explanation [_ pair-explainer
                               {:keys [type cycle steps] :as ex}]
      (elle/render-cycle-explanation
        elle/cycle-explainer pair-explainer ex))))

(defn trivial-path-transition
  "A path transition function which is always legal."
  ([vertex] nil)
  ([_ path edge vertex'] nil))

(defn first-path-transition
  "Takes a set of relationships like #{:rw}. Constructs a path transition
  function for use with g/find-cycle-with. Ensures that the first edge, and no
  later edge, is a subset of rels."
  [rels]
  (let [rels (bs/from rels)]
    (fn transition
      ([v] true) ; Our state is true if we're starting, false otherwise.
      ([starting? path edge v']
       (let [match? (bs/contains-all? rels edge)]
         (if starting?
           (if match? false :elle.graph/invalid)
           (if match? :elle.graph/invalid false)))))))

(defn nonadjacent-path-transition
  "Takes a set of relationships like #{:rw}. Constructs a function suitable for
  use with g/find-cycle-with. Ensures that no pair of adjacent edges can both
  be subsets of rels.

  This fn ensures that no :rw is next to another by testing successive edge
  types. In addition, we ensure that the first edge in the cycle is not an rw.
  Cycles must have at least two edges, and in order for no two rw edges to be
  adjacent, there must be at least one non-rw edge among them. This constraint
  ensures a sort of boundary condition for the first and last nodes--even if
  the last edge is rw, we don't have to worry about violating the nonadjacency
  property when we jump to the first."
  [rels]
  (let [rels (bs/from rels)]
    (fn transition
      ([v] true) ; Our accumulator here is a boolean: whether our last edge was (potentially? rw).
      ([last-matched? path edge v']
       ; It's fine to follow *non* rw links, but if you've only
       ; got rw, and we just did one, this path is invalid.
       (let [match? (bs/contains-all? rels edge)]
         (if (and last-matched? match?)
           :elle.graph/invalid
           match?))))))

(defn multiple-path-state-pred
  "Takes a graph and a set of rels. Constructs a predicate over PathStates
  which returns true iff multiple edges are subsets of rels."
  [g rels]
  (let [rels (bs/from rels)]
    (fn pred? [^PathState ps]
      (loopr [seen? false]
             [edge (.edges ps)]
             (if (bs/contains-all? rels edge)
               (if seen? ; We have two; done
                 true
                 (recur true))
               (recur seen?))
             false))))

(defn required-path-state-pred
  "Takes a graph and a collection of rels. Constructs a predicate over
  PathStates which returns true iff at least one edge is a subset of rels."
  [g rels]
  (let [rels (bs/from rels)]
    (fn pred? [^PathState ps]
      (loopr []
             [edge (.edges ps)]
             (if (bs/contains-all? rels edge)
               true
               (recur))
             false))))

(def cycle-type-priorities
  "A map of cycle types to approximately how bad they are; low numbers are more
  interesting/severe anomalies"
  (->> [:G0
        :G1c
        :G-single
        :G-nonadjacent
        :G2-item
        :G2
        :G0-process
        :G1c-process
        :G-single-process
        :G-nonadjacent-process
        :G2-item-process
        :G2-process
        :G0-realtime
        :G1c-realtime
        :G-single-realtime
        :G-nonadjacent-realtime
        :G2-item-realtime
        :G2-realtime]
       (map-indexed (fn [i t] [t i]))
       (into {})))

(def base-cycle-anomaly-specs
  "We define a specification language for different anomaly types, and a small
   interpreter to search for them. An anomaly is specified by a map including:

     :rels         A set of relationships which can be used as edges in the
                   cycle.

   There may also be supplementary relationships which may be used in addition
   to rels:

     :nonadjacent-rels  If present, a set of relationships which must may be
                        used for non-adjacent edges.

     :single-rels    Edges intersecting this set must appear exactly once in
                     this cycle.

     :multiple-rels  Edges intersecting this set must appear more than once in
                     the cycle.

     :required-rels  Edges intersecting this set must appear at least once in
                     the cycle.

   And optionally:

     :realtime?    If present, at least one edge must be :realtime.

     :process?     If present, at least one edge must be :process.

     :type         If present, the cycle explainer must tell us any cycle is of
                   this :type specifically."
  (sorted-map-by
    (fn [a b] (compare (cycle-type-priorities a 100)
                       (cycle-type-priorities b 100)))
    :G0        {:rels #{:ww}}
    ; G1c has at least a wr edge, and can take either ww or wr.
    :G1c       {:rels          #{:ww :wr}
                :required-rels #{:wr}}
    ; G-single takes ww/wr normally, but has exactly one rw.
    :G-single  {:rels        #{:ww :wr}
                :single-rels #{:rw}}
    ; G-nonadjacent is the more general form of G-single: it has multiple
    ; nonadjacent rw edges.
    :G-nonadjacent {:rels             #{:ww :wr}
                    :nonadjacent-rels #{:rw}
                    :multiple-rels    #{:rw}}
    ; G2-item, likewise, starts with an anti-dep edge, but allows more, and
    ; insists on being G2, rather than G-single. Not bulletproof, but G-single
    ; is worse, so I'm OK with it.
    :G2-item   {:rels          #{:ww :wr :rw}
                :multiple-rels #{:rw} ; A single rw rel is trivially G-Single
                :type         :G2-item}
    ; G2 is identical, except we want a cycle explained as G2
    ; specifically--it'll have at least one :predicate? edge.
    :G2        {:rels          #{:ww :wr :rw}
                :multiple-rels #{:rw}
                :type          :G2}))

(defn cycle-anomaly-spec-variant
  "Takes a variant (:process or :realtime) and a cycle anomaly pair of name and
  spec map, as in base-cycle-anomaly-specs. Returns a new [name' spec'] pair
  for the process/realtime variant of that spec."
  [variant [anomaly-name spec]]
  (let [; You can take variant edges any time
        spec' (update spec :rels conj variant)
        ; And must include the appropriate realtime/process flag
        spec' (assoc spec' (case variant
                             :realtime :realtime?
                             :process  :process?)
                     variant)
        ; If there's a type, we need to match its variant.
        spec' (if-let [t (:type spec)]
                (assoc spec' :type (keyword (str (name anomaly-name) "-"
                                                 (name variant))))
                spec')]
    [(keyword (str (name anomaly-name) "-" (name variant))) spec']))

(def cycle-anomaly-specs
  "Like base-cycle-anomaly-specs, but with realtime and process variants."
  (into base-cycle-anomaly-specs
        (mapcat (juxt identity
                      (partial cycle-anomaly-spec-variant :process)
                      (partial cycle-anomaly-spec-variant :realtime))
                base-cycle-anomaly-specs)))

(def cycle-types
  "All types of cycles we can detect."
  (set (keys cycle-anomaly-specs)))

(def process-anomaly-types
  "Anomaly types involving process edges."
  (set (filter (comp (partial re-find #"-process") name) cycle-types)))

(def realtime-anomaly-types
  "Anomaly types involving realtime edges."
  (set (filter (comp (partial re-find #"-realtime") name) cycle-types)))

(def unknown-anomaly-types
  "Anomalies which cause the analysis to yield :valid? :unknown, rather than
  false."
  #{:empty-transaction-graph
    :cycle-search-timeout})

(defn prohibited-anomaly-types
  "Takes an options map with

      :consistency-models   A collection of consistency models we expect hold
      :anomalies            A set of additional, specific anomalies we don't
                            want to see

  and returns a set of anomalies which would constitute a test failure.
  Defaults to {:consistency-models [:strict-serializable]}"
  [opts]
  (set/union (cm/all-anomalies-implying (:anomalies opts))
             (cm/anomalies-prohibited-by
               (:consistency-models opts [:strict-serializable]))))

(defn reportable-anomaly-types
  "Anomalies worth reporting on, even if they don't cause the test to fail."
  [opts]
  (set/union (prohibited-anomaly-types opts)
             unknown-anomaly-types))

(defn additional-graphs
  "Given options, determines what additional graphs we'll need to consider for
  this analysis. Options:

      :consistency-models   A collection of consistency models we expect hold
      :anomalies            A set of additional, specific anomalies we don't
                            want to see
      :additional-graphs    If you'd like even more dependencies"
  [opts]
  (let [ats (reportable-anomaly-types opts)]
    (-> ; If we need realtime, use realtime-graph. No need to bother
        ; with process, cuz we'll find those too.
        (cond (seq (set/intersection realtime-anomaly-types ats))
              #{elle/realtime-graph}

              ; If we're looking for any process anomalies...
              (seq (set/intersection process-anomaly-types ats))
              #{elle/process-graph}

              ; Otherwise, the usual graph is fine.
              true nil)
        ; Tack on any other requested graphs.
        (into (:additional-graphs opts)))))

(defn filtered-graphs
  "Takes a graph g. Returns a function that takes a set of relationships, and
  yields g filtered to just those relationships. Memoized."
  [graph]
  (memoize (fn [rels] (g/project-relationships rels graph))))

(defn warm-filtered-graphs!
  "I thought memoizing this and making it lazy was a good idea, and it might be
  later, but it also pushes a BIG chunk of work into initial cycle search---the
  timeout fires and kills a whole bunch of searches because the graph isn't
  computed yet, and that's silly. So instead, we explicitly precompute these.

  Returns fg, but as a side effect, with all the relevant filtered graphs for
  our search precomputed."
  [fg]
  (->> (vals cycle-anomaly-specs)
       (mapcat (juxt :rels :first-rels :rest-rels))
       (remove nil?)
       set
       (mapv fg))
  fg)

(def cycle-search-timeout
  "How long, in milliseconds, to look for a certain cycle in any given SCC."
  1000)

(defn cycle-cases-in-scc-fallback
  "This finds SOME cycle via DFS in a graph (guaranteed to be strongly
  connected), as a fallback in case our BFS gets stuck. We invoke this if our
  search times out."
  [g fg pair-explainer]
  (let [c (loop [rels [#{:ww}
                       #{:ww :realtime :process}
                       #{:ww :wr}
                       #{:ww :wr :realtime :process}
                       #{:ww :wr :rw}
                       #{:ww :wr :rw :realtime :process}]]
            (if-not (seq rels)
              ; Out of projections; fall back to the total scc, which
              ; MUST have a cycle.
              (g/fallback-cycle g)

              ; Try the graph which has just those relationships and
              ; that particular SCC
              (if-let [sub-scc (-> ^IGraph (fg (first rels))
                                   (g/strongly-connected-components)
                                   first)]
                ; Hey, we've got a smaller SCC to focus on!
                (g/fallback-cycle g)
                ; No dice
                (recur (next rels)))))]
    (elle/explain-cycle cycle-explainer
                        pair-explainer
                        c)))

(defn cycle-in-scc-of-type
  "Takes a graph, a filtered-graph function, a pair explainer, and a cycle
  anomaly type specification from cycle-anomaly-specs. Tries to find a cycle
  matching that specification in the graph. Returns the explained cycle, or
  nil if none found."
  [opts g fg pair-explainer
   [type {:keys [rels nonadjacent-rels single-rels multiple-rels required-rels
                 realtime? process?]
          :as spec}]]
  ; First pass: look for a candidate
  (let [; Build up path predicates based on known constraints
        ; TODO: we can compile these once and re-use them
        preds (cond-> []
                ; Note: you may be asking "hang on, what if we insist on two
                ; different kinds of required edges, and it just so happens
                ; that we choose the *same* edge for both required passes?
                ; Thankfully this does not happen: required edges admit no
                ; other possibilities, and our requirements never intersect. An
                ; #{:rt :ww} edge never satisfies an :rt requirement: only
                ; #{:rt} can do that.
                multiple-rels (conj (multiple-path-state-pred g multiple-rels))
                required-rels (conj (required-path-state-pred g required-rels))
                process?      (conj (required-path-state-pred g #{:process}))
                realtime?     (conj (required-path-state-pred g #{:realtime})))
        pred (util/fand preds)

        ; And the path transition function
        transition (cond
                     single-rels      (first-path-transition single-rels)
                     nonadjacent-rels (nonadjacent-path-transition
                                        nonadjacent-rels)
                     true             trivial-path-transition)
        ;_ (info :spec spec)
        ;_ (info ":preds\n" (with-out-str (pprint preds)))
        ;_ (info :transition transition)

        ; Now search for a candidate cycle
        cycle
        (cond ; We have predicate constraints or nonadjacents; gotta use the
              ; full state machine search. Our graph will include *everything*
              ; possible.
              (or (seq preds) nonadjacent-rels)
              (g/find-cycle-with transition pred
                                 (fg (set/union rels nonadjacent-rels
                                                required-rels single-rels
                                                multiple-rels)))

              ; No predicates, no nonadjacents. If there's a single rels
              ; constraint, we can start our search there and jump back into
              ; the rels graph.
              single-rels
              (g/find-cycle-starting-with (fg single-rels) (fg rels))

              ; Rels only, nothing else. Straightforward search. This is super
              ; fast for stuff like G0/G1c, which we check first.
              true
              (g/find-cycle (fg rels)))]
    (when cycle
      ;(info "Cycle:\n" (with-out-str (pprint cycle)))
      (let [ex (elle/explain-cycle cycle-explainer pair-explainer cycle)]
            ; Our cycle spec here isn't QUITE complete: the explainer might
            ; declare it (e.g.) g2-item vs g2. If there's a type constraint, we
            ; explain the cycle and check the type matches.
            ;(when (not= type (:type ex))
            ;  (info "Was looking for" type "but found a" (:type ex)
            ;        (with-out-str
            ;          (prn)
            ;          (pprint spec)
            ;          (prn)
            ;          (pprint ex))))
            (if (:type spec)
              (do ; _ (info "Filtering explanation")
                  ; _ (prn :explanation ex)
                  (when (= (:type spec) (:type ex))
                    ex))
              ex)))))

(defn cycle-cases-in-scc
  "Searches a graph restricted to single SCC for cycle anomalies. See
  cycle-cases."
  [opts g fg pair-explainer]
  (let [; We're going to do a partial search which can time out. If that
        ; happens, we want to preserve as many of the cycles that we found as
        ; possible, and offer an informative error message. These two variables
        ; help us do that.
        types  (atom []) ; What kind of anomalies have we searched for?
        cycles (atom [])] ; What anomalies did we find?
    (util/timeout
      (:cycle-search-timeout opts cycle-search-timeout)
      ; If we time out...
      (let [types  @types
            cycles @cycles]
        (info "Timing out search for" (peek types) "in SCC of" (b/size g)
              "transactions (checked" (str (pr-str (butlast types))")"))
        ;(info :scc
        ;      (with-out-str (pprint scc)))
        ; We generate two types of anomalies no matter what. First, an anomaly
        ; that lets us know we failed to complete the search. Second, a
        ; fallback cycle so there's SOMETHING from this SCC.
        (into [{:type               :cycle-search-timeout
                :anomaly-spec-type  (peek types)
                :does-not-contain   (drop-last types)
                :scc-size           (b/size g)}
               (cycle-cases-in-scc-fallback g fg pair-explainer)]
              ; Then any cycles we already found.
              cycles))
      ; Now, try each type of cycle we can search for
      ;
      ; TODO: many anomalies imply others. We should use the dependency graph to
      ; check for special-case anomalies before general ones, and only check for
      ; the general ones if we can't find special-case ones. e.g. if we find a
      ; g-single, there's no need to look for g-nonadjacent.
      ;(info "Checking scc of size" (b/size g))
      (doseq [type+spec cycle-anomaly-specs]
        ; (info "Checking for" type)
        (swap! types conj (first type+spec))
        (when-let [cycle (cycle-in-scc-of-type opts g fg pair-explainer
                                               type+spec)]
          (swap! cycles conj cycle)))
      @cycles)))

(defn cycle-cases-in-graph
  "Takes a search options map (see cycles), a pair explainer that
  can justify relationships between pairs of transactions, and a graph. Returns
  a map of anomaly names to sequences of cycle explanations for each. We find:

  :G0                 ww edges only
  :G1c                ww, at least one wr edge
  :G-single           ww, wr, exactly one rw
  :G-nonadjacent      ww, wr, 2+ nonadjacent rw
  :G2-item            ww, wr, 2+ rw
  :G2                 ww, wr, 2+ rw, with predicate edges

  :G0-process         G0, but with process edges
  ...

  :G0-realtime        G0, but with realtime edges
  ...

  Note that while this works for any transaction graph, including the full
  graph, we call this function with independent SCCs from the full graph.
  There's no point in exploring beyond the bounds of a single SCC; there can't
  possibly be a cycle out there. This means we can avoid materializing filtered
  graphs and searching the entire graph."
  [opts pair-explainer graph]
  (let [fg (-> (filtered-graphs graph) warm-filtered-graphs!)]
    (->> (cycle-cases-in-scc opts graph fg pair-explainer)
         (group-by :type))))

(defn cycles
  "Takes an options map, including a collection of expected consistency models
  :consistency-models, a set of additional :anomalies, an analyzer function,
  and a history. Analyzes the history and yields the analysis, plus an anomaly
  map like {:G1c [...]}."
  [opts analyzer history]
  (let [; Analyze the history.
        {:keys [graph explainer sccs] :as analysis}
        (elle/check- analyzer history)
        ; TODO: just call (analyzer (h/client-ops history)) directly; we don't
        ; need elle's core mechanism here.

        ; Spawn a task to check each SCC
        scc-tasks (mapv (fn per-scc [scc]
                          (let [g (bg/select graph (bs/from scc))]
                            (h/task history cycle-cases-in-graph []
                                    (cycle-cases-in-graph opts explainer g))))
                        sccs)

        ; And merge together
        anomalies (reduce (partial merge-with into)
                          {}
                          (map deref scc-tasks))]
    ;(pprint anomalies)
    ; Merge our cases into the existing anomalies map.
    (update analysis :anomalies merge anomalies)))

(defn cycles!
  "Like cycles, but writes out files as a side effect. Only writes files for
  relevant anomalies."
  [opts analyzer history]
  (let [analysis (cycles opts analyzer history)
        anomalies (select-keys (:anomalies analysis)
                               (reportable-anomaly-types opts))]
    ; First, text files.
    (doseq [[type cycles] anomalies]
      (when (cycle-types type)
        (elle/write-cycles! (assoc opts
                                   :pair-explainer  (:explainer analysis)
                                   :cycle-explainer cycle-explainer
                                   :filename        (str (name type) ".txt"))
                            cycles)))

    ; Then (in case they break), GraphViz plots.
    (when-let [d (:directory opts)]
      ; We do a directory for SCCs...
      (viz/plot-analysis! analysis (io/file d "sccs") opts)

      ; Then for each class of anomaly...
      (dorun
        (pmap (fn [[type cycles]]
                (when (cycle-types type)
                  ; plot-analysis! expects a list of sccs, which it's gonna go
                  ; through and plot. We're going to give it just the component
                  ; it needs to show each particular cycle explanation.
                  (let [sccs (map (comp set :cycle) cycles)]
                    (viz/plot-analysis! (assoc analysis :sccs sccs)
                                        (io/file d (name type))
                                        opts))))
        anomalies)))

    ; And return analysis
    analysis))

(defn result-map
  "Takes options, including :anomalies and :consistency-models, which define
  what specific anomalies and consistency models to look for, and a map of
  anomaly names to anomalies, and returns a map of the form...

  {:valid?        true | :unknown | false
   :anomaly-types [:g1c ...]
   :anomalies     {:g1c [...] ...}
   :impossible-models #{:snapshot-isolation ...}}"
  [opts anomalies]
  ;(info :anomalies anomalies)
  ;(info :reportable-anomaly-types (reportable-anomaly-types opts))
  (let [bad         (select-keys anomalies (prohibited-anomaly-types opts))
        reportable  (select-keys anomalies (reportable-anomaly-types opts))]
    (if (empty? reportable)
      ; TODO: Maybe return anomalies and/or a boundary here too, and just not
      ; flag them as invalid? Maybe? I dunno, might be noisy, especially if we
      ; expect to see them all the time.
      {:valid? true}
      (merge {:valid?            (cond (seq bad)          false
                                       (seq reportable)   :unknown
                                       true               true)
              :anomaly-types     (sort (keys reportable))
              :anomalies         reportable}
             (cm/friendly-boundary (keys anomalies))))))

(defn key-dist-scale
  "Takes a key-dist-base and a key count. Computes the scale factor used for
  random number selection used in rand-key."
  [key-dist-base key-count]
  (-> (Math/pow key-dist-base key-count)
      (- 1)
      (* key-dist-base)
      (/ (- key-dist-base 1))))

(defn rand-key
  "Helper for generators. Takes a key distribution (e.g. :uniform or
  :exponential), a key distribution scale, a key distribution base, and a
  vector of active keys. Returns a random active key."
  [key-dist key-dist-base key-dist-scale active-keys]
  (case key-dist
    :uniform     (rand-nth active-keys)
    :exponential (let [ki (-> (rand key-dist-scale)
                              (+ key-dist-base)
                              Math/log
                              (/ (Math/log key-dist-base))
                              (- 1)
                              Math/floor
                              long)]
                   (nth active-keys ki))))

(defn fresh-key
  "Takes a key and a vector of active keys. Returns the vector with that key
  replaced by a fresh key."
  [^java.util.List active-keys k]
  (let [i (.indexOf active-keys k)
        k' (inc (reduce max active-keys))]
    (assoc active-keys i k')))

(defn wr-txns
  "A lazy sequence of write and read transactions over a pool of n numeric
  keys; every write is unique per key. Options:

    :key-dist             Controls probability distribution for keys being
                          selected for a given operation. Choosing :uniform
                          means every key has an equal probability of appearing.
                          :exponential means that key i in the current key pool
                          is k^i times more likely than the first key to be
                          chosen. Defaults to :exponential.

    :key-dist-base        The base for an exponential distribution. Defaults
                          to 2, so the first key is twice as likely as the
                          second, which is twice as likely as the third, etc.

    :key-count            Number of distinct keys at any point. Defaults to
                          10 for exponential, 3 for uniform.

    :min-txn-length       Minimum number of operations per txn

    :max-txn-length       Maximum number of operations per txn

    :max-writes-per-key   Maximum number of operations per key"
  ([opts]
   (let [key-dist  (:key-dist  opts :exponential)
         key-count (:key-count opts (case key-dist
                                      :exponential 10
                                      :uniform     3))]
     (wr-txns (assoc opts
                     :key-dist  key-dist
                     :key-count key-count)
              {:active-keys (vec (range key-count))})))
  ([opts state]
   (lazy-seq
     (let [min-length           (:min-txn-length      opts 1)
           max-length           (:max-txn-length      opts 2)
           max-writes-per-key   (:max-writes-per-key  opts 32)
           key-dist             (:key-dist            opts :exponential)
           key-dist-base        (:key-dist-base       opts 2)
           key-count            (:key-count           opts)
           ; Choosing our random numbers from this range converts them to an
           ; index in the range [0, key-count).
           key-dist-scale       (key-dist-scale key-dist-base key-count)
           length               (+ min-length (rand-int (- (inc max-length)
                                                           min-length)))
           [txn state] (loop [length  length
                              txn     []
                              state   state]
                         (let [^java.util.List active-keys
                               (:active-keys state)]
                           (if (zero? length)
                             ; All done!
                             [txn state]
                             ; Add an op
                             (let [f (rand-nth [:r :w])
                                   k (rand-key key-dist key-dist-base
                                               key-dist-scale active-keys)
                                   v (when (= f :w) (get state k 1))]
                               (if (and (= :w f)
                                        (< max-writes-per-key v))
                                 ; We've updated this key too many times!
                                 (let [state' (update state :active-keys
                                                      fresh-key k)]
                                   (recur length txn state'))
                                 ; Key is valid, OK
                                 (let [state' (if (= f :w)
                                                (assoc state k (inc v))
                                                state)]
                                   (recur (dec length)
                                          (conj txn [f k v])
                                          state')))))))]
       (cons txn (wr-txns opts state))))))

(defn gen
  "Takes a sequence of transactions and returns a sequence of invocation
  operations."
  [txns]
  (map (fn [txn] {:type :invoke, :f :txn, :value txn}) txns))
