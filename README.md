# Mailman

A Clojure library that provides a simple way to handle events in a generic manner.
The goal is to be able to declare event handling similar to HTTP routing libraries,
such as [reitit](https://github.com/metosin/reitit).

The core library is implementation agnostic, but it provides some sensible defaults
and an in-memory implementation of a simple event broker, meant for development and
testing purposes.

The handler functions are supposed to be simple 1-arity functions that take an event
as argument, and return a sequence of resulting events that should be posted back to
the broker, or `nil` if no events should be posted.

Mailman provides additional helper functions to create routers, add interceptors
(using [Pedestal](https://pedestal.io)) or set up custom matches and invokers.  See
more below on what these are.

## Usage

Include the library in your `deps.edn`:
```clojure
{:deps {com.monkeyprojects/mailman {:mvn/version "<version>"}}}
```

Or with Leiningen:
```clojure
(defproject ...
  :dependencies [[com.monkeyprojects/mailman "<version>"]])
```

Functionality is spread over several namespaces, but the core is in `monkey.mailman.core`.
Similar to a HTTP-style application, you define your "routes", which map event types to
handlers.  The default configuration assumes events have a `:type` property that is
used to match the available event handlers.

You can declare are router function, that takes an event, invokes the matching handlers,
and returns the results.

```clojure
(require '[monkey.mailman.core :as mm])

(def routes {:event-1 [(constantly ::first)]
             :event-2 [(constantly ::second)]})

(def router (mm/router routes))

(router {:type :event-1})
;; => returns [{:event {:type :event-1} :handler <handler-fn> :result ::first}]
```

The event handlers are 1-arity functions that take the event as argument.  The return
value is added to the resulting structure, along with the input event and the handler
function itself.  It's up to the event broker implementation to process this result,
but the intention is that when the result is an event, it is re-dispatched.

## Brokers

Mailman defines several protocols for how to post and receive events.  The `EventPoster`
is responsible for sending out events.  The `post-events` function can take a sequence
of events to post.

It's counterpart is `EventReceiver` which allows to actively poll for events, using
`pull-events` or to register a listener using `add-listener`.  The listener is invoked
for each received message, and is a 1-arity function, presumably created using `router`.

Mailman provides a default in-memory broker implementation that implements both of these
protocols.

```clojure
(require '[monkey.mailman.mem :as mb])

(def broker (mb/make-memory-events))

;; Post a single event
(mm/post-events broker [{:type ::test-event :message "This is a test event"}])

;; Pull it
(mm/pull-events broker 1)
;; Returns a list holding the previously posted event
```

`pull-events` takes an additional argument indicating how many events you want to
receive.  If `nil` is given, it will pull all available events.  There is a shorthand
function available when you want to pull one event: `pull-next`.

## Listeners

Often you don't want to have to manually pull in events.  For this, you can register
a listener instead, using `add-listener`.

```clojure
;; Register the previously created router as a listener
(def l (mm/add-listener broker router))

;; You can unregister the listener as well
(mm/unregister-listener l)
```

The listener is also a protocol, called `Listener`, which is again dependent on your
broker implementation.

## LICENSE

[GPLv3.0 license](LICENSE)

Copyright (c) 2025 by [Monkey Projects](https://www.monkey-projects.be)