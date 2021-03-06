package datadog.smoketest.springboot.grpc;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Responder extends GreeterGrpc.GreeterImplBase {

  @Override
  public void hello(Request request, StreamObserver<Response> responseObserver) {
    responseObserver.onNext(Response.newBuilder().setMessage("bye").build());
    responseObserver.onCompleted();
  }
}
