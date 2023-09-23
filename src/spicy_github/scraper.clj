(ns spicy-github.scraper
    (:gen-class)
    (:require [clojure.java.io :as io]
              [spicy-github.db :as db]
              [spicy-github.util :refer :all]
              [spicy-github.adapters :as adapters]
              [malli.dev.pretty]
              [clj-http.client :as client]
              [cheshire.core :refer :all]
              [clojure.edn :as edn]
              [hiccup.util]
              [gungnir.model]
              [gungnir.query :as q]
              [clj-time.core :as t]
              [honey.sql.helpers :as h]
              [net.cgrand.xforms :as x]
              [throttler.core :refer [throttle-fn]]))

(defn get-github-token []
    (-> (io/resource "token.edn")
        io/file
        slurp
        edn/read-string
        :github-token))

(def get-url (throttle-fn client/get 5000 :hour))

(def github-token (get-github-token))

(defn get-github-url [url]
    (get-url url {:headers {"Authorization" (str "Bearer " github-token)}}))

(defn paginated-iteration [paginated-url]
    (iteration #(get-github-url %1)
               :kf #(-> %1 :links :next :href)
               :vf #(parse-json (:body %1))
               :initk paginated-url
               :somef :body))

(defn add-url-query [url query]
    (.toString (hiccup.util/url url query)))

(defn parse-then-persist [parser]
    (execute #(-> %
                  parser
                  db/persist-record!)))

(defn get-last-processed-repository []
    (-> (h/where [:< :repository/processed-at (java.sql.Date. (inst-ms (t/yesterday)))])
        (h/order-by :repository/processed-at)
        (h/limit 10)
        (q/all! :repository)))

(defn get-issues []
    (q/all! :issue))

(defn get-issues-url-from-repo-model [repo]
    (-> repo
        :repository/github-json-payload
        parse-json
        :issues_url
        sanitize-github-url))

(defn get-comments-url-from-issue [issue]
    (-> issue
        :issue/github-json-payload
        parse-json
        :comments_url))

(defn persist-repo [repo-url]
    (-> repo-url
        get-github-url
        :body
        parse-json
        adapters/parse-repository
        db/persist-record!))

(def repo-pipeline-xf
    (comp
        (map get-issues-url-from-repo-model)
        (map #(add-url-query % {:state "all"}))             ; Without stating "all" we will only get open issues
        (map paginated-iteration)                           ; Create a paginated iterator over all issues in this repo
        cat                                                 ; Iterate over the issues pagination
        cat                                                 ; Each pagination gives us a list of issues, iterate over them
        (parse-then-persist adapters/parse-user-from-issue) ; Save the user of each issue
        (parse-then-persist adapters/parse-issue)           ; Save the issue
        (map :comments_url)
        (map paginated-iteration)                           ; Create a paginated iterator over all comments in this issue
        cat                                                 ; Iterate over the comment pagination
        cat                                                 ; Each pagination gives us a list of comments, iterate over them
        (parse-then-persist adapters/parse-user-from-comment) ; Save the user of each comment
        (parse-then-persist adapters/parse-comment)))       ; Save the comment

(defn process-repository-models [repo-models]
    (transduce repo-pipeline-xf (constantly nil) repo-models))

(comment
    (def repo (get-last-processed-repository))

    (into [] (x/window 2 + -) (range 16))

    repo

    (def new-repo-url "https://api.github.com/repos/cgrand/xforms")

    (persist-repo new-repo-url)

    (process-repository-models repo)

    (add-url-query "https://api.github.com/repos/dakrone/cheshire/issues" {:state "all"})

    (count (process-repository-models repo))

    (process-repository repo)

    (def it (process-repository repo))

    (count (lazy-concat it))

    (first (into [] cat it))

    (def cl (first (into [] (comp cat (map adapters/parse-issue) (take 1)) it)))

    (db/persist-record! cl)

    (transduce (comp
                   cat
                   (passthrough #(-> %1
                                     adapters/parse-user-from-issue
                                     db/persist-record!))
                   (map adapters/parse-issue)
                   (map db/persist-record!))
               (constantly nil)
               it)

    (transduce (comp
                   cat
                   (passthrough #(println %1))
                   (take 1))
               (constantly nil)
               it)

    (into [] (comp cat
                   (passthrough #(println %1))
                   ) [[1], [2], [3], [4]])

    (eduction)

    (partial)

    (run!)

    (eduction (map inc) [1 2 3])

    (eduction (filter even?) (map inc)
              (range 5))

    (def xf (eduction (filter even?) (map inc) (range 100)))

    (class xf)

    (defn process-with-transducers [files]
        (transduce (comp (mapcat parse-json-file-reducible)
                         (filter valid-entry?)
                         (keep transform-entry-if-relevant)
                         (partition-all 1000)
                         (map save-into-database))
                   (constantly nil)
                   nil
                   files))

    (def issues-url (let [{json-str :repository/github-json-payload} repo
                          json (parse-json json-str)
                          issues-url (-> json
                                         :issues_url
                                         sanitize-github-url)]
                        issues-url))

    (get-github-url issues-url)

    (when-let [repo (not-empty (get-last-processed-repository))]
        (println repo)
        (println "huh"))

    (def comments-url (-> (get-issues)
                          first
                          ))

    comments-url

    (def comments-url "https://api.github.com/repos/devlooped/moq/issues/1374/comments")

    (def events-url "https://api.github.com/repos/devlooped/moq/issues/1374/events")

    (count (parse-json (:body (get-github-url comments-url))))

    (get-github-url "https://api.github.com/users")

    (def comments (get-github-url comments-url))

    comments

    )