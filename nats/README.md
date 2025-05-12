# Mailman NATS implementation

This is a [Mailman](../README.md) implementation that uses [NATS](https://nats.io) for
event posting and receiving.  See the NATS documentation for more details on how it works.
This library uses the [clj-nats-async](https://github.com/monkey-projects/clj-nats-async)
that in turn uses the [Java Nats library](https://github.com/nats-io/nats.java).

## Usage

First include the lib in your project:
```clojure
;; Deps.edn
{:deps {com.monkeyprojects/mailman-nats {:mvn/version "<VERSION>"}}}
```

Or when using [Leiningen](https://leiningen.org):
```clojure
:dependencies [[com.monkeyprojects/mailman-nats "<VERSION>"]]
```

Then `require` the core namespace in your code:
```clojure
(require '[monkey.mailman.nats.core :as mn])
```

Connect to a NATS broker (or cluster) and then you can use all the Mailman functionality for
sending and receiving events:
```clojure
;; Include the core ns for the basic functions
(require '[monkey.mailman.core :as mc])

;; Add options as needed
(def nats (mn/connect {:urls "nats://nats-broker:4222" :secure? true}))

;; Map event types to subjects
(def subjects {:event/type-1 "test.subject.1"
               :event/type-2 "test.subject.2"})

;; Create a broker that determines the subject from the event type
(def broker (mn/make-broker nats {:subject-mapper (comp subjects :type)}))

(defn on-recv [evt]
  (println "Received event:" evt))

;; Start listening
(def listener (mc/add-listener broker {:subject "test.subject.1" :handler on-recv}))

;; Post something
(mc/post-events broker [{:type :event/type-1 :message "test event"}])

;; Clean up
(.close broker)
```

## Queues

NATS also supports [queue groups](https://docs.nats.io/nats-concepts/core-nats/queue).
These allow multiple subscribers on the same subject, but still make sure only one of
them receives each message.  This is useful for load balancing.  In order to subscribe
using a queue group, specify `:queue` in the handler, like this:

```clojure
(mc/add-listener broker {:subject "test.subject"
                         :handler my-handler
			 :queue "my-queue"})
```

## JetStream

[JetStream](https://docs.nats.io/nats-concepts/jetstream) is a kind of persistence layer
on top of Nats messaging.  It allows listeners to retrieve messages that were sent when
they were offline, among other things.  Mailman also supports this.  To enable JetStream
consumption, you have to configure a `stream` and `consumer`.  Make sure you have
previously created this stream and consumer using Nats management.  The stream id
is used to capture posted events, while the consumer is used to pull them from the
storage layer.  In Mailman, consumers are always durable, but you can configure
other settings using the `consumer-opts` property.

```clojure
;; Stream and consumer can be configured on broker or listener level.
(def broker (mn/make-broker nats {:stream "my-stream"}))

(def l (mn/add-listener broker {:consumer "my-consumer" :handler println}))
```

The above example will create a JetStream consumer that receives messages from
the stream `my-stream`, with durable consumer id `my-consumer`.  Note that you
cannot specify a subject at this level, since this has already been configured
when creating the stream and the consumer.  See the [Nats
documentation](https://docs.nats.io/nats-concepts/jetstream/streams) for more
details on this.

## License

Copyright (c) 2025 by [Monkey Projects BV](https://www.monkey-projects.be)

[GPLv3](../LICENSE)