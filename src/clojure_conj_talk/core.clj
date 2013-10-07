(ns clojure-conj-talk.core
  (:refer-clojure :exclude [map reduce into partition partition-by take merge])
  (:require [clojure.core.async :refer :all]
            [clojure.pprint :refer [pprint]]
            [cheshire.core :as cheshire]))


;; The most basic primitive in Core.Async is the channel

(def c (chan))



;; We can attach listeners via take!

(take! c (fn [v] (println v)))


;; And we can send values via put!

(put! c 42)


;; And in reverse order

(put! c "Hello World")

(take! c (fn [v] (println "Got " v)))

;; The semantics are simple. Callbacks are one-shot. And you can have
;; many readers/writers. Each puts/gets a single value. There is no fan-out
;; or fan-in.



;; Notice the different location of the output, one
;; side or the other dispatches to a threadpool to
;; run the attached callback.

;; We can wait until the put is finished by passing a callback

(put! c "Hello World" (fn [] (println "Done putting")))


(take! c (fn [v] (println "Got " v)))

;; Try the above again in reverse order to show dispatching



;;;;;;; Introducing <!! and >!! ;;;;;;

;; Well that's nice, but it's a pain to write code in that form,
;; so let's use something that uses promises:

(defn takep [c]
  (let [p (promise)]
    (take! c (fn [v] (deliver p v)))
    p))

;; Now we can block the current thread waiting on the promise.
(future (println "Got!" @(takep c)))

(put! c 42)


;; And we can do the reverse with put!

(defn putp [c val]
  (let [p (promise)]
    (put! c val (fn [] (deliver p nil)))
    p))


(future (println "Done" @(putp c 42)))

(future (println "Got!" @(takep c)))


;; Well, that's exactly what clojure.core.async/<!! and >!! do

(future (println "Done" (>!! c 42)))

(future (println "Got! " (<!! c)))


;; But future doesn't really fit here, as it returns a promise, why not make
;; it return a channel? This is what core.async's thread macro does:

(<!! (thread 42))

(thread (println "It works!" (<!! (thread 42))))

;;;;;;;; The Go Block ;;;;;;;;

;; That's all well and good, but who wants to tie up a thread
;; when we could use simple callbacks. This is what the go macro
;; does. It lets you write code that looks like the above code,
;; but it re-writes all your code to use callbacks.


(<!! (go 42))

(go (println "It works!" (<! (go 42))))

;; Wait....why did we use <!? Well <!! is simply a function that uses
;; blocking promises to wait for channel values. That would mess up the fixed
;; size thread pool that go blocks dispatch in. So we must define two sets
;; of operations

;; for thread, use <!! and >!! (blocking)

;; for go, use <! and >! (parking)



;; How do go blocks work? Let's take a look.

(pprint (macroexpand '(go 42)))

;; The code is rewritten as a state machine. Each block ends with a flow
;; control command to the next block. From this we can start/stop the state
;; machine as desired.

(pprint (macroexpand '(go (>! c (inc (<! c))))))


;; You really don't have to worry about most of this. Just accept that it works
;; and look up clojure core.async state machines on the internet if you want
;; more information.


;;;;; Buffer Types ;;;;;


;; Fixed length buffer
(def fbc (chan 1))

(go (>! fbc 1)
    (println "done 1"))
(go (>! fbc 2)
    (println "done2"))

(<!! fbc)
(<!! fbc)


;; Dropping buffer (aka. drop newest)

(def dbc (chan (dropping-buffer 1)))

(go (>! dbc 1)
    (println "done 1"))

(go (>! dbc 2)
    (println "done 2"))

(<!! dbc) ;; returns 1

;; Sliding buffer (aka. drop oldest)

(def sbc (chan (sliding-buffer 1)))

(go (>! sbc 1)
    (println "done 1"))

(go (>! sbc 2)
    (println "done 2"))

(<!! sbc) ;; returns 2

;;; Custom Buffers ;;;;

; Need not be thread-safe. They will only be accessed by a single
; thread at a time

(require '[clojure.core.async.impl.protocols :as impl])
(import '[java.util LinkedList])

(defn debug-buffer [n a]
  (let [buf (LinkedList.)
        dbb (reify
              impl/Buffer
              (full? [this]
                (= (.size buf) n))
              (remove! [this]
                (swap! a dec)
                (.removeLast buf))
              (add! [this itm]
                (swap! a inc)
                (.addFirst buf itm))
              clojure.lang.Counted
              (count [this]
                (.size buf)))]
    dbb))

(def a-size (atom 0))

(def dbbc (chan (debug-buffer 20 a-size)))

(>!! dbbc 0)
(>!! dbbc 1)
(>!! dbbc 2)

(println @a-size)

(println (<!! dbbc) @a-size)


;;;; Thread Pool Service


(defn thread-pool-service [ch f max-threads timeout-ms]
  (let [thread-count (atom 0)
        buffer-status (atom 0)
        buffer-chan (chan)
        thread-fn (fn []
                    (swap! thread-count inc)
                    (loop []
                      (when-let [v (first (alts!! [buffer-chan (timeout timeout-ms)]))]
                        (f v)
                        (recur)))
                    (swap! thread-count dec)
                    (println "Exiting..."))]
    (go (loop []
          (when-let [v (<! ch)]
            (if-not (alt! [[buffer-chan v]] true
                          :default false)
              (loop []
                (if (< @thread-count max-threads)
                  (do (put! buffer-chan v)
                      (thread (thread-fn)))
                  (when-not (alt! [[buffer-chan v]] true
                                  [(timeout 1000)] ([_] false))
                    (recur)))))
            (recur)))
        (close! buffer-chan))))

(def exec-chan (chan))
(def thread-pool (thread-pool-service exec-chan (fn [x]
                                                  (println x)
                                                  (Thread/sleep 5000)) 3 3000))



(>!! exec-chan "Hello World")



;;;;; Logging Handler ;;;;;

(def log-chan (chan))

(thread
 (loop []
   (when-let [v (<!! log-chan)]
     (println v)
     (recur)))
 (println "Log Closed"))


(close! log-chan)

(defn log [msg]
  (>!! log-chan msg))

(log "foo")

;;;;; HTTP Async ;;;;;;


(require '[org.httpkit.client :as http])

(defn http-get [url]
  (let [c (chan)]
    (println url)
    (http/get url
              (fn [r] (put! c r)))
    c))

(def key "b4cb6cd7a349b47ccfbb80e05a601a7c")

(defn request-and-process [url]
  (go
   (-> (str "http://api.themoviedb.org/3/" url "api_key=" key)
       http-get
       <!
       :body
       (cheshire/parse-string true))))

(defn latest-movies []
  (request-and-process "movies/latest?"))

(defn top-rated-movies []
  (request-and-process "movie/top_rated?"))

(<!! (top-rated-movies))

(defn movie-by-id [id]
  (request-and-process (str "movie/" id "?")))

(<!! (movie-by-id 238))

(defn movie-cast [id]
  (request-and-process (str "movie/" id "/casts?")))

(<!! (movie-cast 238))

(defn people-by-id [id]
  (request-and-process (str "person/" id "?")))

(<!! (people-by-id 3144))

(defn omdb-by-title [q]
  (go
   (-> (str "http://www.omdbapi.com/?t=" q)
       http-get
       <!
       :body
       (cheshire/parse-string true))))

(defn omdb-item [id]
  (go
   (-> (str "http://www.omdbapi.com/?tomatoes=true&i=" id)
       http-get
       <!
       :body
       (cheshire/parse-string true))))

(<!! (omdb-by-title "the+matrix"))
(<!! (omdb-item "tt1285016"))




(time (dotimes [x 10]
        (<!! (http-get "http://www.imdb.com/xml/find?json=1&q=ben+afleck"))))

(time (let [chans (doall (for [x (range 10)]
                             (http-get "http://www.imdb.com/xml/find?json=1&q=ben+afleck")))]
        (doseq [c chans]
          (<!! c))))



;;;; Actors ;;;;

;; Please don't do this:

(defprotocol IActor
  (! [this msg]))

(defn spawn [f]
  (let [c (chan Integer/MAX_VALUE)]
    (go (loop [f f]
          (recur (f (<! c)))))
    (reify IActor
      (! [this msg]
        (put! c msg)))))


(defn spawn-counter []
  (let [counter (fn counter [cnt msg]
                  (case (:type msg)
                    :inc (partial counter (inc cnt))
                    :get (do (! (:to msg) cnt)
                             (partial counter cnt))))]
    (spawn (partial counter 0))))

(defn printer []
  (spawn (fn ptr [msg]
           (println "Got:" msg)
           ptr)))

(def ptr (printer))

(def counter (spawn-counter))

(! counter {:type :inc})

(! counter {:type :get
            :to ptr})


;; This follows the actor model closely
;; 1) send to other actors
;; 2) create new actors
;; 3) specify how to handle the next message


;; <pontificate>

;; My critique of this system
;; 1) unbounded queue (mailbox)
;; 2) internal mutating state (hidden in function closures)
;; 3) must send message to deref (mailbox could be backed up)
;; 4) couples a queue, with mutating state, with a process
;; 5) basically async OOP

;; </pontificate>



;;;;;; Limited Access to a Shared Resource ;;;;;


(defn function-service [f num-threads]
  (let [c (chan num-threads)]
    (dotimes [x num-threads]
      (thread
       (loop []
         (when-let [[args ret-chan] (<!! c)]
           (>!! ret-chan (apply f args))
           (recur)))))
    c))

(def slurp-service (function-service (comp read-string slurp) 2))


(defn slurp-async [& args]
  (let [c (chan 1)]
    ;; put! is tied to the take! from chan
    ;; so no unbounded-ness here.
    (put! slurp-service [args c])
    c))



(<!! (slurp-async "project.clj"))

(close! slurp-service)


;;;; ClojureScript examples ;;;;

(require '[cljs.core.async.macros :refer [go]])

(require '[cemerick.piggieback]
         '[cemerick.austin]
         '[cemerick.austin.repls])
(cemerick.piggieback/cljs-repl)
(cemerick.austin.repls/exec
 :exec-cmds ["open" "-ga" "/Applications/Google Chrome.app"])

(cemerick.austin/exec-env )

(require '[cljs.repl :as repl])
(require '[cljs.repl.browser :as browser])

(def env (browser/repl-env))
(repl/repl env)

(go 42)

(def repl-env (reset! cemerick.austin.repls/browser-repl-env
                      (cemerick.austin/repl-env)))
(cemerick.austin.repls/cljs-repl repl-env)


(ns cljs-examples
  (:require [cljs.core.async :refer [chan put! take!]]))
