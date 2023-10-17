(ns spendtracker.app
  (:require-macros
   [cljs.core.async.macros :refer [go]])
  (:require
   [cljs-http.client :as http]
   [cljs.core.async :refer [<!]]
   [clojure.string :as string]
   [reagent.core :as r]
   [reagent.dom :as rdom]))

(defonce state (r/atom {:categories ["food"
                                     "foraging"]
                        :cat-tags-input ""
                        :transactions [{:date "1/2/23"
                                        :amount 123
                                        :cat-tags "cat tag1 tag2"
                                        :notes "yikes a 68"}]}))

(defn append-to-input-value [input-id appendage]
  (-> js/document
      (.getElementById input-id)
      (clj->js {:value appendage})))

(append-to-input-value "cat-tags-input" "goose")

(defn matching-cat-tags []
  [:ul
   (for [c (:categories @state)
         :let [input (:cat-tags-input @state)
               input-parts (string/split input #" ")
               input-without-last (drop-last input-parts)
               newest-input (last input-parts)]
         :when (string/starts-with? c newest-input)]
     ^{:key c} [:li {:on-click
                     #(swap! state
                             assoc :cat-tags-input
                             (as-> [input-without-last c] %
                               (filter (fn [s] (not (string/blank? s))) %)
                               (flatten %)
                               (string/join " " %)
                               (str % " ")))}
                c])])

(defn transaction-input []
  [:form {:action "/transaction" :method "post"}
   [:label {:for "amount"} "Amount: "]
   [:input {:type "number" :name "amount"}]
   [:br]
   [:label {:for "category-tags"} "Category Tags: "]
   [:input#cat-tags-input {:type "text" :name "category-tags"
                           :value (:cat-tags-input @state)
                           :on-change #(swap! state assoc
                                              :cat-tags-input
                                              (-> % .-target .-value))}]
   [:br]
   (matching-cat-tags)
   [:input {:type "submit" :value "Add"}]])

(defn overview-stats []
  (let [spent-today (->> @state
                         :transactions
                         (filter (fn [_] true)) ; was today
                         (map :amount)
                         (apply +))]
    [:div#overview-stats
     [:span "Spent today: $" spent-today]]))

(defn transactions-list []
  (let [cols ["date" "amount" "cat-tags" "notes"]]
  [:table#transactions-list
   [:tr (for [col cols] [:th col])]
   (for [txn (sort-by :date (:transactions @state))
         :let [kwd-fns (map keyword cols)
               values (map #(% txn) kwd-fns)]]
     [:tr (for [v values]
            [:td v])])]))

(defn app []
  [:div
   (transaction-input)
   [:hr]
   (overview-stats)
   [:hr]
   (transactions-list)])

(defn ^:export init []
  (rdom/render [app] (js/document.getElementById "root")))
(init)

(comment 
  (require '[shadow.cljs.devtools.api :as shadow])
  (shadow/watch :frontend)
  (shadow/repl :frontend))
