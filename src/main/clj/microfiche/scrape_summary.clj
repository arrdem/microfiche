;; This code is part of the scraper namespace
;;
;; Lifted out for brevity
(in-ns 'microfiche.scraper)

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
