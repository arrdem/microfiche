(in-ns 'microfiche.scraper)

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
                   (re-seq #"[?(),<>\s]|(?:(?:boolean|byte|char|int|long|float|double|void|static|final|abstract|public|private|protected)(?=\s|$))|\w+" node)
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
