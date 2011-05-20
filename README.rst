stockings
=========

Stockings is a library that gives you easy access to current and
historical stock quotes as well as a little bit of other financial data.

Usage
-----

The stockings library is hosted on clojars.org.

Add ``[com.fxtlabs/stockings "1.0.0-SNAPSHOT"]`` to the dependencies
list in your ``project.clj`` file and run ``lein deps`` to download the
library from the Clojars archives.

License
-------

Copyright (C) 2011 Filippo Tampieri

Distributed under the Eclipse Public License, the same as Clojure.

Documentation
-------------

Autodoc generated documentation can be found at
http://stockings.fxtlabs.com.

You can generate a local copy with ``lein autodoc``; it will be saved
in the ``autodoc`` directory under the project root.

Notes
-----

The historical stock quotes are downloaded on-the-fly from Google Finance.

NASDAQ keeps lists of the companies traded on the NASDAQ, NYSE, and
AMEX exchanges at http://www.nasdaq.com/screening/company-list.aspx.

Todo
----

* Add support for current quotes from Yahoo! Finance
* Add list of companies for the NASDAQ, NYSE, and AMEX exchanges

