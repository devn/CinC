(ns clojure.compiler
  (:refer-clojure :exclude [eval load munge *ns*])
  (:require [clojure.java.io :as io]
   [clojure.string :as string])
  (:use [clojure
         [analyzer :only [analyze namespaces *ns*]]
         [walk :only [walk]]
         [reflect :only [type-reflect]]
         [set :only [select]]])
  (:import [org.objectweb.asm Type Opcodes ClassReader ClassWriter]
   [org.objectweb.asm.commons Method GeneratorAdapter]
   [org.objectweb.asm.util CheckClassAdapter TraceClassVisitor]
   [clojure.lang DynamicClassLoader RT Util]))

(def max-positional-arity 20)

(def ^:private char-map
  {\- "_",
   \: "_COLON_",
   \+ "_PLUS_",
   \> "_GT_",
   \< "_LT_",
   \= "_EQ_",
   \~ "_TILDE_",
   \! "_BANG_",
   \@ "_CIRCA_",
   \# "_SHARP_",
   \' "_SINGLEQUOTE_",
   \" "_DOUBLEQUOTE_",
   \% "_PERCENT_",
   \^ "_CARET_",
   \& "_AMPERSAND_",
   \* "_STAR_",
   \| "_BAR_",
   \{ "_LBRACE_",
   \} "_RBRACE_",
   \[ "_LBRACK_",
   \] "_RBRACK_",
   \/ "_SLASH_",
   \\ "_BSLASH_",
   \? "_QMARK_",
   \. "_DOT_"})

(defn munge [s]
  (symbol (apply str (map #(char-map % %) (str s)))))

(defmulti ^:private emit :op )

(defmulti ^:private emit-constant class)

(def ^:dynamic ^:private *gen* nil)

(def ^:dynamic ^:private *loader* (DynamicClassLoader.))

(def ^:private object-type (Type/getType Object))
(def ^:private class-type (Type/getType Class))

(def ^:private ifn-type (Type/getType clojure.lang.IFn))
(def ^:private afn-type (Type/getType clojure.lang.AFn))
(def ^:private var-type (Type/getType clojure.lang.Var))
(def ^:private symbol-type (Type/getType clojure.lang.Symbol))
(def ^:private util-type (Type/getType clojure.lang.Util))
(def ^:private arg-types (into-array (for [i (range max-positional-arity)]
                                       (into-array Type (repeat i object-type)))))

(def ^:private constructor-method (Method/getMethod "void <init> ()"))
(def ^:private var-get-method (Method/getMethod "Object get()"))
(def ^:private var-get-raw-method (Method/getMethod "Object getRawRoot()"))

(defn ^:private var! [sym]
  (RT/var (namespace sym) (name sym)))

(defn dynamic? [v]
  (or (:dynamic (meta v)) (.isDynamic v)))


(def ^:dynamic *trace-bytecode* false)
(def ^:dynamic *check-bytecode* false)
(defn load-class [name bytecode form]
  (println "load-class " name *trace-bytecode*)
  (let [binary-name (.replace name \/ \.)]
    (when (or *trace-bytecode* *check-bytecode*)
      (let [cr (ClassReader. bytecode)
            w (java.io.PrintWriter. (if *trace-bytecode* *out* (java.io.StringWriter.)))
            v (TraceClassVisitor. w)
            v (if *check-bytecode* (CheckClassAdapter. v) v)]
        (.accept cr v 0)))
    (.defineClass *loader* binary-name bytecode form)))

(def ^:dynamic ^:private *frame*)

(defn ^:private new-frame [] (atom {}))


(defn ^:private collect-frame [ast]
  (cond
    (= :invoke (:op ast))
    (let [s (-> ast :f :info :name )]
      (when (-> s var! meta :protocol )
        {:callsites [s]}))

    (= :var (:op ast))
    (let [var (-> ast :info :name )]
      (when-not (resolve var)
        (throw (Util/runtimeException (str "No such var: " var))))
      {:vars [var]})

    (= :def (:op ast))
    {:vars [(:name ast)]}

    (= :constant (:op ast))
    {:constants [(:form ast)]}

    :else nil
    ))

(defn ^:private new-frame? [form]
  (#{:fn } (:op form)))

(defn ^:private process-frames-helper [f ast]
  (let [post-fn
        (fn [form]
          (swap! *frame* (partial merge-with (comp vec distinct concat)) (f form))
          form)]
    (if (new-frame? ast)
      (binding [*frame* (new-frame)]
        (let [res (walk (partial process-frames-helper f) post-fn ast)]
          (merge res @*frame*)))
      (walk (partial process-frames-helper f) post-fn ast))))

(defn process-frames [ast]
  (binding [*frame* (new-frame)]
    (merge (process-frames-helper collect-frame ast) @*frame*)))

(defn ^:private emit-vars [cv {:keys [vars env]}]
  (doseq [v vars]
    (let [n (str (munge v))
          var (var! v)]
      (.visitField cv
        (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_STATIC) n (.getDescriptor var-type) nil nil)
      (swap! *frame* assoc-in [:fields var] n))))

(defmulti type-of class)
(defmethod type-of :default [o] object-type)
(defmethod type-of clojure.lang.Var [o] var-type)
(defmethod type-of clojure.lang.Symbol [o] symbol-type)

(defn ^:private emit-constants [cv {constants :constants}]
  (dorun (map-indexed
           (fn [i v]
             (let [name (str "CONSTANT" i)]
               (.visitField cv
                 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_STATIC)
                 name
                 (.getDescriptor (type-of v))
                 nil
                 nil)
               (swap! *frame* assoc-in [:fields v] name)))
           constants)))

(defn ^:private emit-proto-callsites [cv {:keys [callsites env]}]
  (dorun (map-indexed
           (fn [i site]
             (let [cached-class (str "__cached_class__" i)
                   cached-proto-fn (str "__cached_proto_fn__" i)
                   cached-proto-impl (str "__cached_proto_impl__" i)]
               ; 		 cv.visitField(ACC_PRIVATE, cachedClassName(i), CLASS_TYPE.getDescriptor(), null, null);
               ;      cv.visitField(ACC_PRIVATE, cachedProtoFnName(i), AFUNCTION_TYPE.getDescriptor(), null, null);
               ;      cv.visitField(ACC_PRIVATE, cachedProtoImplName(i), IFN_TYPE.getDescriptor(), null, null);
               (.visitField cv (+ Opcodes/ACC_PRIVATE) cached-class (.getDescriptor class-type) nil nil)
               (.visitField cv (+ Opcodes/ACC_PRIVATE) cached-proto-fn (.getDescriptor afn-type) nil nil)
               (.visitField cv (+ Opcodes/ACC_PRIVATE) cached-proto-impl (.getDescriptor ifn-type) nil nil)
               (swap! *frame* assoc-in [:protos site]
                              {:cached-class cached-class :cached-proto-fn cached-proto-fn :cached-proto-impl cached-proto-impl})))
           callsites)))


(defmulti ^:private emit-value class)

(defmethod emit-value java.lang.Long [v]
  (.push *gen* v)
  (.box *gen* (Type/getType "J")))

(defmethod emit-value clojure.lang.Symbol [v]
  (.push *gen* (namespace v))
  (.push *gen* (name v))
  (.invokeStatic *gen* (Type/getType clojure.lang.Symbol) (Method/getMethod "clojure.lang.Symbol intern(String,String)")))

(defmethod emit-value clojure.lang.Var [v]
  (.push *gen* (str (.ns v)))
  (.push *gen* (name (.sym v)))
  (.invokeStatic *gen* (Type/getType RT) (Method/getMethod "clojure.lang.Var var(String,String)")))

(defn ^:private emit-constructors [cv ast]
  (let [ctor (GeneratorAdapter. Opcodes/ACC_PUBLIC constructor-method nil nil cv)]
    (.loadThis ctor)
    (.invokeConstructor ctor object-type constructor-method)
    (.returnValue ctor)
    (.endMethod ctor))
  (let [m (Method/getMethod "void <clinit> ()")
        line (-> ast :env :line )
        {:keys [class fields]} @*frame*]
    (binding [*gen* (GeneratorAdapter. Opcodes/ACC_PUBLIC m nil nil cv)]
      (.visitCode *gen*)
      (when line
        (.visitLineNumber *gen* line (.mark *gen*)))
      (doseq [[v n] fields]
        (emit-value v)
        (.checkCast *gen* (type-of v))
        (.putStatic *gen* class n (type-of v)))
      (.returnValue *gen*)
      (.endMethod *gen*))))

(defn ^:private debug-info [name source-path line]
  (str "SMAP\n"
    name ".java\n"
    "Clojure\n"
    "*S Clojure\n"
    "*F\n"
    "+ 1 " name "\n"
    source-path "\n"
    "*L\n"
    line
    "*E"))

(defn ^:private emit-class [internal-name ast emit-methods]
  (binding [*frame* (new-frame)]
    (let [cw (ClassWriter. ClassWriter/COMPUTE_MAXS)]
      (swap! *frame* merge ast {:class (Type/getType internal-name)})
      (doto cw
        (.visit Opcodes/V1_5 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER) internal-name nil "java/lang/Object" nil)
        ;                (.visitSource internal-name (debug-info internal-name "NO_SOURCE_PATH" (-> ast :env :line)))
        (.visitSource internal-name nil)
        (emit-vars ast)
        (emit-constants ast)
        (emit-proto-callsites ast)
        (emit-constructors ast)
        (emit-methods ast)
        .visitEnd)
      cw)))

(defn ^:private emit-statement [cv ast]
  (binding [*gen* (GeneratorAdapter. Opcodes/ACC_PUBLIC (Method/getMethod "Object invoke()") nil nil cv)]
    (.visitCode *gen*)
    (emit ast)
    (.returnValue *gen*)
    (.endMethod *gen*)))

(defn eval
  ([form & {:keys [trace check]}]
   (binding [*trace-bytecode* trace
             *check-bytecode* check]
     (eval form)))
  ([form]
   (let [env {:ns (@namespaces *ns*) :context :statement :locals {}}
         ast (analyze env form)
         ast (process-frames ast)
         internal-name (str "repl/Temp" (RT/nextID))
         cw (emit-class internal-name ast emit-statement)]
     (binding [*loader* (DynamicClassLoader.)]
       (let [bytecode (.toByteArray cw)
             class (load-class internal-name bytecode form)
             instance (.newInstance class)]
         (.invoke instance))))))

(defn load [f]
  (let [res (or (io/resource f) (io/as-url (io/as-file f)))]
    (assert res (str "Can't find " f " in classpath"))
    (binding [*file* (.getPath res)]
      (with-open [r (io/reader res)]
        (let [env {:ns (@namespaces *ns*) :context :statement :locals {}}
              pbr (clojure.lang.LineNumberingPushbackReader. r)
              eof (Object.)]
          (loop [r (read pbr false eof false)]
            (let [env (assoc env :ns (@namespaces *ns*))]
              (when-not (identical? eof r)
                (eval r)
                (recur
                  (read pbr false eof false))))))))))

(defn ^:private emit-args-and-call [args]
  (doseq [arg args]
    (emit arg))
  (.invokeInterface *gen* ifn-type (Method. "invoke" object-type (get arg-types (count args)))))

(defmulti maybe-class class)
(defmethod maybe-class String [s]
  (try
    (RT/classForName s)
    (catch Exception e nil)))
(defmethod maybe-class Class [c] c)
(defmethod maybe-class clojure.lang.Symbol [sym]
  (when-not (namespace sym)
    ; TODO: I have no idea what this used to do
    ;    (if(Util.equals(sym,COMPILE_STUB_SYM.get()))
    ;    return (Class) COMPILE_STUB_CLASS.get();
    (let [ret (resolve sym)]
      (if (instance? Class ret)
        ret
        (maybe-class name)))))

(defn ^:private emit-invoke-proto [{:keys [f args]}]
  (let [{:keys [class fields protos]} @*frame*
        on-label (.newLabel *gen*)
        call-label (.newLabel *gen*)
        end-label (.newLabel *gen*)
        fsym (-> f :info :name )
        fvar (var! fsym)
        proto @(-> fvar meta :protocol )
        e (get args 0)
        protocol-on (maybe-class (:on proto))]
    ; load the first arg, so we can see its type
    (emit e)
    (.dup *gen*)
    (.invokeStatic *gen* util-type (Method/getMethod "Class classOf(Object)"))
    (.loadThis *gen*)
    (.getField *gen* class (-> protos fsym :cached-class ) class-type) ; target,class,cached-class
    ; Check if first arg type is same as cached
    (.visitJumpInsn *gen* Opcodes/IF_ACMPEQ call-label) ; target
    (when protocol-on
      (.dup *gen*) ; target, target
      (.instanceOf *gen* (Type/getType protocol-on))
      (.ifZCmp *gen* GeneratorAdapter/NE on-label))
    (.dup *gen*) ; target, target
    (.invokeStatic *gen* util-type (Method/getMethod "Class classOf(Object)")) ; target, class
    (.loadThis *gen*)
    (.swap *gen*)
    (.putField *gen* class (-> protos fsym :cached-class ) class-type) ; target

    (assert (fields fvar))

    ; Slow path through proto-fn
    (.mark *gen* call-label) ; target
    (.getStatic *gen* class (fields fvar) var-type)
    (.invokeVirtual *gen* var-type var-get-raw-method) ; target, proto-fn
    (.swap *gen*)
    (emit-args-and-call (rest args))
    (.goTo *gen* end-label)

    ; Fast path through interface
    (.mark *gen* on-label)
    (when protocol-on
      (let [mmap (:method-map proto)
            key (or (-> fsym name keyword mmap) (throw (IllegalArgumentException.
                                                         (str "No method of interface: " protocol-on
                                                                                         " found for function: " fsym " of protocol: " (-> fvar meta :protocol )
                                                                                         " (The protocol method may have been defined before and removed.)"))))
            meth (-> key name munge)
            members (-> protocol-on type-reflect :members )
            methods (select #(and (= (:name meth)) (= (dec (count args)) (-> % :parameter-types count))) members)]
        (when-not (= (count methods) 1)
          (throw (IllegalArgumentException. (str "No single method: " meth " of interface: " protocol-on
                                                                      " found for function: " fsym " of protocol: " (-> fvar meta :protocol )))))
        ;    MethodExpr.emitTypedArgs(objx, gen, onMethod.getParameterTypes(), RT.subvec(args,1,args.count()));
        ;    if(context == C.RETURN)
        ;    {
        ;      ObjMethod method = (ObjMethod) METHOD.deref();
        ;      method.emitClearLocals(gen);
        ;      }
        ;    Method m = new Method(onMethod.getName(), Type.getReturnType(onMethod), Type.getArgumentTypes(onMethod));
        ;  gen.invokeInterface(Type.getType(protocolOn), m);
        ;  HostExpr.emitBoxReturn(objx, gen, onMethod.getReturnType());
        (println "Protocol primitive path not implemented yet")
        (.mark *gen* end-label)))))

(defn ^:private emit-invoke-fn [{:keys [f args env]}]
  (emit f)
  (.checkCast *gen* ifn-type)
  (emit-args-and-call args))

(defmethod emit :invoke [ast]
  (.visitLineNumber *gen* (-> ast :env :line ) (.mark *gen*))
  (if (:protocol (meta (-> ast :f :info :name var!)))
    (emit-invoke-proto ast)
    (emit-invoke-fn ast)))

(defmethod emit :var [{:keys [info env]}]
  (let [v (:name info)
        var (var! v)
        {:keys [class fields]} @*frame*]
    (.getStatic *gen* class (fields var) var-type)
    (.invokeVirtual *gen* var-type (if (dynamic? var) var-get-method var-get-raw-method))))

(defmethod emit :def [{:keys [name form init env doc export] :as args}]
  (let [var (var! name)
        {:keys [class fields]} @*frame*]
    (.getStatic *gen* class (fields var) var-type)
    (when (dynamic? form)
      (.push *gen* true)
      (.invokeVirtual *gen* var-type (Method/getMethod "clojure.lang.Var setDynamic(boolean)")))
    (when (meta form)
      (println "TODO: Copy meta on def"))
    (when init
      (.dup *gen*)
      (emit init)
      (.invokeVirtual *gen* var-type (Method/getMethod "void bindRoot(Object)")))))

(defmulti ^:private emit-constant class)
(defmethod emit-constant Long [v]
  (let [{:keys [class fields]} @*frame*]
    (.getStatic *gen* class (fields v) (type-of v))))

(defmethod emit :constant [{:keys [form env]}]
  (emit-constant form))

(defn ^:private notsup [& args]
  (Util/runtimeException (apply str "Unsupported: " args)))

(defn emit-fn-method [cv {:keys [name params statements ret env recurs] :as args}]
  (binding [*gen*
            (GeneratorAdapter. Opcodes/ACC_PUBLIC
              (Method/getMethod (str "Object invoke(" (apply str (interpose \, params)) ")")) nil nil cv)]
    (.visitCode *gen*)
    (when recurs (notsup "recurs"))
    (when statements
      (dorun (map emit statements)))
    (emit ret)
    (.returnValue *gen*)
    (.endMethod *gen*)))

(defn ^:private emit-fns [cv {:keys [name env methods max-fixed-arity variadic recur-frames] :as ast}]
  (let [loop-locals (seq (mapcat :names (filter #(and % @(:flag %)) recur-frames)))]
    (when loop-locals
      (notsup "loop-locals"))
    (doseq [meth methods]
      (if (:variadic meth)
        (notsup '(emit-variadic-fn-method meth))
        (emit-fn-method cv meth)))
    (when loop-locals
      (notsup "loop-locals"))))

(defmethod emit :fn [ast]
  (let [name (str (or (:name ast) (gensym)))
        cw (emit-class name ast emit-fns)
        bytecode (.toByteArray cw)
        class (load-class name bytecode ast)]
    (.newInstance *gen* (Type/getType class))
    (.dup *gen*)
    (.invokeConstructor *gen* (Type/getType class) constructor-method)))

(defmethod emit :default [args] (Util/runtimeException (str "Unknown operator: " (:op args) "\nForm: " args)))
