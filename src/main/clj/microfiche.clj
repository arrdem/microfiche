(ns microfiche
  "Tools for locating and extracting Java documentation from Javadocs.

  This namespace is HEAVILY inspired by `clojure.java.javadoc`, which
  has a particular and dated API unsuited to composition or
  programmatic access."
  {:authors ["Reid \"arrdem\" McKenzie <me@arrdem.com>"
             "Christophe Grand"
             "Stuart Sierra"]
   :license "https://www.eclipse.org/legal/epl-v10.html"}
  (:require [clojure.string :as str]
            [clojure.java.browse :refer [browse-url]]
            [detritus.string :refer [indicies-of prefixes-by-including]])
  (:import [java.net URL]))

(defn get-classpath
  "Return the system classpath as a seq of path strings."
  []
  (str/split (System/getProperty "java.class.path") #":"))

(defn java-version->javadoc-url
  "Maps a JDK version eg. \"1.2\", \"1.9\" etc. to a the Java SE documentation on docs.oracle.com.

  Assumes that Oracle can't and won't change the docs location because compatibility rues all.
  Assumes that we'll never see a Java 2.0, and so only the minor version matters."
  [java-version]
  (let [[major minor] (str/split java-version #"\.")]
    (format "http://docs.oracle.com/javase/%s/docs/api/" minor)))

(defn java-version->jdk-roots
  "Maps a JDK version to an options map providing remote roots for the JDK distributed classes."
  [jdk-version]
  (let [jdk-url (java-version->javadoc-url jdk-version)]
    {:type         ::options
     :remote-roots {"java."          jdk-url
                    "com.oracle."    jdk-url
                    "com.sun."       jdk-url
                    "sun."           jdk-url
                    "javax."         jdk-url
                    "org.ietf.jgss." jdk-url
                    "org.omg."       jdk-url
                    "org.w3c.dom."   jdk-url
                    "org.xml.sax."   jdk-url}}))

(def empty-options
  {:type         ::options
   :remote-roots {}
   :local-roots  {}})

(def merge-options
  "Function for merging options maps together."
  (partial merge-with
           #(if (and (map? %1) (map? %2))
              (merge %1 %2) %2)))

(def default-options
  "The default options map used for locating Javadocs.

  `:javadoc-roots` must be a map from package prefix (ending in \".\") to a URI or URL."
  (let [jdk-version (System/getProperty "java.specification.version")]
    (merge-options empty-options
                   (java-version->jdk-roots jdk-version))))

(defn as-url [str-or-url]
  (if (string? str-or-url)
    (URL. str-or-url)
    (if (instance? URL str-or-url)
      str-or-url)))

(defn javadoc-url-for-package*
  "Implementation detail.

  Searches a roots map for a URL for the given class name."
  [roots package-name]
  {:pre [(every? #(.endsWith ^String % ".") (keys roots))]}
  (loop [[pfx & prefixes] (prefixes-by-including package-name ".")]
    (or (some-> (get roots pfx) as-url)
        (if prefixes
          (recur prefixes)))))

(defn javadoc-url-for-package
  "Try to locate a root URL for Javadocs including the given package.

  Searches first the configured `:local-roots` mapping and then the
  `:remote-roots` for a mapping of a prefix of the given
  `package-name` to a URL.

  `package-name` and all package prefix in both `:local-roots` and
  `:remote-roots` must end with \".\""
  [{:keys [local-roots remote-roots] :as options} package-name]
  {:pre [(.endsWith package-name ".")]}
  (or (javadoc-url-for-package* local-roots package-name)
      (javadoc-url-for-package* remote-roots package-name)))

(defn javadoc-url-for-class
  "Returns a full Javadoc URL for a given fully qualified `class-name`
  Eg. \"com.foo.Bar\" and a URL corresponding to a Javadoc root
  expected to contain documentation for the given `class-name`.

  `class-name` must be a valid class which is resolvable in the
  current classloader context."
  [package-url ^String class-name]
  {:pre [(Class/forName class-name)]}
  (URL. package-url (str (.replace class-name \. \/) ".html")))

(def ^:dynamic *user-options*
  "Reference containing options to be used by `#'javadoc` when none are provided.

  The referenced options should be an options map per `#'default-options`.

  Programmatic access should pass options explicitly rather than rely
  on this behavior."
  (atom default-options))

(defn browse-javadoc-for
  "Attempts to open a browser window viewing the Javadocs for the given object or class.

  If no options are provided, uses `#'*user-options*` which defaults
  to `#'default-options` unless modified or bound by the user.

  Programmatic access should pass options explicitly rather than rely
  on this behavior."
  ([class-or-object]
   (browse-javadoc-for @*user-options* class-or-object))
  ([options class-or-object]
   (let [^Class c             (if (instance? Class class-or-object)
                                class-or-object
                                (class class-or-object))
         ^String package-name (str (.getName (.getPackage c)) ".")]
     (if-let [package-url (javadoc-url-for-package options package-name)]
       (let [javadoc-url (javadoc-url-for-class package-url (.getName c))]
         (browse-url javadoc-url)
         javadoc-url)
       {:type    ::error
        :message "Could not find Javadoc for package"
        :package package-name
        :class   (.getName c)
        :options options}))))
