# Microfiche
<img align="right" src="https://github.com/arrdem/microfiche/raw/master/etc/microfiche.jpg" width=300/>

**TL;DR** [Javadoc](https://docs.oracle.com/javase/9/javadoc/javadoc.htm) as data for Clojure.

This project is designed to feed Javadoc data into my [stacks](https://github.com/arrdem/stacks)
project at some point in the future.

The name is in snide reference to the longevity of the Javadoc tool, and the relatively archaic
dialect of HTML which it emits. Yet much like microfiche there's much data worth the recovering in
this format so here we are.

The hope of stacks is that by getting all the entities related to the Clojure we write in a single
namespace for convenience while writing documentation, we'll choose to write more of it and what we
do write will be more usable by our peers because we'll choose to cross-reference because it's easy
to do so. Or tools will be able to cross-reference for us.

Provides an alternative to `clojure.java.javadoc` for finding & browsing Javadocs, as well as some
experimental tools for screen scraping Javadoc HTML into more useful data structures.

## Demo: Finding & Browsing Javadocs

```clj
user> (require '[microfiche
                 :refer [default-options
                         locate-javadoc-for
                         browse-javadoc-for]])
nil
user> default-options
{:type :microfiche/options,
 :remote-roots {"java." "http://docs.oracle.com/javase/8/docs/api/",
                "org.w3c.dom." "http://docs.oracle.com/javase/8/docs/api/",
                "com.sun." "http://docs.oracle.com/javase/8/docs/api/",
                "org.ietf.jgss." "http://docs.oracle.com/javase/8/docs/api/",
                "com.oracle." "http://docs.oracle.com/javase/8/docs/api/",
                "org.omg." "http://docs.oracle.com/javase/8/docs/api/",
                "org.xml.sax." "http://docs.oracle.com/javase/8/docs/api/",
                "javax." "http://docs.oracle.com/javase/8/docs/api/",
                "sun." "http://docs.oracle.com/javase/8/docs/api/"},
 :local-roots {}}
user> (locate-javadoc-for Object)
#object[java.net.URL
        "0x16dab433"
        "http://docs.oracle.com/javase/8/docs/api/java/lang/Object.html"]
user> (locate-javadoc-for java.io.File)
#object[java.net.URL
        "0x249d0971"
        "http://docs.oracle.com/javase/8/docs/api/java/io/File.html"]
user> (browse-javadoc-for java.net.CookieStore)
true

;; There are no published Clojure javadocs, so this will bomb out
user> (locate-javadoc-for :keyword)
ExceptionInfo Could not find Javadoc for package  clojure.core/ex-info (core.clj:4739)
user> (ex-data *e)
{:package "clojure.lang.",
 :class "clojure.lang.Keyword",
 :options {:type :microfiche/options,
           :remote-roots {"java." "http://docs.oracle.com/javase/8/docs/api/",
                          "org.w3c.dom." "http://docs.oracle.com/javase/8/docs/api/",
                          "com.sun." "http://docs.oracle.com/javase/8/docs/api/",
                          "org.ietf.jgss." "http://docs.oracle.com/javase/8/docs/api/",
                          "com.oracle." "http://docs.oracle.com/javase/8/docs/api/",
                          "org.omg." "http://docs.oracle.com/javase/8/docs/api/",
                          "org.xml.sax." "http://docs.oracle.com/javase/8/docs/api/",
                          "javax." "http://docs.oracle.com/javase/8/docs/api/",
                          "sun." "http://docs.oracle.com/javase/8/docs/api/"},
           :local-roots {}}}

;; Create our own options which do list a source of Clojure javadocs
user> (def *options
        (-> default-options
            (assoc-in [:local-roots "clojure.lang."] "file:///home/arrdem/clojure-javadoc/")))
#'user/*options
user> (locate-javadoc-for *options :keyword)
#object[java.net.URL
        "0x68ea9558"
        "file:/home/arrdem/clojure-javadoc/clojure/lang/Keyword.html"]
user>
```

## Demo: Scraping Javadocs

Javadocs are, sadly, statically rendered HTML documents which are rather inflexible. They aren't
data, let alone some abstract markup as data.

Microfiche provides a way to try and convert back from rendered HTML to a data structure, containing
both the HTML fragments of any rich formatted documentation and recovering as much abstract
structure as possible about the documented class or interface.

```clj
user> (require '[microfiche.scraper :refer [scrape-javadoc-for]])
nil
user> (scrape-javadoc-for java.net.HttpURLConnection)
{:type :microfiche.scraper/javadoc,
 :url #object[java.net.URL
              "0x2b4de398"
              "http://docs.oracle.com/javase/8/docs/api/java/net/HttpURLConnection.html"],
 :name "java.net.HttpURLConnection",
 :parents ({:type :java/type,
            :name "Object",
            :package "java.lang",
            :primitive? false,
            :url #object[java.net.URL
                         "0x2f1e1170"
                         "http://docs.oracle.com/javase/8/docs/api/java/lang/Object.html"]}
           {:type :java/type,
            :name "URLConnection",
            :package "java.net",
            :primitive? false,
            :url #object[java.net.URL
                         "0x6f5874ca"
                         "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html"]}),
 :constructors [{:type :microfiche.scraper/constructor,
                 :name nil,
                 :access {:type :microfiche.scraper/access,
                          :access "protected",
                          :static? false,
                          :abstract? false},
                 :signature {:type :microfiche.scraper/signature,
                             :arguments [...]},
                 :doc ("Constructor for the HttpURLConnection.")}],
 :methods ({:type :java/method,
            :class {:type :java/type,
                    :name "URLConnection",
                    :package "java.net",
                    :primitive? false,
                    :url #object[java.net.URL
                                 "0x6cd25ac2"
                                 "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html"]},
            :name "addRequestProperty",
            :url #object[java.net.URL
                         "0x28c73d61"
                         "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html#addRequestProperty-java.lang.String-java.lang.String-"],
            :inherited? true}
           {:type :java/method,
            :class {:type :java/type,
                    :name "URLConnection",
                    :package "java.net",
                    :primitive? false,
                    :url #object[java.net.URL
                                 "0x1f4a9f24"
                                 "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html"]},
            :name "connect",
            :url #object[java.net.URL
                         "0x339843b9"
                         "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html#connect--"],
            :inherited? true}
           ...),
 :fields ({:type :java/field,
           :class {:type :java/type,
                   :name "URLConnection",
                   :package "java.net",
                   :primitive? false,
                   :url #object[java.net.URL
                                "0x5dc35991"
                                "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html"]},
           :name "allowUserInteraction",
           :url #object[java.net.URL
                        "0x2f81353"
                        "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html#allowUserInteraction"],
           :inherited? true}
          {:type :java/field,
           :class {:type :java/type,
                   :name "URLConnection",
                   :package "java.net",
                   :primitive? false,
                   :url #object[java.net.URL
                                "0x56cebe8f"
                                "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html"]},
           :name "connected",
           :url #object[java.net.URL
                        "0x24856e90"
                        "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html#connected"],
           :inherited? true}
          {:type :java/field,
           :class {:type :java/type,
                   :name "URLConnection",
                   :package "java.net",
                   :primitive? false,
                   :url #object[java.net.URL
                                "0x53a68153"
                                "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html"]},
           :name "doInput",
           :url #object[java.net.URL
                        "0x14ee5d8e"
                        "http://docs.oracle.com/javase/8/docs/api/java/net/URLConnection.html#doInput"],
           :inherited? true}
          ...)}
user>
```

## License

Copyright © 2017 Reid "arrdem" McKenzie

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
