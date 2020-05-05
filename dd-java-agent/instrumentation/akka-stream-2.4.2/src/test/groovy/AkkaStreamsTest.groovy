import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.DelayOverflowStrategy
import akka.stream.Materializer
import akka.stream.javadsl.Keep
import akka.stream.javadsl.RunnableGraph
import akka.stream.javadsl.Sink
import akka.stream.javadsl.Source
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import scala.compat.java8.FutureConverters
import scala.concurrent.duration.FiniteDuration
import spock.lang.Shared

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

// Based on ReactorCoreTest
class AkkaStreamsTest extends AgentTestRunner {

  public static final String EXCEPTION_MESSAGE = "test exception"

  @Shared
  ActorSystem system = ActorSystem.create("testing")

  @Shared
  Materializer materializer = ActorMaterializer.create(system)

  @Shared
  def addOne = { i ->
    // FIXME: Our clock implementation doesn't guarantee that start times are monotonic across
    //  traces, we base span start times on a millisecond time from the start of the trace and
    //  offset a number of nanos, this does not guarantee that the start times are monotonic. Thus
    //  2 traces started during the same millisecond might have the span start times wrong relative
    //  to each other. IE: TraceA and TraceB start at the same millisecond, SpanA1 starts, then
    //  SpanB1 starts. SpanB1 can have an earlier startTimeNano than SpanA1
    sleep(1)
    addOneFunc(i)
  }

  @Shared
  def throwException = {
    throw new RuntimeException(EXCEPTION_MESSAGE)
  }

  def "Source '#name' test"() {
    when:
    def result = runUnderTrace(sourceSupplier)

    then:
    result == expectedSum
    and:
    sortAndAssertTraces(1) {
      trace(0, workSpans + 2) {
        span(0) {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))

        for (int i = 0; i < workSpans; i++) {
          span(i + 2) {
            resourceName "addOne"
            operationName "addOne"
            childOf(span(1))
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    name                    | expectedSum | workSpans | sourceSupplier
    "basic source"          | 2           | 1         | { -> Source.single(1).map(addOne) }
    "two operations source" | 4           | 2         | { -> Source.single(2).map(addOne).map(addOne) }
    "delayed source"        | 4           | 1         | { ->
      Source.single(3)
        .delay(FiniteDuration.apply(100, TimeUnit.MILLISECONDS), DelayOverflowStrategy.backpressure())
        .map(addOne)
    }
    "delayed twice source"  | 6           | 2         | { ->
      Source.single(4)
        .delay(FiniteDuration.apply(100, TimeUnit.MILLISECONDS), DelayOverflowStrategy.backpressure())
        .map(addOne)
        .delay(FiniteDuration.apply(100, TimeUnit.MILLISECONDS), DelayOverflowStrategy.backpressure())
        .map(addOne)
    }
    "basic source"          | 13          | 2         | { -> Source.from([5, 6]).map(addOne) }
    "two operations source" | 17          | 4         | { -> Source.from([6, 7]).map(addOne).map(addOne) }
    "delayed source"        | 17          | 2         | { ->
      Source.from([7, 8])
        .delay(FiniteDuration.apply(100, TimeUnit.MILLISECONDS), DelayOverflowStrategy.backpressure())
        .map(addOne)
    }
    "delayed twice source"  | 21          | 4         | { ->
      Source.from([8, 9])
        .delay(FiniteDuration.apply(100, TimeUnit.MILLISECONDS), DelayOverflowStrategy.backpressure())
        .map(addOne)
        .delay(FiniteDuration.apply(100, TimeUnit.MILLISECONDS), DelayOverflowStrategy.backpressure())
        .map(addOne)
    }

    "source from future"    | 12          | 2         | { ->
      Source.fromFuture(FutureConverters.toScala(CompletableFuture.supplyAsync({ addOneFunc(10) })))
        .map(addOne)
    }
  }

//  def "Publisher error '#name' test"() {
//    when:
//    runUnderTrace(publisherSupplier)
//
//    then:
//    def exception = thrown RuntimeException
//    exception.message == EXCEPTION_MESSAGE
//    and:
//    sortAndAssertTraces(1) {
//      trace(0, 2) {
//        span(0) {
//          resourceName "trace-parent"
//          operationName "trace-parent"
//          parent()
//          errored true
//          tags {
//            "$Tags.COMPONENT" "trace"
//            errorTags(RuntimeException, EXCEPTION_MESSAGE)
//            defaultTags()
//          }
//        }
//
//        // It's important that we don't attach errors at the Reactor level so that we don't
//        // impact the spans on reactor integrations such as netty and lettuce, as reactor is
//        // more of a context propagation mechanism than something we would be tracking for
//        // errors this is ok.
//        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
//      }
//    }
//
//    where:
//    name   | publisherSupplier
//    "mono" | { -> Mono.error(new RuntimeException(EXCEPTION_MESSAGE)) }
//    "flux" | { -> Flux.error(new RuntimeException(EXCEPTION_MESSAGE)) }
//  }
//
//  def "Publisher step '#name' test"() {
//    when:
//    runUnderTrace(publisherSupplier)
//
//    then:
//    def exception = thrown RuntimeException
//    exception.message == EXCEPTION_MESSAGE
//    and:
//    sortAndAssertTraces(1) {
//      trace(0, workSpans + 2) {
//        span(0) {
//          resourceName "trace-parent"
//          operationName "trace-parent"
//          parent()
//          errored true
//          tags {
//            "$Tags.COMPONENT" "trace"
//            errorTags(RuntimeException, EXCEPTION_MESSAGE)
//            defaultTags()
//          }
//        }
//
//        // It's important that we don't attach errors at the Reactor level so that we don't
//        // impact the spans on reactor integrations such as netty and lettuce, as reactor is
//        // more of a context propagation mechanism than something we would be tracking for
//        // errors this is ok.
//        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
//
//        for (int i = 0; i < workSpans; i++) {
//          span(i + 2) {
//            resourceName "addOne"
//            operationName "addOne"
//            childOf(span(1))
//            tags {
//              "$Tags.COMPONENT" "trace"
//              defaultTags()
//            }
//          }
//        }
//      }
//    }
//
//    where:
//    name                 | workSpans | publisherSupplier
//    "basic mono failure" | 1         | { -> Mono.just(1).map(addOne).map({ throwException() }) }
//    "basic flux failure" | 1         | { -> Flux.fromIterable([5, 6]).map(addOne).map({ throwException() }) }
//  }
//
//  def "Publisher '#name' cancel"() {
//    when:
//    cancelUnderTrace(publisherSupplier)
//
//    then:
//    assertTraces(1) {
//      trace(0, 2) {
//        span(0) {
//          resourceName "trace-parent"
//          operationName "trace-parent"
//          parent()
//          tags {
//            "$Tags.COMPONENT" "trace"
//            defaultTags()
//          }
//        }
//
//        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
//      }
//    }
//
//    where:
//    name         | publisherSupplier
//    "basic mono" | { -> Mono.just(1) }
//    "basic flux" | { -> Flux.fromIterable([5, 6]) }
//  }
//
//  def "Publisher chain spans have the correct parent for '#name'"() {
//    when:
//    runUnderTrace(publisherSupplier)
//
//    then:
//    assertTraces(1) {
//      trace(0, workSpans + 2) {
//        span(0) {
//          resourceName "trace-parent"
//          operationName "trace-parent"
//          parent()
//          tags {
//            "$Tags.COMPONENT" "trace"
//            defaultTags()
//          }
//        }
//
//        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
//
//        for (int i = 0; i < workSpans; i++) {
//          span(i + 2) {
//            resourceName "addOne"
//            operationName "addOne"
//            childOf(span(1))
//            tags {
//              "$Tags.COMPONENT" "trace"
//              defaultTags()
//            }
//          }
//        }
//      }
//    }
//
//    where:
//    name         | workSpans | publisherSupplier
//    "basic mono" | 3         | { -> Mono.just(1).map(addOne).map(addOne).then(Mono.just(1).map(addOne)) }
//    "basic flux" | 5         | { -> Flux.fromIterable([5, 6]).map(addOne).map(addOne).then(Mono.just(1).map(addOne)) }
//  }
//
//  def "Publisher chain spans have the correct parents from assembly time '#name'"() {
//    when:
//    runUnderTrace {
//      // The operations in the publisher created here all end up children of the publisher-parent
//      Publisher<Integer> publisher = publisherSupplier()
//
//      AgentSpan intermediate = startSpan("intermediate")
//      // After this activation, all additions to the assembly are children of this span
//      AgentScope scope = activateSpan(intermediate, true)
//      try {
//        if (publisher instanceof Mono) {
//          return ((Mono) publisher).map(addOne)
//        } else if (publisher instanceof Flux) {
//          return ((Flux) publisher).map(addOne)
//        }
//        throw new IllegalStateException("Unknown publisher type")
//      } finally {
//        scope.close()
//      }
//    }
//
//    then:
//    sortAndAssertTraces(1) {
//      trace(0, (workItems * 2) + 3) {
//        span(0) {
//          resourceName "trace-parent"
//          operationName "trace-parent"
//          parent()
//          tags {
//            "$Tags.COMPONENT" "trace"
//            defaultTags()
//          }
//        }
//
//        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
//        basicSpan(it, 2, "intermediate", "intermediate", span(1))
//
//        for (int i = 0; i < workItems * 2; i++) {
//          span(i + 3) {
//            resourceName "addOne"
//            operationName "addOne"
//            childOf(span(i % 2 == 0 ? 1 : 2))
//            tags {
//              "$Tags.COMPONENT" "trace"
//              defaultTags()
//            }
//          }
//        }
//      }
//    }
//
//    where:
//    name         | workItems | publisherSupplier
//    "basic mono" | 1         | { -> Mono.just(1).map(addOne) }
//    "basic flux" | 2         | { -> Flux.fromIterable([1, 2]).map(addOne) }
//  }
//
//  def "Publisher chain spans can have the parent removed at assembly time '#name'"() {
//    when:
//    runUnderTrace {
//      // The operations in the publisher created here all end up children of the publisher-parent
//      Publisher<Integer> publisher = publisherSupplier()
//
//      // After this activation, all additions to the assembly will create new traces
//      AgentScope scope = activateSpan(AgentTracer.noopSpan(), true)
//      try {
//        if (publisher instanceof Mono) {
//          return ((Mono) publisher).map(addOne)
//        } else if (publisher instanceof Flux) {
//          return ((Flux) publisher).map(addOne)
//        }
//        throw new IllegalStateException("Unknown publisher type")
//      } finally {
//        scope.close()
//      }
//    }
//
//    then:
//    sortAndAssertTraces(1 + workItems) {
//      trace(0, 2 + workItems) {
//        span(0) {
//          resourceName "trace-parent"
//          operationName "trace-parent"
//          parent()
//          tags {
//            "$Tags.COMPONENT" "trace"
//            defaultTags()
//          }
//        }
//
//        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
//
//        for (int i = 0; i < workItems; i++) {
//          span(2 + i) {
//            resourceName "addOne"
//            operationName "addOne"
//            childOf(span(1))
//            tags {
//              "$Tags.COMPONENT" "trace"
//              defaultTags()
//            }
//          }
//        }
//      }
//      for (int i = 0; i < workItems; i++) {
//        trace(i + 1, 1) {
//          span(0) {
//            resourceName "addOne"
//            operationName "addOne"
//            parent()
//            tags {
//              "$Tags.COMPONENT" "trace"
//              defaultTags()
//            }
//          }
//        }
//      }
//    }
//
//    where:
//    name         | workItems | publisherSupplier
//    "basic mono" | 1         | { -> Mono.just(1).map(addOne) }
//    "basic flux" | 2         | { -> Flux.fromIterable([1, 2]).map(addOne) }
//  }

  @Trace(operationName = "trace-parent", resourceName = "trace-parent")
  def runUnderTrace(Closure<Source> sourceSupplier) {
    final AgentSpan span = startSpan("publisher-parent")

    AgentScope scope = activateSpan(span, true)
    try {
      scope.setAsyncPropagation(true)

      def source = sourceSupplier()
      // Read all data from publisher
      Sink<Integer, CompletionStage<Integer>> sink =
        Sink.<Integer, Integer> fold(0, { aggr, next -> aggr + next })

      // connect the Source to the Sink, obtaining a RunnableFlow
      RunnableGraph<CompletionStage<Integer>> runnable =
        source.toMat(sink, Keep.right())

      // materialize the flow
      CompletionStage<Integer> sum = runnable.run(materializer)

      return sum.toCompletableFuture().get()
    } finally {
      scope.close()
    }
  }

  @Trace(operationName = "trace-parent", resourceName = "trace-parent")
  def cancelUnderTrace(def publisherSupplier) {
    final AgentSpan span = startSpan("publisher-parent")
    AgentScope scope = activateSpan(span, true)
    scope.setAsyncPropagation(true)

    def publisher = publisherSupplier()
    publisher.subscribe(new Subscriber<Integer>() {
      void onSubscribe(Subscription subscription) {
        subscription.cancel()
      }

      void onNext(Integer t) {
      }

      void onError(Throwable error) {
      }

      void onComplete() {
      }
    })

    scope.close()
  }

  @Trace(operationName = "addOne", resourceName = "addOne")
  def static addOneFunc(int i) {
    return i + 1
  }

  void sortAndAssertTraces(
    final int size,
    @ClosureParams(value = SimpleType, options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {

    TEST_WRITER.waitForTraces(size)

    TEST_WRITER.each {
      it.sort({ a, b ->
        return a.startTimeNano <=> b.startTimeNano
      })
    }

    TEST_WRITER.sort({ a, b ->
      return a[0].startTimeNano <=> b[0].startTimeNano
    })

    assertTraces(size, spec)
  }
}
