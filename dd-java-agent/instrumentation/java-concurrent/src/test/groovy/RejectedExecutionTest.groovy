import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDId
import datadog.trace.core.DDSpan

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

class RejectedExecutionTest extends AgentTestRunner {

  // TODO test elasticsearch RejectedExecutionHandlers which downcast or throw

  def "trace reported when FJP shutdown"() {
    // tests the shutdown state because it's easy to provoke without
    // spying the same points we instrument. This works the same way
    // in FJPs no matter the reason for rejection, and this could be
    // provoked (most of the time) by submitting a lot of tasks very
    // quickly
    setup:
    ForkJoinPool fjp = new ForkJoinPool()
    fjp.shutdownNow()

    when:
    runUnderTrace("parent") {
      fjp.submit({})
    }

    then:
    thrown RejectedExecutionException
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 1
    TEST_WRITER.get(0).get(0).getOperationName() == "parent"
  }

  def "trace reported when thread pool shut down"() {
    setup:
    def testClosure = setupShutdownExecutor(new ThreadPoolExecutor.AbortPolicy())

    when:
    testClosure()

    then:
    thrown RejectedExecutionException
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 1
    TEST_WRITER.get(0).get(0).getOperationName() == "parent"
  }

  def "trace reported when thread pool shut down with #rejectedExecutionHandler"() {
    setup:
    def testClosure = setupShutdownExecutor(rejectedExecutionHandler)

    when:
    testClosure()

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 1 // in all cases because the executor is shut down
    TEST_WRITER.get(0).get(0).getOperationName() == "parent"

    where:
    // this test requires these not to throw
    rejectedExecutionHandler << [
      new SwallowingRejectedExecutionHandler(),
      new ThreadPoolExecutor.CallerRunsPolicy(),
      new ThreadPoolExecutor.DiscardPolicy(),
      new ThreadPoolExecutor.DiscardOldestPolicy()
    ]
  }

  def "trace reported when live thread pool rejects work and throws with #rejectedExecutionHandler"() {
    setup:
    def testClosure = setupBackloggedExecutor(rejectedExecutionHandler)

    when:
    testClosure()

    then:
    thrown RejectedExecutionException
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    List<DDSpan> trace = TEST_WRITER.get(0)
    trace.size() == 1
    trace.get(0).getOperationName() == "parent"


    where:
    // this test requires these throw
    rejectedExecutionHandler << [
      new ThreadPoolExecutor.AbortPolicy()
    ]
  }

  def "trace reported when live thread pool rejects and discards work with #rejectedExecutionHandler"() {
    setup:
    def testClosure = setupBackloggedExecutor(rejectedExecutionHandler)

    when:
    testClosure()

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    List<DDSpan> trace = TEST_WRITER.get(0)
    trace.size() == 1
    trace.get(0).getOperationName() == "parent"


    where:
    // this test requires these not to run the runnable or throw
    rejectedExecutionHandler << [
      new SwallowingRejectedExecutionHandler(),
      new ThreadPoolExecutor.DiscardPolicy()
    ]
  }

  def "trace reported when live thread pool rejects and runs work with #rejectedExecutionHandler"() {
    setup:
    def testClosure = setupBackloggedExecutor(rejectedExecutionHandler)

    when:
    testClosure()

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    List<DDSpan> trace = TEST_WRITER.get(0)
    trace.size() == 2
    DDSpan parent = trace.find {
      it.getOperationName() == "parent"
    }
    DDSpan child = trace.find {
      it.getOperationName() == "asyncChild"
    }
    parent != null
    child != null
    parent.getParentId() == DDId.ZERO
    parent.getSpanId() == child.getParentId()


    where:
    // this test requires these to actually run the runnable
    rejectedExecutionHandler << [
      new ExecutingRejectedExecutionHandler(),
      new ThreadPoolExecutor.CallerRunsPolicy()
      // FIXME - this policy passes the same runnable back to the
      //  executor after removing the head of the queue, and the
      //  sacrificed runnable's continuation would never be closed,
      //  we also don't get a chance to intercept it with simple
      //  method enter/exit instrumentation points.
      //new ThreadPoolExecutor.DiscardOldestPolicy()
    ]
  }

  def setupShutdownExecutor(RejectedExecutionHandler rejectedExecutionHandler) {
    ExecutorService pool = new ThreadPoolExecutor(1,
      1,
      0L,
      TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(1),
      Executors.defaultThreadFactory(),
      rejectedExecutionHandler)
    pool.shutdownNow()

    return {
      runUnderTrace("parent") {
        activeScope().setAsyncPropagation(true)
        pool.submit({})
      }
    }
  }

  def setupBackloggedExecutor(RejectedExecutionHandler rejectedExecutionHandler) {
    ExecutorService pool = new ThreadPoolExecutor(1,
      1,
      0L,
      TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(1),
      Executors.defaultThreadFactory(),
      rejectedExecutionHandler)
    CountDownLatch latch = new CountDownLatch(1)
    // this task will block the executor thread ensuring the next task gets enqueued
    pool.submit({
      latch.await()
    })
    // this will sit in the queue
    pool.submit({})

    return {
      runUnderTrace("parent") {
        activeScope().setAsyncPropagation(true)
        // must be rejected because the queue will be full until some
        // time after the first task is released
        pool.submit((Runnable) new JavaAsyncChild(true, false))
        latch.countDown()
      }
    }
  }

}
