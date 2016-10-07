(ns nightlight.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.util.response :refer [redirect]]
            [ring.util.request :refer [body-string]]
            [clojure.spec :as s :refer [fdef]]
            [eval-soup.core :as es]
            [compliment.core :as com])
  (:import [java.io File FilenameFilter]))

(def ^:const max-file-size (* 1024 1024 2))
(def ^:const pref-file ".nightlight.edn")

(defonce web-server (atom nil))

(defn form->serializable [form]
  (if (instance? Exception form)
    [(.getMessage form)]
    (pr-str form)))

(defn read-state []
  (try (slurp pref-file)
    (catch Exception _ (pr-str {:auto-save? true :theme :dark}))))

(defn file-node
  ([^File file]
   (let [pref-state (edn/read-string (read-state))
         selection (:selection pref-state)]
     (assoc (file-node file pref-state)
       :path selection
       :file? (some-> selection io/file .isFile))))
  ([^File file {:keys [expansions selection] :as pref-state}]
   (let [path (.getCanonicalPath file)
         children (->> (reify FilenameFilter
                         (accept [this dir filename]
                           (not (.startsWith filename "."))))
                       (.listFiles file)
                       (mapv #(file-node % pref-state)))
         node {:text (.getName file)
               :path path
               :file (.isFile file)
               :state {:expanded (contains? expansions path)
                       :selected (= selection path)}}]
     (if (seq children)
       (assoc node :nodes children)
       node))))

(defn handler [request]
  (case (:uri request)
    "/" (redirect "/index.html")
    "/eval" {:status 200
             :headers {"Content-Type" "text/plain"}
             :body (->> request
                        body-string
                        edn/read-string
                        es/code->results
                        (mapv form->serializable)
                        pr-str)}
    "/tree" {:status 200
             :headers {"Content-Type" "text/plain"}
             :body (-> "." io/file .getCanonicalFile file-node pr-str)}
    "/read-file" (let [path (body-string request)]
                   (if (-> path io/file .length (< max-file-size))
                     {:status 200
                      :headers {"Content-Type" "text/plain"}
                      :body (slurp path)}
                     {:status 400
                      :headers {}
                      :body "File too large."}))
    "/write-file" (let [{:keys [path content]} (-> request body-string edn/read-string)]
                    (spit path content)
                    {:status 200})
    "/read-state" {:status 200
                   :headers {"Content-Type" "text/plain"}
                   :body (read-state)}
    "/write-state" {:status 200
                    :headers {"Content-Type" "text/plain"}
                    :body (spit pref-file (body-string request))}
    "/completions" {:status 200
                    :headers {"Content-Type" "text/plain"}
                    :body (let [{:keys [ns context-before context-after prefix text]} (->> request body-string edn/read-string)]
                            (->> {:ns ns :context (read-string (str context-before "__prefix__" context-after))}
                                 (com/completions prefix)
                                 (map #(set/rename-keys % {:candidate :text}))
                                 (filter #(not= text (:text %)))
                                 pr-str))}
    nil))

(defn start
  ([opts]
   (start (wrap-resource handler "nightlight-public") opts))
  ([app opts]
   (->> (merge {:port 0 :join? false} opts)
        (run-jetty (wrap-content-type app))
        (reset! web-server))))

(defn dev-start [opts]
  (when-not @web-server
    (.mkdirs (io/file "target" "nightlight-public"))
    (start (wrap-file handler "target/nightlight-public") opts)))

