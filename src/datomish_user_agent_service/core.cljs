(ns ^:figwheel-always datomish-user-agent-service.core
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [datomish.api :as d]
            [datomish.js-sqlite] ;; Otherwise, we won't have the ISQLiteConnectionFactory defns.
            [datomish.pair-chan]
            [datomish-user-agent-service.api :as api]
            [cljs.core.async :as a :refer [chan <! >!]]))

(enable-console-print!)

(.install (nodejs/require "source-map-support"))

(defonce express (nodejs/require "express"))
(defonce http (nodejs/require "http"))
(defonce expressValidator (nodejs/require "express-validator"))
(defonce bodyParser (nodejs/require "body-parser"))

;; define your app data so that it doesn't get over-written on reload
(defonce app-state
  (go-pair
    (js/console.log "Opening Datomish knowledge-base.")
    (let [c (<? (d/<connect "")) ;; In-memory for now.
          _ (<? (d/<transact! c api/tofino-schema))]
      c)))

(defn on-js-reload []
  (js/console.log "on-js-reload"))

;; app gets redefined on reload.
(def app (express))

(def router (. express Router))

;; TODO: figure out how to give JSON 404 errors.
;; (defn- error-handler [err req res next]
;;   (js/console.log "error-handler" err)
;;   (.send (.status res 500)))

;; (.use router error-handler)

(doto app
  (.use (.json bodyParser))
  (.use (expressValidator))
  (.use "/v1" router))

;; routes get redefined on each reload.
(. app (get "/__heartbeat__" 
            (fn [req res] (. res (json (clj->js {}))))))

;; TODO: validate in CLJS.
(defn- auto-caught-route-error [validator method]
  (fn [req res next]
    (go-pair
      (try
        (when validator
          (validator req))
        (let [errors (.validationErrors req)]
          (if errors
            ;; TODO: log.
            (doto res
              (.status 401)
              (.json (clj->js errors)))
            ;; TODO: .catch errors in method?
            (<? (method req res next))))
        (catch js/Error e
          (js/console.log "caught error" e)
          (next e))))))

;; TODO: write a small macro to cut down this boilerplate.
(. router (post "/session/start"
                (auto-caught-route-error
                  (fn [req]
                    (-> req
                        (.checkBody "scope")
                        (.optional)
                        (.isInt))
                    (-> req
                        (.checkBody "ancestor")
                        (.optional)
                        (.isInt))
                    )
                  (fn [req res]
                    (go-pair
                      (let [session (<? (api/<start-session (<? app-state)
                                                            {:ancestor (-> req .-body .-ancestor)
                                                             :scope (-> req .-body .-scope)}))]
                        (. res (json (clj->js {:session session})))))))))

(. router (post "/session/end"
                (auto-caught-route-error
                  (fn [req]
                    (-> req
                        (.checkBody "session")
                        (.notEmpty)
                        (.isInt))
                    )
                  (fn [req res]
                    (go-pair
                      (let [_ (<? (api/<end-session (<? app-state)
                                                    {:session (-> req .-body .-session)}))]
                        (. res (json (clj->js {})))))))))

(. router (post "/visits"
                (auto-caught-route-error
                  (fn [req]
                    (-> req
                        (.checkBody "url")
                        (.notEmpty))
                    (-> req
                        (.checkBody "title")
                        (.optional))
                    (-> req
                        (.checkBody "session")
                        (.notEmpty)
                        (.isInt))
                    )
                  (fn [req res]
                    (go-pair
                      (let [_ (<? (api/<add-visit (<? app-state)
                                                  {:url (-> req .-body .-url)
                                                   :title (-> req .-body .-title)
                                                   :session (-> req .-body .-session)}))]
                        (. res (json (clj->js {})))))))))

(. router (get "/visits"
               (auto-caught-route-error
                 (fn [req]
                   (-> req
                       (.checkQuery "limit")
                       (.notEmpty)
                       (.isInt))
                   )
                 (fn [req res]
                   (go-pair
                     (let [results (<? (api/<visited (d/db (<? app-state)) ;; TODO -- unify on conn over db?
                                                     {:limit (int (-> req .-query .-limit))}))]
                       (. res (json (clj->js {:pages results})))))))))

(. router (post "/stars/:url"
                (auto-caught-route-error
                  (fn [req]
                    (-> req
                        (.checkParams "url")
                        (.notEmpty))
                    (-> req
                        (.checkBody "title")
                        (.optional))
                    (-> req
                        (.checkBody "session")
                        (.notEmpty)
                        (.isInt))
                    )
                  (fn [req res]
                    (go-pair
                      (let [_ (<? (api/<star-page (<? app-state)
                                                  {:url (-> req .-params .-url)
                                                   :title (-> req .-body .-title) ;; TODO: allow no title.
                                                   ;; TODO: coerce session to integer.
                                                   :session (int (-> req .-body .-session))}))]
                        ;; TODO: dispatch bookmark diffs to WS.
                        (. res (json (clj->js {})))))))))

;; (. router (delete "/stars/:url"
;;                   (auto-caught-route-error
;;                     (fn [req]
;;                       (-> req
;;                           (.checkParams "url")
;;                           (.notEmpty))
;;                       (-> req
;;                           (.checkBody "session")
;;                           (.notEmpty)
;;                           (.isInt))
;;                       )
;;                     (fn [req res]
;;                       (go-pair
;;                         (let [_ (<? (api/<star-page (<? app-state)
;;                                                     {:url (-> req .-params .-url)
;;                                                      ;; TODO: coerce session to integer.
;;                                                      :session (-> req .-body .-session)}))]
;;                           ;; TODO: dispatch bookmark diffs to WS.
;;                           (. res (json (clj->js {})))))))))

(. router (get "/stars"
               (auto-caught-route-error
                 (fn [req]
                   )
                 (fn [req res]
                   (go-pair
                     (let [results (<? (api/<starred-pages (d/db (<? app-state))))]
                       (. res (json (clj->js {:stars results})))))))))

(. router (get "/recentStars"
               (auto-caught-route-error
                 (fn [req]
                   (-> req
                       (.checkQuery "limit")
                       (.notEmpty)
                       (.isInt))
                   )
                 (fn [req res]
                   (go-pair
                     (let [results (<? (api/<starred-pages (d/db (<? app-state)) ;; TODO -- unify on conn over db?
                                                           ;; {:limit (int (-> req .-query .-limit))}
                                                           ))]
                       (. res (json (clj->js {:stars results})))))))))

;; (def -main 
;;   (fn []
;;     ;; This is the secret sauce. you want to capture a reference to 
;;     ;; the app function (don't use it directly) this allows it to be redefined on each reload
;;     ;; this allows you to change routes and have them hot loaded as you
;;     ;; code.
;;     (let [port (or (.-PORT (.-env js/process)) 3000)]
;;       (server port
;;               #(js/console.log (str "Server running at http://127.0.0.1:" port "/"))))))

(defn server [port]
  ;; This is the secret sauce. you want to capture a reference to the app function (don't use it
  ;; directly) this allows it to be redefined on each reload this allows you to change routes and
  ;; have them hot loaded as you code.
  (doto (.createServer http #(app %1 %2))
    (.listen port)))

(def -main (partial server 3000))
;; (fn []
;;   ;; This is the secret sauce. you want to capture a reference to the app function (don't use it
;;   ;; directly) this allows it to be redefined on each reload this allows you to change routes and
;;   ;; have them hot loaded as you code.
;;   (doto (.createServer http #(app %1 %2))
;;     (.listen 3000))))

(set! *main-cli-fn* -main) ;; this is required
