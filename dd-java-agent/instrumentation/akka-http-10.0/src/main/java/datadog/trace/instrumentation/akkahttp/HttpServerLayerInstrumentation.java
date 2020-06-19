package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.DDSpanNames.AKKA_REQUEST;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.japi.function.Function;
import akka.stream.TLSProtocol;
import akka.stream.scaladsl.BidiFlow;
import akka.stream.scaladsl.Flow;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
public final class HttpServerLayerInstrumentation extends Instrumenter.Default {
  public HttpServerLayerInstrumentation() {
    super("akka-http", "akka-http-server");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("akka.http.scaladsl.HttpExt");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        named("serverLayer")
            .and(takesArgument(0, named("akka.http.scaladsl.settings.ServerSettings"))),
        HttpServerLayerInstrumentation.class.getName() + "$HttpServerLayerAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      HttpServerLayerInstrumentation.class.getName() + "$GraphWrappers",
      HttpServerLayerInstrumentation.class.getName() + "$HttpRequestWrapper",
      HttpServerLayerInstrumentation.class.getName() + "$HttpResponseWrapper",
      packageName + ".AkkaHttpServerHeaders",
      packageName + ".AkkaHttpServerDecorator",
    };
  }

  public static class HttpServerLayerAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void wrapReturn(
        @Advice.Return(readOnly = false)
            BidiFlow<
                    HttpResponse,
                    TLSProtocol.SslTlsOutbound,
                    TLSProtocol.SslTlsInbound,
                    HttpRequest,
                    NotUsed>
                serverLayer) {
      final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> testSpanFlow =
          BidiFlow.fromFlows(GraphWrappers.responseWrapper, GraphWrappers.requestWrapper);
      serverLayer = testSpanFlow.atop(serverLayer);
    }
  }

  public static final class GraphWrappers {
    public static final Flow<HttpRequest, HttpRequest, NotUsed> requestWrapper =
        akka.stream.javadsl.Flow.fromFunction(HttpRequestWrapper.INSTANCE).asScala();
    public static final Flow<HttpResponse, HttpResponse, NotUsed> responseWrapper =
        akka.stream.javadsl.Flow.fromFunction(HttpResponseWrapper.INSTANCE).asScala();
  }

  public static final class HttpRequestWrapper implements Function<HttpRequest, HttpRequest> {
    public static final HttpRequestWrapper INSTANCE = new HttpRequestWrapper();

    @Override
    public HttpRequest apply(final HttpRequest request) throws Exception, Exception {
      final AgentSpan.Context extractedContext = propagate().extract(request, GETTER);
      final AgentSpan span = startSpan(AKKA_REQUEST, extractedContext);
      span.setTag(DD_MEASURED, true);

      DECORATE.afterStart(span);
      DECORATE.onConnection(span, request);
      DECORATE.onRequest(span, request);

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      return request;
    }
  }

  public static final class HttpResponseWrapper implements Function<HttpResponse, HttpResponse> {
    public static final HttpResponseWrapper INSTANCE = new HttpResponseWrapper();

    @Override
    public HttpResponse apply(final HttpResponse response) throws Exception, Exception {
      final AgentSpan span = activeSpan();
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);

      final TraceScope scope = activeScope();
      if (scope != null) {
        scope.setAsyncPropagation(false);
        scope.close();
      }
      span.finish();
      return response;
    }
  }
}
