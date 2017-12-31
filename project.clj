(defproject me.arrdem/microfiche "_"
  :description "Microfiche - it's ancient but we have a lot if it."
  :url "https://github.com/arrdem/microfiche"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths      ["src/main/clj"
                      "src/main/cljc"]
  :java-source-paths ["src/main/jvm"]
  :resource-paths    ["src/main/resources"]

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [me.arrdem/detritus "0.3.2"]
                 [enlive "1.1.6"]]

  :profiles
  {:test {:test-paths     ["src/test/clj"
                           "src/test/cljc"]
          :resource-paths ["src/test/resources"]}}

  :plugins [[me.arrdem/lein-git-version "2.0.3"]]

  :git-version
  {:status-to-version
   (fn [{:keys [tag version ahead ahead? dirty?] :as git}]
     (if (and tag (not ahead?) (not dirty?))
       tag
       (str tag
            (when ahead? (str "." ahead))
            (when dirty? "-SNAPSHOT"))))})
