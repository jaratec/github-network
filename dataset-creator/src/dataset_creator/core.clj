(ns dataset-creator.core
  (:require [cheshire.core :as json])
  (:require [clj-http.client :as client])
  (:require [clojure.java.io :as io])
  (:require [clojure.java.jdbc :as jdbc])
  (:require [clojure.string :as string]))


;; read pairs (owner,repo) file
(defn read-owners [path-to-file]
  (with-open [rdr (io/reader path-to-file)]
    (set (map #(first (string/split % #",")) (rest (line-seq rdr))))))

(defn read-pairs [path-to-file]
  (with-open [rdr (io/reader path-to-file)]
    (set (map #(string/split % #",") (rest (line-seq rdr))))))

;; JSON functions
(defn read-json [json]
  (do
    (Thread/sleep 2000) ; wait 2 second(s) between calls to avoid github throttling
    (json/parse-string json true)))

;; HTTP query functions
(defn query-github [url]
  (read-json
    (:body
      (try
        (client/get url
          {:basic-auth ["", ""] ; put your github login and password here
           :content-type :json})
        (catch Exception e (println (str (.getMessage e) " : " url)))))))

(defn get-user [login]
  (query-github (str "https://api.github.com/users/" login)))

(defn get-repos [login, type]
  (cond 
    (= type "User") (query-github (str "https://api.github.com/users/" login "/repos?per_page=100"))
    (= type "Organization") (query-github (str "https://api.github.com/orgs/" login "/repos?per_page=100"))
    :else {}))

(defn get-contributors [user_login, repo_name]
  (query-github (str "https://api.github.com/repos/" user_login "/" repo_name "/contributors?per_page=100")))

;; db setup & operations
; sqlite config
(def db-spec
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname "../db/github.db"}) ; provide path to file storing the database

; oracle config - do not forget to add the oracle driver to the lein dependencies (file project.clj)
;(def db-spec
;  {:classname "oracle.jdbc.OracleDriver"
;   :subprotocol "oracle"
;   :subname "thin:@BIRUATDB11:1521:BIRH11" ; jdbc:oracle:thin:@BIRUATDB11:1521:BIRH11
;   :user ""
;   :password ""}) ; provide JDBC URL, user and password. You can use a connection string instead of param map.

(defn insert-user [user]
  (if-not (nil? user)
    (jdbc/with-connection db-spec
      (jdbc/insert-records :users
        (select-keys user [:id :login :name :type])))))

(defn insert-repo [repo]
  (if-not (nil? repo)
    (jdbc/with-connection db-spec
      (jdbc/insert-records :repos
        (hash-map :id (:id repo) :owner_id (:id (:owner repo)) :name (:name repo) :description (:description repo) :language (:language repo))))))

(defn insert-contrib [contributor repo]
  (if-not (or (nil? contributor) (nil? repo))
    (jdbc/with-connection db-spec
      (jdbc/insert-records :contribs
        (hash-map :repo_id repo :user_id contributor)))))

(defn known-user? [login]
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from users where login = ?" login]
      (> (count rows) 0))))

; name is not a unique identifier for a repo, function does not have much use
(defn known-repo? [repo_name]
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from repos where name = ?" repo_name]
      (> (count rows) 0))))

(defn known-repo-for-owner? [repo_name login]
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from repos where name = ? and owner_id = (select id from users where login = ?)" repo_name login]
      (> (count rows) 0))))

(defn select-users []
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from users"]
      (doall rows))))

(defn select-user [login]
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from users where login = ?" login]
      (first rows))))

(defn select-user-with-id [user_id]
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from users where id = ?" user_id]
      (first rows))))

(defn select-users-without-repos []
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from users where id not in (select owner_id from repos)"]
      (doall rows))))

(defn select-repos []
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from repos"]
      (doall rows))))

(defn select-repos-without-users []
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from repos where id not in (select repo_id from contribs)"]
      (doall rows))))

; name is not a unique identifier for a repo, function does not have much use
(defn select-repo [repo_name]
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from repos where name = ?" repo_name]
      (first rows))))

(defn select-repo-with-id [repo_id]
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select * from repos where id = ?" repo_id]
      (first rows))))

(defn select-contribs []
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select r.name, u.login from repos r, users u, contribs c where r.id = c.repo_id and c.user_id = u.id order by r.name, u.login"]
      (doall rows))))

(defn select-repo-links []
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select distinct r1.id as source, r2.id as target from repos r1, repos r2, contribs c1, contribs c2 where r1.id = c1.repo_id and c1.user_id = c2.user_id and c2.repo_id = r2.id and c1.repo_id <> c2.repo_id"]
      (doall rows))))

(defn select-user-links []
  (jdbc/with-connection db-spec
    (jdbc/with-query-results rows ["select distinct u1.id as source, u2.id as target from users u1, users u2, contribs c1, contribs c2 where u1.id = c1.user_id and c1.repo_id = c2.repo_id and c2.user_id = u2.id and c1.user_id <> c2.user_id"]
      (doall rows))))

;; get data from github and save to database
(defn fetch-users [owners-file]
  (let [users (read-owners owners-file)]
    (map #(insert-user (get-user %)) users)))

(defn fetch-missing-users [owners-file]
  (let [users (filter #(not (known-user? %)) (read-owners owners-file))]
    (map #(insert-user (get-user %)) users)))

(defn fetch-repos [lang]
  (let [repos (filter #(= lang (:language %)) (mapcat #(get-repos (:login %) (:type %)) (select-users)))]
    (map insert-repo repos)))

(defn fetch-missing-repos [lang] 
  (let [repos (filter #(= lang (:language %)) (mapcat #(get-repos (:login %) (:type %)) (select-users-without-repos)))]
    (map insert-repo repos)))

;(defn fetch-contributor [repo_name contrib]
;  (let [login (:login contrib)
;        contrib_id (:id contrib)
;        repo_id (:id (select-repo repo_name))]
;    (cond
;      (known-user? login) (insert-contrib contrib_id repo_id)
;      :else 
;      (do
;        (insert-user (get-user login))
;        (insert-contrib contrib_id repo_id)))))

;(defn process-group [repo_name contributors]
;  (map #(fetch-contributor repo_name %) contributors))

;(defn fetch-contributors [owners-file]
;  (let [pairs (read-pairs owners-file)
;        groups (map #(hash-map :repo (last %) :contrib (get-contributors (first %) (last %))) pairs)]
;    (map #(process-group (:repo %) (:contrib %)) groups)))

(defn fetch-contributor [repo contrib]
  (let [login (:login contrib)
        contrib_id (:id contrib)
        repo_id (:id repo)]
    (cond
      (known-user? login) (insert-contrib contrib_id repo_id)
      :else 
      (do
        (insert-user (get-user login))
        (insert-contrib contrib_id repo_id)))))

(defn fetch-contributors-for-repo [repo user]
  (let [repo_name (:name repo)
        login (:login user)]
    (map #(fetch-contributor repo %) (get-contributors login repo_name))))

(defn fetch-contributors []
  (let [repos (select-repos)]
     (map #(fetch-contributors-for-repo % (select-user-with-id (:owner_id %))) repos)))

(defn fetch-missing-contributors []
  (let [repos (select-repos-without-users)]
     (map #(fetch-contributors-for-repo % (select-user-with-id (:owner_id %))) repos)))

;; clojure - although a small community by github standards, extracting the data from github takes a long time
(defn clojure-users []
  (fetch-users "../githubarchive/repos-clojure.csv")) ; path to csv file

(defn clojure-repos []
  (fetch-repos "Clojure"))

;(defn clojure-contributors []
;  (fetch-contributors "../githubarchive/repos-clojure.csv"))

;; racket - another (small) lisp community
(defn racket-users []
  (fetch-users "../githubarchive/repos-racket.csv"))

(defn racket-repos []
  (fetch-repos "Racket"))

;(defn racket-contributors []
;  (fetch-contributors "../githubarchive/repos-racket.csv"))

;; mirah - tiny community (test only, only 2 components in user graph, no associations in project graph)
(defn mirah-users []
  (fetch-users "../githubarchive/repos-mirah.csv"))

(defn mirah-repos []
  (fetch-repos "Mirah"))

;(defn mirah-contributors []
;  (fetch-contributors "../githubarchive/repos-mirah.csv"))

;; fantom - tiny (nano) community (no links - test only)
(defn fantom-users []
  (fetch-users "../githubarchive/repos-fantom.csv"))

(defn fantom-repos []
  (fetch-repos "Fantom"))

;(defn fantom-contributors []
;  (fetch-contributors "../githubarchive/repos-fantom.csv"))

;; generate gml files
(def line-separator
  (System/getProperty "line.separator"))

(defn indent [n]
  (apply str (repeat n " ")))

(defn gen-content-line [k v]
  (apply str (indent 4) k " " v))

(defn gen-user-node [user]
  (list
    (gen-content-line "id" (str (:id user)))
    (gen-content-line "label" (str "\"" (:login user) "\""))
    (gen-content-line "login" (str "\"" (:login user) "\""))
    (gen-content-line "name" (str "\"" (:name user) "\""))
    (gen-content-line "type" (str "\"" (:type user) "\""))))

(defn gen-repo-node [repo]
  (list
    (gen-content-line "id" (str (:id repo)))
    (gen-content-line "label" (str "\"" (:name repo) "\""))
    (gen-content-line "name" (str "\"" (:name repo) "\""))
    (gen-content-line "language" (str "\"" (:language repo) "\""))))

(defn gen-node [f node]
  (let [tag (str (indent 2) "node")
        begin (str (indent 2) "[")
        end (str (indent 2) "]")
        content (f node) ; f is a function gen-...-node
        all (flatten (list tag begin content end))]
    (string/join line-separator all)))

(defn gen-edge [edge]
  (let [tag (str (indent 2) "edge")
        begin (str (indent 2) "[")
        end (str (indent 2) "]")
        source (gen-content-line "source " (str (:source edge)))
        target (gen-content-line "target " (str (:target edge)))
        all (list tag begin source target end)]
    (string/join line-separator all)))

(defn gen-nodes [f nodes]
  (string/join line-separator (map #(gen-node f %) nodes)))

(defn gen-edges [edges]
  (string/join line-separator (map gen-edge edges)))

(defn gen-gml [comment f nodes edges]
  (let [tag "graph"
        begin "["
        end "]"
        com (str (indent 2) "comment " "\"" comment "\"")
        dir (str (indent 2) "directed 0")
        ns (gen-nodes f nodes)
        es (gen-edges edges)
        all (flatten (list tag begin com dir ns es end))]
    (string/join line-separator all)))

(defn eliminate-duplicates [rows]
  (let [sorted_set (set (map #(sort (list (:source %) (:target %))) rows))]
    (map #(hash-map :source (first %) :target (last %)) sorted_set)))

(defn gen-users-gml []
  (gen-gml "Users Network" gen-user-node (select-users) (eliminate-duplicates (select-user-links))))

(defn gen-repos-gml []
  (gen-gml "Repos Network" gen-repo-node (select-repos) (eliminate-duplicates (select-repo-links))))

(defn write-file [file-name data]
  (with-open [w (io/writer file-name)]
    (.write w data)))

(defn gen-users-file []
  (write-file "users.gml" (gen-users-gml)))

(defn gen-repos-file []
  (write-file "repos.gml" (gen-repos-gml)))

;; test data
(def octocat
  (slurp "../test/user-octocat.json"))

(def repos-octocat
  (slurp "../test/repos-octocat.json"))

(def repo-contributors
  (slurp "../test/contrib.json"))

