package datadog.trace.instrumentation.akkahttp;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.Attributes;
import akka.stream.BidiShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.Shape;
import akka.stream.scaladsl.BidiFlow;
import akka.stream.scaladsl.Flow;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
public class HttpServerHandlerInstrumentation extends Instrumenter.Default {
  public HttpServerHandlerInstrumentation() {
    super("akka-http", "akka-http-server");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("akka.http.scaladsl.HttpExt");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        named("bindAndHandle").and(takesArgument(0, named("akka.stream.scaladsl.Flow"))),
        HttpServerHandlerInstrumentation.class.getName() + "$HttpServerHandlerAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      HttpServerHandlerInstrumentation.class.getName() + "$HandlerFlowWrapper",
      HttpServerHandlerInstrumentation.class.getName() + "$HandlerFlowWrapper$HandlerGraphStage",
      HttpServerHandlerInstrumentation.class.getName()
          + "$HandlerFlowWrapper$HandlerGraphStageLogic",
      HttpServerHandlerInstrumentation.class.getName() + "$HandlerFlowWrapper$RequestInHandler",
      HttpServerHandlerInstrumentation.class.getName() + "$HandlerFlowWrapper$RequestOutHandler",
      HttpServerHandlerInstrumentation.class.getName() + "$HandlerFlowWrapper$ResponseInHandler",
      HttpServerHandlerInstrumentation.class.getName() + "$HandlerFlowWrapper$ResponseOutHandler",
      packageName + ".AkkaHttpServerHeaders",
      packageName + ".AkkaHttpServerDecorator",
    };
  }

  public static class HttpServerHandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.Argument(value = 0, readOnly = false)
            Flow<HttpRequest, HttpResponse, NotUsed> handler) {
      handler = BidiFlow.fromGraph(HandlerFlowWrapper.create()).join(handler);
    }
  }

  public static class HandlerFlowWrapper {
    public static GraphStage<BidiShape<HttpRequest, HttpRequest, HttpResponse, HttpResponse>>
        create() {
      return new HandlerGraphStage();
    }

    public static class HandlerGraphStage
        extends GraphStage<BidiShape<HttpRequest, HttpRequest, HttpResponse, HttpResponse>> {
      private final Inlet<HttpRequest> requestIn = Inlet.create("dd.request.in");
      private final Outlet<HttpRequest> requestOut = Outlet.create("dd.request.out");
      private final Inlet<HttpResponse> responseIn = Inlet.create("dd.request.in");
      private final Outlet<HttpResponse> responseOut = Outlet.create("dd.request.out");
      private final BidiShape<HttpRequest, HttpRequest, HttpResponse, HttpResponse> shape =
          new BidiShape<>(requestIn, requestOut, responseIn, responseOut);

      @Override
      public GraphStageLogic createLogic(final Attributes inheritedAttributes) throws Exception {
        return new HandlerGraphStageLogic(shape, requestIn, requestOut, responseIn, responseOut);
      }

      @Override
      public BidiShape<HttpRequest, HttpRequest, HttpResponse, HttpResponse> shape() {
        return shape;
      }
    }

    public static class HandlerGraphStageLogic extends GraphStageLogic {
      private final Queue<AgentSpan> pendingSpans = new ConcurrentLinkedQueue<>();

      public HandlerGraphStageLogic(
          final Shape shape,
          final Inlet<HttpRequest> requestIn,
          final Outlet<HttpRequest> requestOut,
          final Inlet<HttpResponse> responseIn,
          final Outlet<HttpResponse> responseOut) {
        super(shape);

        setHandler(requestIn, new RequestInHandler(this, requestIn, requestOut, pendingSpans));

        setHandler(requestOut, new RequestOutHandler(this, requestIn));

        setHandler(responseIn, new ResponseInHandler(this, responseIn, responseOut, pendingSpans));

        setHandler(responseOut, new ResponseOutHandler(this, responseIn));
      }
    }

    public static class RequestInHandler extends AbstractInHandler {
      private final GraphStageLogic graphStageLogic;
      private final Inlet<HttpRequest> requestIn;
      private final Outlet<HttpRequest> requestOut;
      private final Queue<AgentSpan> pendingSpans;

      public RequestInHandler(
          final GraphStageLogic graphStageLogic,
          final Inlet<HttpRequest> requestIn,
          final Outlet<HttpRequest> requestOut,
          final Queue<AgentSpan> pendingSpans) {
        this.graphStageLogic = graphStageLogic;
        this.requestIn = requestIn;
        this.requestOut = requestOut;
        this.pendingSpans = pendingSpans;
      }

      @Override
      public void onPush() throws Exception {
        final HttpRequest request = graphStageLogic.grab(requestIn);
        pendingSpans.add(AgentTracer.activeSpan());
        graphStageLogic.push(requestOut, request);
      }

      @Override
      public void onUpstreamFinish() throws Exception {
        graphStageLogic.complete(requestOut);
      }
    }

    public static class RequestOutHandler extends AbstractOutHandler {
      private final GraphStageLogic graphStageLogic;
      private final Inlet<HttpRequest> requestIn;

      public RequestOutHandler(
          final GraphStageLogic graphStageLogic, final Inlet<HttpRequest> requestIn) {
        this.graphStageLogic = graphStageLogic;
        this.requestIn = requestIn;
      }

      @Override
      public void onPull() throws Exception {
        graphStageLogic.pull(requestIn);
      }

      @Override
      public void onDownstreamFinish() throws Exception {
        graphStageLogic.cancel(requestIn);
      }
    }

    public static class ResponseInHandler extends AbstractInHandler {
      private final GraphStageLogic graphStageLogic;
      private final Inlet<HttpResponse> responseIn;
      private final Outlet<HttpResponse> responseOut;
      private final Queue<AgentSpan> pendingSpans;

      public ResponseInHandler(
          final GraphStageLogic graphStageLogic,
          final Inlet<HttpResponse> responseIn,
          final Outlet<HttpResponse> responseOut,
          final Queue<AgentSpan> pendingSpans) {
        this.graphStageLogic = graphStageLogic;
        this.responseIn = responseIn;
        this.responseOut = responseOut;
        this.pendingSpans = pendingSpans;
      }

      @Override
      public void onPush() throws Exception {
        final HttpResponse response = graphStageLogic.grab(responseIn);
        final AgentSpan span = pendingSpans.poll();

        try (final AgentScope scope = AgentTracer.activateSpan(span)) {
          graphStageLogic.push(responseOut, response);
        }
      }

      @Override
      public void onUpstreamFinish() throws Exception {
        graphStageLogic.completeStage();
      }
    }

    public static class ResponseOutHandler extends AbstractOutHandler {
      private final GraphStageLogic graphStageLogic;
      private final Inlet<HttpResponse> responseIn;

      public ResponseOutHandler(
          final GraphStageLogic graphStageLogic, final Inlet<HttpResponse> responseIn) {
        this.graphStageLogic = graphStageLogic;
        this.responseIn = responseIn;
      }

      @Override
      public void onPull() throws Exception {
        graphStageLogic.pull(responseIn);
      }

      @Override
      public void onDownstreamFinish() throws Exception {
        graphStageLogic.cancel(responseIn);
      }
    }
  }
}
