import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.named;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.TLSProtocol;
import akka.stream.scaladsl.BidiFlow;
import akka.stream.scaladsl.Flow;
import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.HttpServerTestAdvice;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer.ForAdvice;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AkkaHttpTestInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named("akka.http.impl.engine.server.HttpServerBluePrint$"))
        .transform(new ForAdvice().advice(named("apply"), AkkaServerTestAdvice.class.getName()));
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
      serverLayer = TestGraph.bidiFlowWrapper.atop(serverLayer);
    }
  }

  public static class TestGraph {
    public static final Flow<HttpRequest, HttpRequest, NotUsed> requestWrapper =
        akka.stream.javadsl.Flow.fromFunction(TestGraph::handleRequest).asScala();

    public static final Flow<HttpResponse, HttpResponse, NotUsed> responseWrapper =
        akka.stream.javadsl.Flow.fromFunction(TestGraph::handleResponse).asScala();

    public static final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed>
        bidiFlowWrapper = BidiFlow.fromFlows(responseWrapper, requestWrapper);

    private static final Queue<AgentSpan> spans = new ConcurrentLinkedDeque<>();

    static HttpRequest handleRequest(final HttpRequest request) {
      HttpServerTestAdvice.ServerEntryAdvice.methodEnter();
      spans.add(activeSpan());
      return request;
    }

    static HttpResponse handleResponse(final HttpResponse response) {
      final AgentSpan span = spans.poll();
      if (span != null) {
        final AgentScope scope = activateSpan(span);
        HttpServerTestAdvice.ServerEntryAdvice.methodExit(scope);
      }
      return response;
    }
  }
}
