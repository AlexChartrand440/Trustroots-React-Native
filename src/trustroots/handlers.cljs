(ns trustroots.handlers
  (:require
    [clojure.walk :refer [keywordize-keys]]
    [re-frame.core :refer [register-handler after dispatch]]
    [schema.core :as   s :include-macros true]
    [trustroots.domain.main :as main :refer [app-db schema]]
    [trustroots.helpers :refer [log info debug]]
    [trustroots.db :as db]
    [trustroots.domain.auth :as auth]
    [trustroots.api :as api]
    ))

;; -- Middleware ------------------------------------------------------------
;; See https://github.com/Day8/re-frame/wiki/Using-Handler-Middleware

(defn check-and-throw
  "throw an exception if db doesn't match the schema."
  [a-schema db]
    (when-let [problems (s/check a-schema db)]
      (info db)
      (info problems)
      (throw (js/Error. (str "Schema check failed: " problems)))))

(def validate-schema-mw
  (if goog.DEBUG
    (after (partial check-and-throw schema))
    []))

;; Helpers
;; ------------------------------------------------------------------------

(defn register-handler-for [event-name handler-fn]
  "Simplify register handler calls by automatically registring middleware
   and by droping event name from event arguments"
  (register-handler
    event-name
    validate-schema-mw
    (fn [db evt]
      (debug (str "Dispatch " event-name))
      (apply handler-fn (concat [db] (rest evt))))))

;; Generic handlers
;; -------------------------------------------------------------

(register-handler-for
  :initialize-db
  (fn [_ _]
    (info app-db)
    app-db))


(register-handler-for
 :set-service
 (fn [db service value]
   (assoc-in db [:services service] value)))


;; Navigation handlers
;; -------------------------------------------------------------

(register-handler-for
  :set-page
  (fn [db value]
    (assoc-in db [:page] value)))

;; DB handlers
;; -------------------------------------------------------------

(register-handler-for
  :set-db
  (fn [_ new-state] new-state))

(register-handler-for
  :load-db
  (fn [db _]
    (db/load #(dispatch :set-db %1))))

(register-handler-for
  :save-db
  (fn [db _]
    (db/save! db)
    db))

;; db handlers
;; -------------------------------------------------------------

(register-handler-for
  :logout
  (fn [db _]
    (auth/set-user! db nil)))

(register-handler-for
  :login
  (fn [db user-pwd]
    (let [sign-in api/signin]
      (sign-in :user {:username (:user user-pwd) :password (:pwd user-pwd)}
               :on-success (fn [user] (dispatch [:auth-success user] ))
               :on-error
               #(condp = (:type %)
                   :invalid-credentials (dispatch [:auth-fail])
                   :network-error (dispatch [:check-off-line])
                   (dispatch [:unknown-error])))

      (-> db
          (auth/set-in-progress! true)
          (auth/set-user!        nil)
          (auth/set-error!       nil)))))

(register-handler-for
  :set-off-line
  main/set-offline!)


(register-handler-for
  :auth-fail
  (fn [db error]
    (-> db
        (auth/set-in-progress! false)
        (auth/set-user!        nil)
        (auth/set-error!       "Authentication failed"))))


(register-handler-for
  :auth-success
  (fn [db user]
    (dispatch [:save-db])
    (when (= (:page db) "login")
      (dispatch [:set-page "main"]))
    (-> db
        (auth/set-in-progress! false)
        (auth/set-user!        user)
        (auth/set-error!       nil))))

(def react-native (js/require "react-native"))

;; Hardware related event listeners
;; ----------------------------------

(register-handler-for
 :initialize-hardware
 (fn [db _]
   (-> react-native
       (.-NetInfo)
       (.-isConnected)
       (.fetch)
       (.done #(dispatch [:set-off-line (not %)])))
   db))
