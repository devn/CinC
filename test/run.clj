(defn unmap [& nss]
    (doseq [ns nss
            :when (find-ns ns)
            [sym var] (ns-map ns)]
        (when (and (var? var) ((set nss) (.getName (.ns var))))
            (ns-unmap ns sym))))


(unmap 'clojure.compiler 'clojure.analyzer 'user)

(use 'clojure.analyzer :reload)
(use 'clojure.compiler :reload)

(use 'clojure.repl)
(use 'clojure.pprint)

(defn ppm [obj]
    (let [orig-dispatch *print-pprint-dispatch*]
        (with-pprint-dispatch
            (fn [o]
                (when (meta o)
                    (print "^")
                    (orig-dispatch (meta o))
                    (pprint-newline :fill ))
                (orig-dispatch o))
            (pprint obj))))

;(eval-trace '(fn [] 1))

(defprotocol Blah
  (foo [a])
  (bar [a]))

(defrecord P []
  Blah
  (foo [a] 1))

(def sample (P.))

(process-frames (analyze '(foo (bar sample))))