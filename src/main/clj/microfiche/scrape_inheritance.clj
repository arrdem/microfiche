(in-ns 'microfiche.scraper)

(defn parse-inherited-fields [opts html]
  "FIXME")

(defn parse-inherited-constructors [opts html]
  "FIXME")

(defn parse-javadoc-inheritance [url html]
  (let [supers (map (partial classref url)
                    (html/select html [:ul.inheritance :li :a]))
        name   (first (:content (last (html/select html [:ul.inheritance :li]))))]
    {:supers supers
     :name   name}))
