package datadog.trace.instrumentation.reactor.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

/**
 * Based on OpenTracing code.
 * https://github.com/opentracing-contrib/java-reactor/blob/master/src/main/java/io/opentracing/contrib/reactor/TracedSubscriber.java
 */
@Slf4j
public class TracingSubscriber<T> implements CoreSubscriber<T> {
  private final Subscriber<? super T> subscriber;
  private final Context context;
  private final AgentSpan span;
  private TraceScope.Continuation continuation;

  public TracingSubscriber(final Subscriber<? super T> subscriber, final Context context) {
    this(subscriber, context, AgentTracer.activeSpan());
  }

  public TracingSubscriber(
      final Subscriber<? super T> subscriber, final Context context, final AgentSpan span) {
    this.subscriber = subscriber;
    this.context = context;
    this.span = span;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    if (span != null) {
      try (final TraceScope scope = AgentTracer.activateSpan(span)) {
        continuation = scope.capture();
      }
    }
    log.debug(
        "onSubscribe subscriber={} continuation={} subscription={}",
        this,
        continuation,
        subscription);
    subscriber.onSubscribe(subscription);
  }

  @Override
  public void onNext(final T o) {
    log.debug("onNext subscriber={} continuation={}", this, continuation);
    withActiveSpan(() -> subscriber.onNext(o));
  }

  @Override
  public void onError(final Throwable throwable) {
    log.debug("onError subscriber={} continuation={}", this, continuation);
    withActiveSpan(() -> subscriber.onError(throwable));
    if (continuation != null) {
      continuation.cancel();
      continuation = null;
    }
  }

  @Override
  public void onComplete() {
    log.debug("onComplete subscriber={} continuation={}", this, continuation);
    withActiveSpan(subscriber::onComplete);
    if (continuation != null) {
      continuation.cancel();
      continuation = null;
    }
  }

  @Override
  public Context currentContext() {
    return context;
  }

  private void withActiveSpan(final Runnable runnable) {
    if (span != null) {
      try (final TraceScope scope = AgentTracer.activateSpan(span)) {
        scope.setAsyncPropagation(true);
        runnable.run();
      }
    } else {
      runnable.run();
    }
  }
}
