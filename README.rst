stockings
=========

Stockings is a Clojure library that gives you easy access to financial
data such as current and historical stock quotes, current currency
exchange rates, stock symbol suggestions, stock and company info by
exchanges and industry sectors, and more.


Usage
-----

The stockings library is hosted on clojars.org.

Add ``[com.fxtlabs/stockings "1.0.0-SNAPSHOT"]`` to the dependencies
list in your ``project.clj`` file and run ``lein deps`` to download the
library from the Clojars archives.


License
-------

Copyright (C) 2011 Filippo Tampieri

Distributed under the
`Eclipse Public License v1.0 <http://www.eclipse.org/org/documents/epl-v10.php>`_,
the same as Clojure.


Documentation
-------------

Autodoc-generated API reference documentation can be found at
http://stockings.fxtlabs.com.

You can generate a local copy with ``lein autodoc``; it will be saved
in the ``autodoc`` directory under the project root.


Examples
--------

Once you have the stokings JAR file on your classpath, you can run a
REPL, load the library and start exploring. The following shows a few
commands (see user>) and their output (formatted here for readability):

::

  user> (use 'stockings.core)
  nil

  user> (get-quote "YHOO")
  {:symbol "YHOO",
   :last 16.02,
   :last-date-time #<DateTime 2011-05-27T20:00:00.000Z>,
   :name "Yahoo! Inc.",
   :low 15.95,
   :open 16.04,
   :previous-close 15.98,
   :high 16.19,
   :volume 20096766}

gets the current stock quote for YAHOO! Note that the time for the
last trade is represented by a ``org.joda.time.DateTime``
object in UTC. Stockings uses the Joda-Time library for all its
time-related values.

::

  user> (get-quotes "GOOG" "NASDAQ:AAPL")
  ({:symbol "GOOG", ...}
   {:symbol "AAPL", ...})
  

gets the current stock quotes for Google and Apple. Note that stock
symbols may optionally be prefixed with the name of an exchange (like
``NASDAQ:AAPL`` above); however, the Yahoo! Finance service providing the
financial data does not recognize prefixed stock symbols, so the
prefix is simply dropped.

::

  user> (import [org.joda.time LocalDate])
  org.joda.time.LocalDate

  user> (get-historical-quotes "YHOO" (LocalDate. 2011 4 1) (LocalDate. 2011 5 1))
  (#:stockings.core.HistoricalQuote{:date #<LocalDate 2011-04-29>,
                                    :open 17.46,
                                    :high 17.77,
                                    :low 17.36,
                                    :close 17.7,
                                    :volume 30800000}
   #:stockings.core.HistoricalQuote{:date #<LocalDate 2011-04-28>,
                                    :open 17.22,
                                    :high 17.53,
                                    :low 17.17,
                                    :close 17.51,
                                    :volume 14414700}
   ...
   #:stockings.core.HistoricalQuote{:date #<LocalDate 2011-04-01>,
                                    :open 16.83,
                                    :high 16.98,
                                    :low 16.72,
                                    :close 16.84,
                                    :volume 12487400})

gets the historical stock quotes for Yahoo! for the period starting on
April 1, 2011 and ending on May 1, 2011. There are a few things worth
noting:

* The quotes are returned in chronological order starting from the
  most recent.
* There are no entries for dates that fall on a weekend or holiday.
* The dates are represented by ``org.joda.time.LocalDate`` objects.
  Note that class ``LocalDate`` does not describe an exact instant
  in time (e.g. an instant down to millisecond precision); instead,
  it just refers to a given calendar day and no particular instant
  of time within it.

::

  user> (get-stock "YHOO")
  {:symbol "YHOO",
   :name "Yahoo! Inc.",
   :start-date #<LocalDate 1996-04-12>,
   :end-date #<LocalDate 2011-05-30>,
   :sector "Technology",
   :industry "Internet Information Providers",
   :full-time-employees 13600}  

gets some info on a company.

::

  user> (get-industry-sectors)
  ({:name "Basic Materials",
    :industries [{:id "112", :name "Agricultural Chemicals"}
                 {:id "132", :name "Aluminum"}
                 {:id "110", :name "Chemicals - Major Diversified"}
                 ...
                 {:id "111", :name "Synthetics"}]}
   {:name "Conglomerates",
    :industries [{:id "210", :name "Conglomerates"}]}
   ...)

gets a list of all industry sectors and a list of industries for each
sector.

::

  user> (get-industry 112)
  {:id "112",
   :name "Agricultural Chemicals",
   :companies [{:name "Agrium Inc.", :symbol "AGU"}
               {:name "American Vanguard Corporation", :symbol "AVD"}
               ...
               {:name "Yongye International, Inc.", :symbol "YONG"}]}

gets a list of all companies for a given industry (identified by its
ID).

::

  user> (get-exchange-rate :usd :eur)
  {:base :usd,
   :quote :eur,
   :rate 0.7002,
   :ask 0.7003,
   :bid 0.7001,
   :date-time #<DateTime 2011-05-30T20:43:00.000Z>}

gets the current exchange rate from a base currency (USD) to a quote (or
counter) currency (EUR). The currencies are denoted by their ISO 4217
3-letter designators used as strings or keywords. In other words:

::

  user> (get-exchange-rate "USD" "EUR")

also works as above.

::

  user> (get-symbol-suggestion "Terra Nitro")
  [{:symbol "TNH",
    :name "Terra Nitrogen Company, L.P.",
    :exch "NYQ",
    :type "S",
    :exchDisp "NYSE",
    :typeDisp "Equity"}]

gets stock symbol suggestion for the company whose name starts with
the given prefix. Note that large companies may be traded on several
exchanges and thus correspond to more than one symbol.

So far so good. Now, let us look at a slightly more complex example.
Yahoo! Finance actually offers a lot more financial data when asking
for a stock quote. The ``get-quote`` example given at the beginning of
this section only returned a small subset of this data. Let us now see
how we can pick and choose what data we want to include in our stock
quotes.

::

  user> raw-quote-keys
  (:symbol :Name :PERatio :EarningsShare :EPSEstimateNextYear
   :PercebtChangeFromYearHigh :ChangeFromTwoHundreddayMovingAverage
   :TwoHundreddayMovingAverage :ChangeinPercent :Bid :DaysLow ...)

Var ``raw-quote-keys`` lists all the keys to the data contained in a raw
stock quote (what we can get from Yahoo! Finance). Let's say we want
to get custom stock quotes including the values of the keys ``:symbol``,
``:Name``, and ``:MarketCapitalization``.

::

  user> (def parser (build-quote-parser {:symbol :symbol
                                         :name :Name
                                         :cap :MarketCapitalization}))
  #'user/parser

  user> (get-quote parser "YHOO")
  {:symbol "YHOO", :name "Yahoo! Inc.", :cap 2.0873E10}

Function ``get-quote`` takes a quote parser as an optional first
parameter. The quote parser function is given the raw quote (with all
the keys listed in ``raw-quote-keys``) and returns a parsed quote as a map
with the requested keys (``:symbol``, ``:name``, and ``:cap`` in this case). All
we had to do was call ``build-quote-parser`` with a map specifying the
correspondences between the keys we want in the final quote and the
keys in the raw quote. So ``get-quote`` can get us back a result as a map
with exactly the info we want, no more, no less; note also that we are
completely free to use whatever names we want for the keys of the
resulting map.

Should we require even more flexibility, we can write our own quote
parser directly:

::

  user> (defrecord MyQuote [stock-symbol company-name last last-date-time])
  user.MyQuote

  user> (defn my-parser [q]
          (let [stock-symbol (parse-quote-item q :symbol)
                company-name (parse-quote-item q :Name)
                last (parse-quote-item q :LastTradePriceOnly)
                last-date-time (parse-last-trade-date-time q)]
            (MyQuote. stock-symbol company-name last last-date-time)))
  #'user/my-parser

  user> (get-quote my-parser "YHOO")
  #:user.MyQuote{:stock-symbol "YHOO",
                 :company-name "Yahoo! Inc.",
                 :last 16.02,
                 :last-date-time #<DateTime 2011-05-27T20:00:00.000Z>}

The quote parser does not have to return a map; it can actually return
any type you like. Function ``parse-quote-item`` is used to parse one
field of the raw quote; it knows the data type (string, double, int,
etc.) of every field and will return the correct value and type.
Function ``parse-last-trade-date-time`` combines the values of
``:LastTradeDate`` and ``:LastTradeTime`` and returns them as a
``org.joda.time.DateTime`` object in UTC. You will want to use this
function if you need access to the last trade time and date because
the data returned by Yahoo! Finance for these fields is a bit
inconsistent; the date value is in UTC, but the time value is in the
time zone of the North American East Coast! Function
``parse-last-trade-date-time`` corrects for this and returns a date time
in UTC.

Stockings includes even more data and functions to help you dig into it.
Please, consult the API Reference Guide at
http://stockings.fxtlabs.com for more details.


Notes
-----

Most of the financial data is downloaded from Yahoo! Finance using
YQL, the Yahoo! Query Language to query the
yahoo.finance.historicaldata, yahoo.finance.industry,
yahoo.finance.quotes, yahoo.finance.sectors, yahoo.finance.stocks, and
yahoo.finance.xchange data tables.

Google Stock is used as an alternate data source for current and
historical stock quotes if desired (see stockings.alt namespace).

NASDAQ keeps lists of the companies traded on the NASDAQ, NYSE, and
AMEX exchanges at http://www.nasdaq.com/screening/company-list.aspx.
All the data in these lists can be accessed through the
stockings.exchanges namespace. Note that the grouping of companies
into industries and sectors used by NASDAQ does not match exactly
those used by Yahoo! Finance, so you may find some discrepancies between
the groupings you get through the stockings.exchanges and stockings.core
namespaces.

