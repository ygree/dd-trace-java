import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.TLSProtocol;
import akka.stream.scaladsl.BidiFlow;
import akka.stream.scaladsl.Flow;
import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.HttpServerTestAdvice;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer.ForAdvice;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AkkaHttpTestInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named("akka.http.scaladsl.HttpExt"))
        .transform(
            new ForAdvice()
                .advice(
                    named("serverLayer")
                        .and(takesArgument(0, named("akka.http.scaladsl.settings.ServerSettings"))),
                    AkkaServerTestAdvice.class.getName()));
  }

  public static class AkkaServerTestAdvice {
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
          BidiFlow.fromFlows(TestGraph.responseWrapper, TestGraph.requestWrapper);
      serverLayer = testSpanFlow.atop(serverLayer);
    }
  }

  public static class TestGraph {
    public static final Flow<HttpRequest, HttpRequest, NotUsed> requestWrapper =
        akka.stream.javadsl.Flow.fromFunction(
                (HttpRequest in) -> {
                  try (final TraceScope scope =
                      HttpServerTestAdvice.ServerEntryAdvice.methodEnter()) {
                    return in;
                  }
                })
            .asScala();
    public static final Flow<HttpResponse, HttpResponse, NotUsed> responseWrapper =
        akka.stream.javadsl.Flow.fromFunction(
                (HttpResponse in) -> {
                  if (activeSpan() != null) {
                    activeSpan().finish();
                  }
                  return in;
                })
            .asScala();
  }
}
