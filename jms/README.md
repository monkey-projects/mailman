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
function to close the connection to the broker.

## Configuration

At the very least, you'll have to configure the destinations to post events to, and
to receive from.  It's possible to set a single global destination in the options map,
but this may very likely be insufficient:

```clojure
;; You can use either topic:// or queue:// as prefix
(jms/jms-broker {:url "..." :destination "topic://my.topic"})
```

This will use that single destination for all events when posting and for all listeners.

### Multiple Destinations

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

### Serialization

By default all messages are encoded as `edn`, but you can override this by specifying
a `serializer` or `deserializer` function in the configuration.

### Durable Subscribers

Similarly, you can specify a `client-id` and `id` in the broker configuration.  Both
are needed, the `client-id` is used when connecting to the broker, and the `id` is
used when creating a message consumer.

## TODO

Some things will probably be needed as we go along:

 - Allow to specify a consumer id when calling `add-listener` instead of in the global config.
 - Auto-close the consumer when the last listener for a destination has been unregistered.

## License

[MIT License](../LICENSE)

Copyright (c) 2025 by [Monkey Projects BV](https://www.monkey-projects.be)
