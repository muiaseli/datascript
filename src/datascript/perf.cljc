(ns datascript.perf
  #?(:cljs (:require-macros datascript.perf)))

(def ^:const   enabled? false)
(def ^:dynamic debug?   false)

(def ^:dynamic *warmup-t* 500)
(def ^:dynamic *bench-t*  1000)
(def ^:dynamic *step*     10)
(def ^:dynamic *repeats*  5)

#?(:cljs (enable-console-print!))

;; helpers

(defn percentile [xs n]
  (->
    (sort xs)
    (nth (min (dec (count xs))
              (int (* n (count xs)))))))

(defn to-fixed [n places]
  #?(:cljs (.toFixed n places)
     :clj  (format (str "%." places "f") (double n))))

(defn round [n]
  (cond
    (> n 1)        (to-fixed n 1)
    (> n 0.1)      (to-fixed n 2)
    (> n 0.001)    (to-fixed n 4)
    (> n 0.000001) (to-fixed n 7)
    :else          n))

(defn pad [n l]
  (let [s (str n)]
    (if (<= (count s) l)
      (str (apply str (repeat (- l (count s)) " ")) s)
      s)))

(defn format-number [n]
  (pad (round n) 6))

(defn format-time [dt]
  (str "[ " (format-number dt) " ms ]"))

#?(:cljs
    (defn ^number now []
      (js/window.performance.now))
   :clj
    (defn ^Long now []
      (System/currentTimeMillis)))

;; minibench

#?(:clj
  (defmacro dotime [duration & body]
   `(let [start-t# (now)
          end-t#   (+ ~duration start-t#)]
      (loop [iterations# *step*]
        (dotimes [_# *step*] ~@body)
        (let [now# (now)]
          (if (< now# end-t#)
            (recur (+ *step* iterations#))
            (/ (- now# start-t#) iterations#)))))))

#?(:clj
  (defmacro minibench [msg & body]
   `(let [_#     (dotime *warmup-t* ~@body)
          avg-t# (dotime *bench-t* ~@body)]
      (println (format-time avg-t#) ~msg "avg time")
      (binding [debug? true]
        ~@body))))

#?(:clj
  (defmacro bench [msg & body]
   `(let [_#       (println ~msg)
          _#       (dotime *warmup-t* ~@body)
          results# (into [] (for [_# (range *repeats*)]
                              (dotime *bench-t* ~@body)))]
      (println 
        "[ min:" (format-number (reduce min results#))
        "] [ med:" (format-number (percentile results# 0.5))
        "] [ max:" (format-number (reduce max results#))
        "] ms")
      (binding [debug? true]
        ~@body)
      (println))))

;; flame graph

(defrecord Frame [parent start end message children])

(def current-frame (volatile! nil))

(defn println-frame [frame depth]
  (let [msg (apply str
                   (apply str (repeat depth "  "))
                   (interpose " " (:message frame)))]
    (if (:start frame)
      (println (format-time (- (:end frame) (:start frame))) msg)
      (println "[           ]" msg))
    (doseq [ch (:children frame)]
      (println-frame ch (inc depth)))))

(defn start-frame [time?]
  (vreset! current-frame (->Frame @current-frame (when time? (now)) nil nil [])))

(defn end-frame [& msg]
  (let [f ^Frame @current-frame
        f (assoc f
            :end (now)
            :message msg)
        p (some-> (:parent f)
                  (update :children conj f))]
    (when (nil? p)
      (println-frame f 0))
    (vreset! current-frame p)))

#?(:clj
  (defmacro when-debug [& body]
    (when enabled?
      `(when debug?
         ~@body))))

#?(:clj
  (defmacro debug [& msgs]
    (when enabled?
      `(when debug?
         (start-frame false)
         (end-frame ~@msgs)))))

#?(:clj
  (defmacro measure [body & msgs]
    (if enabled?
      (let [sym   (gensym)
            msgs* (clojure.walk/postwalk #(if (= (symbol "%") %) sym %) msgs)]
       `(if debug?
          (let [_#   (start-frame true)
                ~sym ~body]
            (end-frame ~@msgs*)
            ~sym)
          ~body))
      body)))
