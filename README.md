# Microfiche

**TL;DR** [Javadoc](https://docs.oracle.com/javase/9/javadoc/javadoc.htm) as data for Clojure.

Developed as part of the [stacks](https://github.com/arrdem/stacks) project.

Provides an alternative to `clojure.java.javadoc` for finding & browsing Javadocs, as well as tools
for screen scraping Javadoc HTML into more useful data structures.

## Demo

```clj
user> (require '[microfiche
                 :refer [*user-options*
                         locate-javadoc
                         browse-javadoc]])
nil
user> (locate-javadoc Class)
;; FIXME
```

## License

Copyright © 2017 Reid "arrdem" McKenzie

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
