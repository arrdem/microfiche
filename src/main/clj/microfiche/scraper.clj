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
    (.setRequestProperty req "User-Agent" "Mozilla/5.0"))
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

(defn parse-javadoc-inheritance [url html]
  (let [supers (map (partial classref url)
                    (html/select html [:ul.inheritance :li :a]))
        name   (first (:content (last (html/select html [:ul.inheritance :li]))))]
    {:supers supers
     :name   name}))

(def summary-delimeters
  {" =========== FIELD SUMMARY =========== " :fields
   " ======== CONSTRUCTOR SUMMARY ======== " :constructors
   " ========== METHOD SUMMARY =========== " :methods})

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

(defn drop-ul-li [node]
  (as-> node %
    (if (= (:tag %) :ul) (:content %))
    (remove #{"\n"} %)
    (first %)
    (if (= (:tag %) :li) (:content %))
    (remove #{"\n"} %)))

(defn parse-inherited-fields [opts html]
  "FIXME")

(defn parse-inherited-constructors [opts html]
  "FIXME")

(def primitive-type?
  #{"boolean" "char" "byte" "int" "long" "float" "double" "void"})

(defn ->primitive-type [name]
  {:pre [(primitive-type? name)]}
  {:type       :java/type
   :primitive? true
   :name       name})

(defn- recursive-type-parse [opts ^PeekPushBackIterator iter]
  (let [generic (.next iter)]
    (if (and (.hasNext iter)
             (= "<" (.peek iter)))
      (do (.next iter) ;; For side-effects
          (loop [acc []]
            (let [type (recursive-type-parse opts iter)]
              (if (= ">" (.next iter))
                {:type       :java/generic
                 :class      generic
                 :parameters (conj acc type)}
                (recur (conj acc type))))))
      generic)))

(defn parse-type [opts html]
  (let [tokens (->> html
                    (mapcat (fn [node]
                              (if (string? node)
                                (re-seq #"[,<>]|[\s\n]+|boolean|byte|char|int|long|float|double|void" node)
                                [node])))
                    (remove #(and (string? %)
                                  (re-matches #"^[\s\n]+$" %)))
                    (map #(if (primitive-type? %)
                            (->primitive-type %) %))
                    (map #(if-not (and (map? %)
                                       (= (:tag %) :a))
                            %
                            (classref opts %))))]
    (try
      (if tokens
        (recursive-type-parse opts (PeekPushBackIterator. (.iterator tokens)))
        (->primitive-type "void"))
      (catch Exception e
        (throw (ex-info "Unable to parse signature of `:tokens`."
                        {:tokens tokens}
                        e))))))

(defn- parse-method-signature-list [opts ^PeekPushBackIterator iter]
  (if (.hasNext iter)
    (loop [acc []]
      (let [type (recursive-type-parse opts iter)
            acc* (conj acc type)]
        (if (and (.hasNext iter)
                 (= "," (.peek iter)))
          (do (.next iter) ;; discard the ","
              (recur acc*))
          acc*)))
    (->primitive-type "void")))

(defn parse-method-signature [opts html]
  (->> html
       (mapcat (fn [node]
                 (if (string? node)
                   (re-seq #"[,<>\s]|boolean|byte|char|int|long|float|double|void" node)
                   [node])))
       (remove #(and (string? %)
                     (re-matches #"^[\s\n]+$" %)))
       (map #(if-not (and (map? %)
                          (= (:tag %) :a))
               %
               (classref opts %)))
       (.iterator)
       (PeekPushBackIterator.)
       (parse-method-signature-list opts)))

(defn parse-method-detail
  "Parse a method detail."
  [opts html]
  (let [[type description]   (->> (html/select html [:td])
                                  (map :content))
        type                 (-> type first :content)
        [method & signature] (-> description
                                 (html/select [:code])
                                 first
                                 :content)
        method               (-> method :content first)
        docs                 (html/select description [:div.block])]
    (try {:type        ::method
          :method      (methodref opts method)
          :return-type (parse-type opts type)
          :signature   (parse-method-signature opts signature)
          :docs        docs}
         (catch Exception e
           (throw (ex-info "Failed to parse method"
                           {:html      html
                            :signature signature
                            :docs      docs}
                           e))))))

(defn parse-inherited-methods
  "Parse the \"methods inherited from\" sections of the overview.

  This particular information is not available elsewhere."
  [opts html]
  (as-> html %
    (html/select % [:ul.blockList :li.blockList :> #{:a :h3 :code}])
    (drop 2 %)
    (partition 3 %)
    (mapcat #(map (comp (fn [m] (assoc m :inherited? true))
                        (partial methodref opts))
                  (html/select % [:code :a]))
            %)))

(defn parse-javadoc-summary [opts html]
  (let [summary-html (->> (html/select html [:div.summary])
                          (mapcat content*)
                          (mapcat content*)
                          (mapcat content*)
                          (remove #{"\n"}))]
    (as-> summary-html %
      (partition-by-comments summary-delimeters %)
      (map-vals % (partial mapcat drop-ul-li))
      #_(update % :fields (partial parse-inherited-fields opts))
      #_(update % :constructors (paprtial parse-inherited-constructors opts))
      (update % :methods (partial parse-inherited-methods opts)))))

(defn parse-javadoc-details [opts html]
  (let [details-html (html/select html [:div.details])]))

(defn parse-javadoc [{:keys [target-url] :as opts} html]
  (let [{:keys [name supers]}             (parse-javadoc-inheritance opts html)
        {inherited-methods :methods
         inherited-fields  :fields
         inherited-ctors   :constructors} (parse-javadoc-summary opts html)]
    {:type         ::javadoc
     :url          target-url
     :name         name
     :supers       supers
     :constructors inherited-ctors
     :methods      inherited-methods
     :fields       inherited-fields}))

(defn parse-javadoc-stream [opts ^InputStream stream]
  (parse-javadoc opts (html/html-resource stream)))

(defn fetch-javadoc-for
  "Attempts to fetch the Javadocs for the given object or class.
  Returns a buffer of HTML as a string.

  If a `:local-roots` mapping matches, but the corresponding `.html`
  file does not exist, falls back to the `:remote-roots` mapping if any.

  If no options are provided, uses `#'*user-options*` which defaults
  to `#'default-options` unless modified or bound by the user.

  Programmatic access should pass options explicitly rather than rely
  on this behavior."
  ([class-or-object]
   (fetch-javadoc-for @*user-options* class-or-object))
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
               (parse-javadoc-stream {:target-url javadoc-url
                                      :root-url   local-package-url}
                                     (FileInputStream. file)))))

         (if-let [remote-package-url (javadoc-url-for-package* (:remote-roots options) package-name)]
           (let [javadoc-url (javadoc-url-for-class remote-package-url class-name)]
             ;; FIXME (arrdem 2017-12-29):
             ;;    Try to hit in a cache first for gods sake
             (parse-javadoc-stream {:target-url javadoc-url
                                    :root-url   remote-package-url}
                                   (fetch-url javadoc-url))))

         {:type    ::error
          :message "Could not find Javadoc for package"
          :package package-name
          :class   class-name
          :options options}))))
