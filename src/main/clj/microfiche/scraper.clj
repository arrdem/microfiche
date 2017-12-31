(ns microfiche.scraper
  "Tools for extracting Java documentation from Javadoc HTML."
  {:authors ["Reid \"arrdem\" McKenzie <me@arrdem.com>"]
   :license "https://www.eclipse.org/legal/epl-v10.html"}
  (:require [clojure.string :as str]
            [clojure.java.browse :refer [browse-url]]
            [clojure.java.io :as io]
            [clojure.zip :as z]
            [microfiche :refer :all] ;; FIXME
            [detritus.update :refer [map-vals]]
            [net.cgrand.enlive-html :as html])
  (:import [java.net URL URLConnection]
           [java.io File FileInputStream InputStream]
           [me.arrdem PeekPushBackIterator]))

(defn ^URLConnection set-properties [^URLConnection req properties]
  (doseq [[k v] properties]
    (.setRequestProperty req ^String k ^String v))
  req)

(defn fetch-url*
  [properties url]
  (let [connection (-> (as-url url)
                       .openConnection
                       (set-properties properties)
                       (doto (.setUseCaches true)))]
    {:type    ::connection
     :code    (.getResponseCode connection)
     :headers (into {} (.getHeaderFields connection))
     :stream  (.getInputStream connection)}))

(defn fetch-url
  "Implementation detail.

  Attempt to fetch a response from a URL as a simple `InputStream`,
  following redirects with a limit."
  ([url]
   (fetch-url {:redirect-limit 10
               :redirect-count 0
               :original-url   url}
              ;; FIXME (arrdem 2017-12-29):
              ;;   any other voodoo need to go here?
              {"User-Agent" "Mozilla/5.0"}
              url))
  ([{:keys [redirect-limit redirect-count] :as state} properties url]
   (if-not (>= redirect-count redirect-limit)
     (let [{:keys [stream code headers] :as resp} (fetch-url* properties url)]
       (if (<= 300 code 399)
         (if-let [url* (first (get headers "Location"))]
           (recur (update state :redirect-count inc) properties url*))
         (if (<= 200 code 299)
           stream
           {:type    ::error
            :headers headers
            :code    code
            :msg     (slurp stream)})))
     {:type ::error
      :msg  "Exhausted the redirect limit!"
      :url  (:original-url state)})))

(defn without-ref [url]
  (str/replace (.toString url) #"#[^?]*" ""))

(defn fqc->package+name [%]
  (let [idx (.lastIndexOf % ".")]
    [(.substring % 0 idx)
     (.substring % (inc idx) (count %))]))

(defn url->package+name [{:keys [root-url]} url]
  (let [fqc (as-> url %
              (.replace (.toString %) (.toString root-url) "")
              (.replace % ".html" "")
              (.replace % "/" "."))]
    (fqc->package+name fqc)))

(defn classref [{:keys [root-url target-url] :as opts}
                {:keys [attrs content] :as a}]
  (when (-> attrs :href)
    (let [url            (URL. (as-url target-url) (:href attrs))
          [package name] (url->package+name opts url)]
      {:type       :java/type
       :name       name
       :package    package
       :primitive? false
       :url        url})))

(defn methodref [{:keys [root-url target-url] :as opts}
                 {:keys [attrs content] :as a}]
  (when (-> attrs :href)
    {:type  :java/method
     :class (classref opts (update-in a [:attrs :href] without-ref))
     ;; FIXME (arrdem 2017-12-30):
     ;;   can we extract the name from the URL?
     :name  (first content)
     :url   (URL. (as-url target-url) (:href attrs))}))

(defn fieldref [{:keys [root-url target-url] :as opts}
                {:keys [attrs content] :as a}]
  (when (-> attrs :href)
    {:type  :java/field
     :class (classref opts (update-in a [:attrs :href] without-ref))
     ;; FIXME (arrdem 2017-12-30):
     ;;   can we extract the name from the URL?
     :name  (first content)
     :url   (URL. (as-url target-url) (:href attrs))}))

(defn ctorref [{:keys [root-url target-url] :as opts}
               {:keys [attrs content] :as a}]
  (when (-> attrs :href)
    {:type  :java/constructor
     :class (classref opts (update-in a [:attrs :href] without-ref))
     ;; FIXME (arrdem 2017-12-30):
     ;;   can we extract the name from the URL?
     :name  (first content)
     :url   (URL. (as-url target-url) (:href attrs))}))

(defn partition-by-comments [delimeters content]
  (loop [current                    nil
         acc                        {}
         [c & content* :as content] content]
    (if-not content
      acc
      (if (= :comment (:type c))
        (recur (get delimeters (:data c)) acc content*)
        (recur current (update acc current (fnil conj []) c) content*)))))

(defn content* [node]
  (if (map? node)
    (:content node)
    [node]))

(def primitive-type?
  #{"boolean" "char" "byte" "int" "long" "float" "double" "void"})

(defn ->primitive-type [name]
  {:pre [(primitive-type? name)]}
  {:type       :java/type
   :primitive? true
   :name       name})

(defn parse-table [html]
  (as-> html %
    ;; FIXME (arrdem 2017-12-30):
    ;;   Can I kill this forward-looking bit? I think I can...
    (html/select % [:ul.blockList :li.blockList :> #{:a :h3 :code :p :span :pre :div}])
    (drop 2 %)
    (partition 3 %)))

(load "scrape_inheritance")
(load "scrape_summary")
(load "scrape_detail")

(defn scrape-javadoc [{:keys [target-url] :as opts} html]
  (let [{:keys [name supers]}       (parse-javadoc-inheritance opts html)
        {inherited-methods :methods
         inherited-fields  :fields} (parse-javadoc-summary opts html)
        {constructors     :constructors
         instance-methods :methods
         instance-fields  :fields}  (parse-javadoc-details opts html)]
    {:type         ::javadoc
     :url          target-url
     :name         name
     :parents      supers
     :constructors constructors
     :methods      (concat inherited-methods instance-methods)
     :fields       (concat inherited-fields instance-fields)}))

(defn scrape-javadoc-stream [opts ^InputStream stream]
  (scrape-javadoc opts (html/html-resource stream)))

(defn scrape-javadoc-for
  "Attempts to fetch the Javadocs for the given object or class.
  Returns a buffer of HTML as a string.

  If a `:local-roots` mapping matches, but the corresponding `.html`
  file does not exist, falls back to the `:remote-roots` mapping if any.

  If no options are provided, uses `#'default-options`.

  Programmatic access should pass options explicitly rather than rely
  on this behavior."
  ([class-or-object]
   (scrape-javadoc-for default-options class-or-object))
  ([options class-or-object]
   (let [^Class c     (if (instance? Class class-or-object)
                        class-or-object
                        (class class-or-object))
         package-name (str (.getName (.getPackage c)) ".")
         class-name   (.getName c)]
     (or (if-let [local-package-url (javadoc-url-for-package* (:local-roots options) package-name)]
           (let [javadoc-url (javadoc-url-for-class local-package-url class-name)
                 file        (io/file javadoc-url)]
             (when (.exists file)
               (scrape-javadoc-stream {:target-url javadoc-url
                                       :root-url   local-package-url}
                                      (FileInputStream. file)))))

         (if-let [remote-package-url (javadoc-url-for-package* (:remote-roots options) package-name)]
           (let [javadoc-url (javadoc-url-for-class remote-package-url class-name)]
             ;; FIXME (arrdem 2017-12-29):
             ;;    Try to hit in a cache first for gods sake
             (scrape-javadoc-stream {:target-url javadoc-url
                                     :root-url   remote-package-url}
                                    (fetch-url javadoc-url))))

         {:type    ::error
          :message "Could not find Javadoc for package"
          :package package-name
          :class   class-name
          :options options}))))
