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
(using [Pedestal](http://pedestal.io)) or set up custom matches and invokers.  See
below for more on what these are.

## Usage

Include the core library in your `deps.edn`:
```clojure
{:deps {com.monkeyprojects/mailman-core {:mvn/version "<version>"}}}
```

Or with Leiningen:
```clojure
(defproject ...
  :dependencies [[com.monkeyprojects/mailman-core "<version>"]])
```

The core library provides the protocols and basic functionality.  There is also an
in-memory implementation of a broker, for testing and development purposes.

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

## Handlers

A handler is a function that takes an event, and returns a result structure.  This
structure consists of the input event, the handler and a result.  To make setting
up handlers easier, a protocol exists called `ToHandler`.  It is responsible for
converting its argument in a handler record.  This is then converted into a handler
function by the router.

Implementations exist that convert a simple function or a map into a handler.
The function is wrapped and its return value converted to the aforementioned result
structure.

### Map Handlers

Map handlers are useful if you want to provide some more detail to the handler.
At the very least it requires a `:handler` key, that provides the handler function,
which takes an event, and returns some result (probably new events to dispatch).

But you can also add interceptors to them.

### Interceptors

One of the most useful things in standard HTTP libraries are the interceptors.
They provide a more flexible way of converting input and output for handlers than
the standard Ring-style function wrapping.  In *Mailman*, we opted to use the
interceptors provided by [Pedestal](http://pedestal.io).  Some default interceptors
have been provided in the `monkey.mailman.interceptors` namespace.

The last interceptor is usually the one that actually invokes the handler function,
and converts the result.  This is the `handler-interceptor`.  There is also a
`sanitize-result` interceptor, that converts the handler return value into a
sequence of events, and drops any non-event things.

But you can also provide your own interceptors.  See the [Pedestal interceptor
documentation](http://pedestal.io/pedestal/0.7/guides/what-is-an-interceptor.html)
for details on how to do that.

And in order to use all these as a regular handler, there is the `interceptor-handler`
function, that wraps a handler so interceptors are execute correctly.  Note that this
is all done for you by the router.

So in order to create a router with interceptors, you could write something like this:
```clojure
(def routes
  {:my/event-type {:handler my-handler
                   :interceptors [log-events]}})

(def router (mm/router routes {:interceptors [(mm/sanitize-result)]}))
```

As shown, you can also provide top-level interceptors in the options map to the router.
These are combined with the route-specific interceptors.  The route-specific interceptors
are appended to the global interceptors, so they have "priority" so to speak.

## Customization

The `router` function accepts an optional second argument, which is an options map
that allows you to override some default behaviour of the router.  You can specify
a custom `matcher`, and also an `invoker`.  See below for more.

### Route Matchers

Since Mailman is all about allowing freedom, but providing sensible defaults, the route
matching is set up in a similar fashion.  It's actually a protocol (calls `RouteMatcher`)
and by default it sets up the routes as a map, and matches by event `:type`.  Each map
value can have multiple possible handlers.

`RouteMatcher` provides two functions: `compile-routes` and `find-handlers`.  The former
takes the routes as provided to the `router` function, and converts them into a format
more suitable to the matcher.  The latter looks up handlers for an incoming event.

Functions also implement the protocol, and by default they leave the routes untouched
(so `compile-routes` is a dummuy function) and just invokes the function to find
handlers.

And you can also specify your own matcher by implementing the protocol.  Override the
default matcher by specifying the `:matcher` key in the options map when calling
`router`.

```clojure
(defrecord CustomMatcher []
  mm/RouteMatcher
  (compile-routes [this routes]
    ;; Prepare routes for the finder
    ...)

  (find-handlers [this routes evt]
    ;; Actually find handlers in the compiled routes
    ...))

(mm/router routes {:matcher (->CustomMatcher)})
```

### Invokers

By default, handler invocation is done by just invoking each handler in sequence.
But you can specify your own invoker, for instance to do async event handling so
one handler cannot block another one.  An invoker is a 2-arity function that takes
the handlers, as returned by `find-handlers`, and the event to handle.  It is
supposed to return a list of results, which is then passed back to the broker.

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

## Implementations

Currently, *Mailman* provides two implementations for its protocols:

 - `manifold`, based on the excellent [manifold](https://github.com/clj-commons/manifold) async library.
 - `jms`, that builds upon the [monkey-jms](https://github.com/monkey-projects/monkey-jms) library to connect to a JMS broker for messaging.

The [manifold lib](manifold) provides an in-memory broker, similar to the one provided in the
core, but it's built upon Manifold streams.  Furthermore, it provides some functions
to work with async event handlers, whereas all core functions all assume your handlers
work synchronously.

The [JMS lib](jms) uses JMS 3.0 to connect to a broker using AMQP protocol.  It is able
to use queues or topics (possibly durable) for posting and receiving events.  Events are
by default encoded using `edn`, but this can be overridden.

## LICENSE

[GPLv3.0 license](LICENSE)

Copyright (c) 2025 by [Monkey Projects](https://www.monkey-projects.be)