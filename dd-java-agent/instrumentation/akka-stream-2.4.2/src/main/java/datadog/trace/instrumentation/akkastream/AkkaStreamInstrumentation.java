package datadog.trace.instrumentation.akkastream;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
public final class AkkaStreamInstrumentation extends Instrumenter.Default {

  public AkkaStreamInstrumentation() {
    super("akka-stream");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return null;
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return null;
  }
}
