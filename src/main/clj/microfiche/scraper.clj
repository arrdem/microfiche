(ns microfiche.scraper
  "Tools for extracting Java documentation from Javadoc HTML."
  {:authors ["Reid \"arrdem\" McKenzie <me@arrdem.com>"]
   :license "https://www.eclipse.org/legal/epl-v10.html"}
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [microfiche :refer [default-options]]
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

;; Scraping inherited information
;;--------------------------------------------------------------------------------------------------
(defn parse-javadoc-inheritance
  "We only extract the name and superclasses from inheritance info."
  [url html]
  (let [supers (map (partial classref url)
                    (html/select html [:ul.inheritance :li :a]))
        name   (first (:content (last (html/select html [:ul.inheritance :li]))))]
    {:supers supers
     :name   name}))

;; Scraping the summary table for inherited structures
;;--------------------------------------------------------------------------------------------------
(def summary-delimeters
  {" =========== FIELD SUMMARY =========== " :fields
   " ======== CONSTRUCTOR SUMMARY ======== " :constructors
   " ========== METHOD SUMMARY =========== " :methods})

(defn- transform-summary-table [xform]
  (fn [opts html]
    (as-> html %
      (parse-table %)
      (mapcat #(map (comp (fn [m] (assoc m :inherited? true))
                          (partial xform opts))
                    (html/select % [:code :a]))
              %))))

(def parse-inherited-methods
  (transform-summary-table methodref))

(def parse-inherited-fields
  (transform-summary-table fieldref))

(defn parse-javadoc-summary [opts html]
  (let [summary-html (->> (html/select html [:div.summary])
                          (mapcat content*)
                          (mapcat content*)
                          (mapcat content*)
                          (remove #{"\n"}))]
    (as-> summary-html %
      (partition-by-comments summary-delimeters %)
      (update % :fields (partial parse-inherited-fields opts))
      (update % :methods (partial parse-inherited-methods opts)))))

;; Scraping the detail table for directly implemented structures
;;--------------------------------------------------------------------------------------------------
(def java-keyword-pattern
  (->> ["boolean"
        "byte"
        "char"
        "int"
        "long"
        "float"
        "double"
        "void"
        "static"
        "final"
        "abstract"
        "public"
        "private"
        "protected"]
       (str/join "|")
       (format "(?:(?:%s)(?=\\s|$))")
       re-pattern))

(def java-tokeize-pattern
  (->> [#"[?(),<>\s]"
        java-keyword-pattern
        #"\w+"]
       (map str)
       (str/join "|")
       re-pattern))

(def detail-delimeters
  {" ============ FIELD DETAIL =========== " :fields
   " ========= CONSTRUCTOR DETAIL ======== " :constructors
   " ============ METHOD DETAIL ========== " :methods})

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

(defn- parse-method-signature-list [opts ^PeekPushBackIterator iter]
  (assert (= "(" (.peek iter)) (.peek iter))
  (.next iter)
  (if (not= ")" (.peek iter))
    (loop [acc []]
      (let [type (recursive-type-parse opts iter)
            name (.next iter)
            acc* (conj acc {:type            ::argument
                            :name            name
                            :type-constraint type})]
        (if (and (.hasNext iter)
                 (= "," (.peek iter)))
          (do (.next iter) ;; discard the ","
              (recur acc*))
          {:type      ::signature
           :arguments acc*})))
    {:type      ::signature
     :arguments []}))

(def java-flags #{"static" "abstract" "final"
                  "public" "private" "protected"})

(defn java-tokenize-html [opts html]
  (->> html
       (mapcat (fn [node]
                 (if (string? node)
                   (re-seq java-tokenize-pattern node)
                   [node])))
       (remove #(and (string? %)
                     (re-matches #"^[\s\n]+$" %)))
       (map #(if (primitive-type? %)
               (->primitive-type %) %))
       (map #(if-not (and (map? %)
                          (= (:tag %) :a))
               %
               (classref opts %)))))

(defn java-parse-access [iter]
  (loop [access    "default"
         static?   false
         abstract? false
         final?    false]
    (case (.peek iter)
      ("final")
      (do (.next iter)
          (recur access static? abstract? true))

      ("abstract")
      (do (.next iter)
          (recur access static? true final?))

      ("static")
      (do (.next iter)
          (recur access true abstract? final?))

      ("public" "private" "protected")
      (recur (.next iter) static? abstract? final?)
      {:type      ::access
       :access    access
       :static?   static?
       :abstract? abstract?})))

(defn parse-method-signature [opts html]
  (let [tokens (java-tokenize-html opts html)
        iter   (->> tokens
                    (.iterator)
                    (PeekPushBackIterator.))
        access (java-parse-access iter)
        rtype  (volatile! nil)
        name   (volatile! nil)
        sig    (volatile! nil)]
    (try
      ;; The next thing should be the return type
      (assert (.hasNext iter))
      (vreset! rtype (recursive-type-parse opts iter))

      ;; The next thing should be a symbol naming the method
      (assert (.hasNext iter))
      (vreset! name (.next iter))

      ;; And the last thing is a parenthesized list of types and names
      ;; constituting the arguments list.
      (assert (.hasNext iter))
      (assert (= "(" (.peek iter)) (.peek iter))
      (vreset! sig (parse-method-signature-list opts iter))

      {:method      @name
       :access      access
       :return-type @rtype
       :signature   @sig}
      (catch AssertionError e
        (throw (ex-info "Assertion failed while parsing method signature!"
                        {:html   html
                         :tokens tokens
                         :access access
                         :rtype  @rtype
                         :name   @name
                         :sign   @sig}
                        e))))))

(defn parse-ctor-signature [opts html]
  (let [tokens (java-tokenize-html opts html)
        iter   (->> tokens
                    (.iterator)
                    (PeekPushBackIterator.))
        access (java-parse-access iter)
        name   (volatile! nil)
        sig    (volatile! nil)]
    ;; The next thing should be a symbol naming the method
    (assert (.hasNext iter))
    (vreset! name (.next iter))

    ;; And the last thing is a parenthesized list of types and names
    ;; constituting the arguments list.
    (assert (.hasNext iter))
    (assert (= "(" (.peek iter)))
    (vreset! sig (parse-method-signature-list opts iter))

    {:method    @name
     :access    access
     :signature @sig}))

(defn parse-method-detail
  "Parse a method detail."
  [opts html]
  (let [[{signature :content}]                        (html/select html [:pre])
        [{doc :content}]                              (html/select html [:div.block])
        {:keys [access return-type method signature]} (parse-method-signature opts signature)]
    (try {:type        ::method
          ;; FIXME (arrdem 2017-12-30):
          ;;   how on earth should this be structured?
          :name        method
          :access      access
          :return-type return-type
          :signature   signature
          :doc         doc}
         (catch Exception e
           (throw (ex-info "Failed to parse method"
                           {:html      html
                            :signature signature
                            :doc       doc}
                           e))))))

(defn parse-field-signature [opts html]
  (try (let [tokens (java-tokenize-html opts html)
             iter   (->> tokens
                         (.iterator)
                         (PeekPushBackIterator.))
             access (java-parse-access iter)
             type   (recursive-type-parse opts iter)
             name   (.next iter)]
         {:access access
          :type   type
          :name   name})
       (catch Exception e
         (throw (ex-info "Failed to parse signature"
                         {:html html}
                         e)))))

(defn parse-field-detail [opts html]
  (try
    (let [[{signature :content}]     (html/select html [:pre])
          [{doc :content}]           (html/select html [:div.block])
          {:keys [access type name]} (parse-field-signature opts signature)]
      {:type            ::field
       :type-constraint type
       :name            name
       :access          access
       :doc             doc})
    (catch Exception e
      (throw (ex-info "Failed to parse field detail"
                      {:html html})))))

(defn parse-ctor-detail [opts html]
  (let [[{signature :content}]          (html/select html [:pre])
        [{doc :content}]                (html/select html [:div.block])
        {:keys [access name signature]} (parse-ctor-signature opts signature)]
    {:type      ::constructor
     :name      name
     :access    access
     :signature signature
     :doc       doc}))

(defn parse-detail-methods [opts html]
  (as-> html %
    (parse-table %)
    (mapv (partial parse-method-detail opts) %)))

(defn parse-detail-fields [opts html]
  (as-> html %
    (parse-table %)
    (mapv (partial parse-field-detail opts) %)))

(defn parse-detail-constructors [opts html]
  (as-> html %
    (parse-table %)
    (mapv (partial parse-ctor-detail opts) %)))

(defn parse-javadoc-details [opts html]
  (let [detail-html (->> (html/select html [:div.details])
                         (mapcat content*)
                         (mapcat content*)
                         (mapcat content*)
                         (remove #{"\n"}))]
    (as-> detail-html %
      (partition-by-comments detail-delimeters %)
      (update % :constructors (partial parse-detail-constructors opts))
      (update % :fields       (partial parse-detail-fields opts))
      (update % :methods      (partial parse-detail-methods opts)))))


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
