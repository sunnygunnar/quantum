(ns
  ^{:doc "A very experimental deployment-centered namespace.

          Focuses on Heroku, Git, and Clojars via the command line."
    :attribution "Alex Gunnarson"}
  quantum.network.deploy
  (:require-quantum [:lib])
  (:require 
    [quantum.google.drive.auth :as crawler]
    [org.httpkit.client        :as http]
    [oauth.google              :as oauth.google]
    [oauth.io                  :as oauth.io]
    [oauth.v2                  :as oauth.v2]
    [quantum.auth.core         :as auth]
    [quantum.http.core         :as qhttp])
  (:import quantum.http.core.HTTPLogEntry))

(def heroku-help-center
  "https://devcenter.heroku.com/articles/getting-started-with-clojure")

(def apps        (atom #{"ramsey"}))
(def default-app (atom "ramsey"))


(defn create!
  [^String app-name]
  (thread+ {:id :heroku-write}
    (sh/exec! [:projects app-name] "heroku" "create")))

(defn ^Int count-dynos
  "Count the number of dynos running on the given app."
  ([]
    (count-dynos @default-app))
  ([^String app-name]
    (thread+ {:id :count-dynos}
      (sh/exec! [:projects app-name] "heroku" "ps")
      (-> @sh/processes (get "heroku ps")
          :out last first
          (take-after "web (")
          (take-until "X):")
          str/val))))

(defn scale-to!
  "Scaling the application may require account verification.
   For each application, Heroku provides 750 free dyno-hours."
  {:threaded true}
  ([^Int dynos]
    (scale-to! @default-app dynos))
  ([^String app-name ^Int dynos]
    (thread+ {:id :heroku-write}
      (sh/exec! [:projects app-name]
        "heroku" "ps:scale" (str "web=" dynos)))))

(defn launch-instance!
  {:threaded true}
  ([]
    (launch-instance! @default-app))
  ([^String app-name]
    (when (< (count-dynos) 1)
      (scale-to! app-name 1))))

(defn deploy!
  {:threaded true}
  ([]
    (deploy! @default-app))
  ([^String app-name]
    (thread+ {:id :heroku-git}
      (sh/exec! [:projects app-name] "git" "push"   "heroku" "master")))
  ([^String app-name ^String commit-desc]
    (thread+ {:id :heroku-git}
      (sh/exec! [:projects app-name] "git" "add"    ".")
      (sh/exec! [:projects app-name] "git" "commit" "-am" (str "\"" commit-desc "\""))
      (sh/exec! [:projects app-name] "git" "push"   "heroku" "master"))))

(defn visit
  ([]
    (visit @default-app))
  ([^String app-name]
    (sh/exec! [:projects app-name] "heroku" "open")))

(defn dep-deploy! [^String repo-name]
  (let [^Key thread-id
          (keyword (str "lein-install-" (-> repo-name str/keywordize name)))]
    (thread+ {:id thread-id}
      (sh/exec! [:projects repo-name] "lein" "install"))))

(defn dep-release!
  {:todo "CAN'T USE YET. Lein deploy clojars requires input"}
  [^String repo-name]
  (dep-deploy! repo-name)
  (let [^Key thread-id
          (keyword (str "lein-deploy-clojars-" (-> repo-name str/keywordize name)))]
    (thread+ {:id thread-id}
      (sh/exec! [:projects repo-name] "lein" "deploy" "clojars"))))

(defn logs [^String repo-name]
  (thread+ {:id :heroku-logs}
    (sh/exec! [:projects repo-name] "heroku" "logs")))

(defn logs-streaming [^String repo-name]
  ; requires CTRL-C to end stream
  (thread+ {:id :heroku-logs-streaming} ; asynchronous because it's a log stream
    (sh/exec! [:projects repo-name] "heroku" "logs" "--tail")))

(defn create-proc-file! [^String repo-name ^String jar-name]
  (io/write!
    :path         [:projects repo-name "Procfile"]
    :write-method :print
    :file-type    ""
    :data (str "web: java $JVM_OPTS -cp target/" jar-name ".jar"
               " clojure.main -m " repo-name ".web")))

(defn create-uberjar! [^String repo-name]
  (let [^Key thread-id
          (keyword (str "lein-uberjar-" (-> repo-name str/keywordize name)))]
    (thread+ {:id thread-id}
      (sh/exec! [:projects repo-name] "lein" "uberjar"))))

; (require '[cljs.repl :as repl])
; (require '[cljs.repl.browser :as browser])  ;; require the browser implementation of IJavaScriptEnv
; (require '[cemerick.piggieback])
; (require '[weasel.repl.websocket])

; (defn launch-buggy-cljs-browser
;   ; Forgets vars somehow and doesn't completely evaluate the rest
;   []
;   (def env (browser/repl-env)) ;; create a new environment
;   (repl/repl env))  


; (defn launch-cljs-browser
;   ; IllegalStateException
;   ; Can't change/establish root binding of:
;   ; *cljs-repl-options* with set  clojure.lang.Var.set (Var.java:221)
;   []
;   (cemerick.piggieback/cljs-repl
;     :repl-env
;       (weasel.repl.websocket/repl-env
;         :ip "0.0.0.0" :port 9001)))

; TEST CLJS
; cd /Users/alexandergunnarson/Development/Source\ Code\ Projects/flappy-bird-demo && lein figwheel
; Delete cached folder in /public
; Can't use macros within that same file if your use with cljs...

; Task with cljx-conversion:  lein deploy