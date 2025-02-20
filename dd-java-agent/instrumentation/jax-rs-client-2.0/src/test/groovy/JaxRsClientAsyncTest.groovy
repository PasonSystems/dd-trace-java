import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.instrumentation.jaxrs.JaxRsClientDecorator
import org.apache.cxf.jaxrs.client.spec.ClientBuilderImpl
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.client.ClientProperties
import org.glassfish.jersey.client.JerseyClientBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import spock.lang.Timeout

import javax.ws.rs.client.AsyncInvoker
import javax.ws.rs.client.Client
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity
import javax.ws.rs.client.InvocationCallback
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class JaxRsClientAsyncTest extends HttpClientTest {
  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(CheckpointValidationMode.INTERVALS)
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    Client client = builder().build()
    WebTarget service = client.target(uri)
    def builder = service.request(MediaType.TEXT_PLAIN)
    headers.each { builder.header(it.key, it.value) }
    AsyncInvoker request = builder.async()

    def latch = new CountDownLatch(1)
    def reqBody = BODY_METHODS.contains(method) ? Entity.text(body) : null
    Response response = request.method(method, (Entity) reqBody, new InvocationCallback<Response>() {
        @Override
        void completed(Response s) {
          callback?.call()
          latch.countDown()
        }

        @Override
        void failed(Throwable throwable) {
        }
      }).get()

    latch.await()
    return response.status
  }

  @Override
  CharSequence component() {
    return JaxRsClientDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "jax-rs.client.call"
  }

  abstract ClientBuilder builder()
}

@Timeout(5)
class JerseyClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder() {
    ClientConfig config = new ClientConfig()
    config.property(ClientProperties.CONNECT_TIMEOUT, CONNECT_TIMEOUT_MS)
    config.property(ClientProperties.READ_TIMEOUT, READ_TIMEOUT_MS)
    return new JerseyClientBuilder().withConfig(config)
  }

  boolean testCircularRedirects() {
    false
  }
}

@Timeout(5)
class ResteasyClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder() {
    return new ResteasyClientBuilder()
      .establishConnectionTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .socketTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  }

  boolean testRedirects() {
    false
  }
}

@Timeout(5)
class CxfClientAsyncTest extends JaxRsClientAsyncTest {

  @Override
  ClientBuilder builder() {
    return new ClientBuilderImpl()
  }

  boolean testRedirects() {
    false
  }

  boolean testConnectionFailure() {
    false
  }

  boolean testRemoteConnection() {
    // FIXME: span not reported correctly.
    false
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}
