# Mailman JMS Implementation

This is a part of the *Mailman* library that provides a [JMS](https://www.oracle.com/technical-resources/articles/java/intro-java-message-service.html)
implementation of the core broker.  It allows you to use the Mailman core functionality
while connecting to a JMS broker, such as [Apache Artemis](https://activemq.apache.org/components/artemis/).

## Usage

Add the lib to your dependencies:

```clojure
;; deps.edn
{:deps {com.monkeyprojects/mailman-jms {:mvn/version "0.1.0-SNAPSHOT"}}}
```

Connect to the remote broker:
```clojure
(require '[monkey.mailman.jms :as jms])

;; Connect using basic configuration
(def broker (jms/jms-broker {:url "amqp://broker-url:61616" :username "testuser" :password "verysecret"}))
```

From the on, you can register listeners, post events, etc., as explained in the
[core documentation](../README.md).  When you're done, you can invoke the `disconnect`
function to close the connection to the broker, or preferrably, invoke `close` on
the broker, which also shuts down consumers and producers (see below).

## Configuration

At the very least, you'll have to configure the destinations to post events to, and
to receive from.  It's possible to set a single global destination in the options map,
but this may very likely be insufficient:

```clojure
;; You can use either topic:// or queue:// as prefix
(jms/jms-broker {:url "..." :destination "topic://my.topic"})
```

This will use that single destination for all events when posting and for all listeners.

### Listening to Multiple Destinations

It is however also possible to use multiple destinations, per event that's posted and
per listener.  For posting, you can specify a `destination-mapper` in the configuration.
This is a function that is takes an outgoing event, and returns the destination (as a
string).  Typically you'll want to link the event `:type` to a destination:

```clojure
(def destinations-per-type
  {:some/event "queue://first.queue"
   :other/event "topic://second.topic"})

(def mapper (comp destinations-per-type :type))

(def broker (jms/jms-broker {:url "..." :destination-mapper mapper}))

(require '[monkey.mailman.core :as mc])

;; This will post the event to destination queue://first.queue
(mc/post-events broker [{:type :some/event :message "some event"}])
```

In order to listen on a specific destination, you'll have to pass that in when invoking
`add-listener`.  The JMS implementation of this function accepts both a function or a map
containing the `handler` and `destination`:

```clojure
(mc/add-listener broker {:handler my-handler-fn
                         :destination "topic://second.topic"})

;; This listener will use the global `destination` specified when connecting
(mc/add-listener broker my-other-handler)
```

When adding a listener to a destination for the first time, a new consumer will be
set up.  You can unregister a listener by invoking `mc/unregister-listener` on the
return value of `add-listener`.

### Posting to Multiple Destinations

By default, the `destination` configured on the broker is used to select where to
post new messages using `post-events`.  If a `destination-mapper` is specified, that
one will have priority.  It will be called on each posted event to determine the
destination.

You can however also override the destination *per event*.  To do this, simply add
a `destination` property to the event:

```clojure
(def destinations
  {:event-1 "topic://destination-1"
   :event-2 "topic://destination-2"})

;; Destination mapper checks the `destinations` map
(def broker (jms/jms-broker {:destination-mapper (comp destinations :type)}))

;; This event is posted to topic://destination-1
(mc/post-events broker [{:type :event-1 :message "First message"}])

;; This event is posted to topic://other-destination, even though it's type is :event-1
(mc/post-events broker [{:type :event-1 :message "Second message" :destination "topic://other-destination"}])
```

This allows for maximum flexibility when posting events.

### Serialization

By default all messages are encoded as `edn`, but you can override this by specifying
a `serializer` or `deserializer` function in the configuration.

### Durable Subscribers

Similarly, you can specify a `client-id` and `id` in the broker configuration.  Both
are needed, the `client-id` is used when connecting to the broker, and the `id` is
used when creating a message consumer.

## Closing

The JMS implementation the Mailman broker implements `java.lang.AutoCloseable` so in
order to do release any resources and make sure all messages are published, you should
invoke `close` on the broker when it's no longer needed.  This also closes the connection
to the JMS broker server.

You can, however, specify your own connection and not have it closed by manually creating
a `JmsRecord` object, like this:

```clojure
;; Create own connection
(require '[monkey.jms :as mj])

(def conn (mj/connect {:url "..."}))

(def broker (jms/->JmsBroker
             conn
	     {:destination "topic://test-dest"}
	     (jms/make-state)))

;; Now, close will not disconnect
(.close broker) ; => connection is still open

;; Manually disconnect
(mj/disconnect conn)
```

## TODO

Some things will probably be needed as we go along:

 - Allow to specify a consumer id when calling `add-listener` instead of in the global config.
 - Auto-close the consumer when the last listener for a destination has been unregistered.

## License

[GPLv3.0 license](../LICENSE)

Copyright (c) 2025 by [Monkey Projects BV](https://www.monkey-projects.be)
