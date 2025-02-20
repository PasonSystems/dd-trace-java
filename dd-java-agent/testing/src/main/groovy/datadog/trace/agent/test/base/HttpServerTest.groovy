package datadog.trace.agent.test.base

import ch.qos.logback.classic.Level
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.api.function.BiFunction
import datadog.trace.api.function.Supplier
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.bootstrap.instrumentation.decorator.http.SimplePathNormalizer
import groovy.transform.CompileStatic
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Unroll

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ASYNC_TIMEOUT_ERROR
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import static org.junit.Assume.assumeTrue

@Unroll
abstract class HttpServerTest<SERVER> extends WithHttpServer<SERVER> {

  public static final Logger SERVER_LOGGER = LoggerFactory.getLogger("http-server")
  static {
    ((ch.qos.logback.classic.Logger) SERVER_LOGGER).setLevel(Level.DEBUG)
  }

  @CompileStatic
  def setupSpec() {
    // Register the Instrumentation Gateway callbacks
    def ig = get().instrumentationGateway()
    def callbacks = new IGCallbacks()
    Events<IGCallbacks.Context> events = Events.get()
    ig.registerCallback(events.requestStarted(), callbacks.requestStartedCb)
    ig.registerCallback(events.requestHeader(), callbacks.requestHeaderCb)
    ig.registerCallback(events.requestMethodUriRaw(), callbacks.requestUriRawCb)
    ig.registerCallback(events.requestBodyStart(), callbacks.requestBodyStartCb)
    ig.registerCallback(events.requestBodyDone(), callbacks.requestBodyEndCb)
  }

  @Shared
  String component = component()

  abstract String component()

  String expectedServiceName() {
    CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
  }

  abstract String expectedOperationName()

  String expectedResourceName(ServerEndpoint endpoint, String method, URI address) {
    if (endpoint.status == 404 && (changesAll404s() || endpoint.path == "/not-found")) {
      return "404"
    } else if (endpoint.hasPathParam) {
      return "$method ${testPathParam()}"
    }
    def encoded = !hasDecodedResource()
    def path = encoded ? endpoint.resolve(address).rawPath : endpoint.resolve(address).path
    return "$method ${new SimplePathNormalizer().normalize(path, encoded)}"
  }

  String expectedUrl(ServerEndpoint endpoint, URI address) {
    URI url = endpoint.resolve(address)
    def path = Config.get().isHttpServerRawResource() && supportsRaw() ? url.rawPath : url.path
    return URIUtils.buildURL(url.scheme, url.host, url.port, path)
  }

  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    null
  }

  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    Collections.emptyMap()
  }

  // Only used if hasExtraErrorInformation is true
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.errored) {
      ["error.msg"  : { it == null || it == EXCEPTION.body },
        "error.type" : { it == null || it == Exception.name },
        "error.stack": { it == null || it instanceof String }]
    } else {
      Collections.emptyMap()
    }
  }

  boolean expectedErrored(ServerEndpoint endpoint) {
    endpoint.errored
  }

  Serializable expectedStatus(ServerEndpoint endpoint) {
    endpoint.status
  }

  String expectedQueryTag(ServerEndpoint endpoint) {
    def encoded = Config.get().isHttpServerRawQueryString() && supportsRaw()
    def query = encoded ? endpoint.rawQuery : endpoint.query
    null != query && encoded && hasPlusEncodedSpaces() ? query.replaceAll('%20', "+") : query
  }

  boolean hasHandlerSpan() {
    false
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    false
  }

  boolean bubblesResponse() {
    // Some things like javax.servlet.RequestDispatcher.include() don't bubble headers or response codes to the
    // parent request.  This is specified in the spec
    true
  }

  boolean hasDecodedResource() {
    !Config.get().isHttpServerRawResource() || !supportsRaw()
  }

  boolean hasPeerInformation() {
    true
  }

  boolean hasPeerPort() {
    true
  }

  boolean hasForwardedIP() {
    true
  }

  boolean hasExtraErrorInformation() {
    false
  }

  boolean changesAll404s() {
    false
  }

  boolean supportsRaw() {
    true
  }

  /** Does the raw query string contain plus encoded spaces? */
  boolean hasPlusEncodedSpaces() {
    false
  }

  int spanCount(ServerEndpoint endpoint) {
    return 2 + (hasHandlerSpan() ? 1 : 0) + (hasResponseSpan(endpoint) ? 1 : 0)
  }

  boolean redirectHasBody() {
    false
  }

  boolean testForwarded() {
    true
  }

  boolean testNotFound() {
    true
  }

  boolean testRedirect() {
    true
  }

  boolean testExceptionBody() {
    true
  }

  boolean testException() {
    true
  }

  boolean testTimeout() {
    false
  }

  boolean testRequestBody() {
    false
  }

  /** Tomcat 5.5 can't seem to handle the encoded URIs */
  boolean testEncodedPath() {
    true
  }

  /** Return the expected path parameter */
  String testPathParam() {
    null
  }

  enum ServerEndpoint {
    SUCCESS("success", 200, "success"),
    CREATED("created", 201, "created"),
    REDIRECT("redirect", 302, "/redirected"),
    FORWARDED("forwarded", 200, "1.2.3.4"),
    ERROR("error-status", 500, "controller error"), // "error" is a special path for some frameworks
    EXCEPTION("exception", 500, "controller exception"),
    CUSTOM_EXCEPTION("custom-exception", 510, "custom exception"), // exception thrown with custom error
    NOT_FOUND("not-found", 404, "not found"),
    NOT_HERE("not-here", 404, "not here"), // Explicitly returned 404 from a valid controller

    TIMEOUT("timeout", 500, null),
    TIMEOUT_ERROR("timeout_error", 500, null),

    QUERY_PARAM("query?some=query", 200, "some=query"),
    QUERY_ENCODED_BOTH("encoded%20path%20query?some=is%20both", 200, "some=is both"),
    QUERY_ENCODED_QUERY("encoded_query?some=is%20query", 200, "some=is query"),
    // TODO: add tests for the following cases:
    // OkHttp never sends the fragment in the request, so these cases don't work.
    //    FRAGMENT_PARAM("fragment#some-fragment", 200, "some-fragment"),
    //    QUERY_FRAGMENT_PARAM("query/fragment?some=query#some-fragment", 200, "some=query#some-fragment"),
    PATH_PARAM("path/123/param", 200, "123"),
    AUTH_REQUIRED("authRequired", 200, null),
    LOGIN("login", 302, null),
    UNKNOWN("", 451, null), // This needs to have a valid status code

    private final String path
    private final String rawPath
    final String query
    final String rawQuery
    final String fragment
    final int status
    final String body
    final Boolean errored
    final Boolean throwsException
    final boolean hasPathParam

    ServerEndpoint(String uri, int status, String body) {
      def uriObj = URI.create(uri)
      this.path = uriObj.path
      this.rawPath = uriObj.rawPath
      this.query = uriObj.query
      this.rawQuery = uriObj.rawQuery
      this.fragment = uriObj.fragment
      this.status = status
      this.body = body
      this.errored = status >= 500 || name().contains("ERROR")
      this.throwsException = name().contains("EXCEPTION")
      this.hasPathParam = body == "123"
    }

    String getPath() {
      return "/$path"
    }

    String relativePath() {
      return path
    }

    String getRawPath() {
      return "/$rawPath"
    }

    String relativeRawPath() {
      return rawPath
    }

    URI resolve(URI address) {
      // must be relative path to allow for servlet context
      return address.resolve(relativeRawPath())
    }

    String bodyForQuery(String queryString) {
      if (queryString.equals(query) || queryString.equals(rawQuery)) {
        return body
      }
      return "non matching query string '$queryString'"
    }

    static {
      assert values().length == values().collect { it.path }.toSet().size(): "paths should be unique"
    }

    private static final Map<String, ServerEndpoint> PATH_MAP = {
      Map<String, ServerEndpoint> map = values().collectEntries { [it.path, it]}
      map.putAll(values().collectEntries { [it.rawPath, it]})
      map
    }.call()

    // Will match both decoded and encoded path
    static ServerEndpoint forPath(String path) {
      def endpoint = PATH_MAP.get(path)
      return endpoint != null ? endpoint : UNKNOWN
    }
  }

  Request.Builder request(ServerEndpoint uri, String method, RequestBody body) {
    def url = HttpUrl.get(uri.resolve(address)).newBuilder()
      .encodedQuery(uri.rawQuery)
      .fragment(uri.fragment)
      .build()
    return new Request.Builder()
      .url(url)
      .method(method, body)
  }

  static <T> T controller(ServerEndpoint endpoint, Closure<T> closure) {
    assert activeSpan() != null: "Controller should have a parent span."
    assert activeSpan() != noopSpan(): "Parent span shouldn't be noopSpan"
    assert activeScope().asyncPropagating: "Scope should be propagating async."
    if (endpoint == NOT_FOUND || endpoint == UNKNOWN) {
      return closure()
    }
    return runUnderTrace("controller", closure)
  }

  def "test success with #count requests"() {
    setup:
    def request = request(SUCCESS, method, body).build()
    List<Response> responses = (1..count).collect {
      return client.newCall(request).execute()
    }

    expect:
    responses.each { response ->
      assert response.code() == SUCCESS.status
      assert response.body().string() == SUCCESS.body
    }

    and:
    assertTraces(count) {
      (1..count).eachWithIndex { val, i ->
        trace(spanCount(SUCCESS)) {
          sortSpansByStart()
          serverSpan(it)
          if (hasHandlerSpan()) {
            handlerSpan(it)
          }
          controllerSpan(it)
          if (hasResponseSpan(SUCCESS)) {
            responseSpan(it, SUCCESS)
          }
        }
      }
    }

    where:
    method = "GET"
    body = null
    count << [1, 4, 50] // make multiple requests.
  }

  def "test forwarded request"() {
    setup:
    assumeTrue(testForwarded())
    def request = request(FORWARDED, method, body).header("x-forwarded-for", FORWARDED.body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == FORWARDED.status
    response.body().string() == FORWARDED.body

    and:
    assertTraces(1) {
      trace(spanCount(FORWARDED)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, FORWARDED)
        if (hasHandlerSpan()) {
          handlerSpan(it, FORWARDED)
        }
        controllerSpan(it)
        if (hasResponseSpan(FORWARDED)) {
          responseSpan(it, FORWARDED)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test success with parent"() {
    setup:
    def traceId = 123G
    def parentId = 456G
    def request = request(SUCCESS, method, body)
      .header("x-datadog-trace-id", traceId.toString())
      .header("x-datadog-parent-id", parentId.toString())
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == SUCCESS.status
    response.body().string() == SUCCESS.body

    and:
    assertTraces(1) {
      trace(spanCount(SUCCESS)) {
        sortSpansByStart()
        serverSpan(it, traceId, parentId, method)
        if (hasHandlerSpan()) {
          handlerSpan(it)
        }
        controllerSpan(it)
        if (hasResponseSpan(SUCCESS)) {
          responseSpan(it, SUCCESS)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test tag query string for #endpoint rawQuery=#rawQuery"() {
    setup:
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    injectSysConfig(HTTP_SERVER_RAW_QUERY_STRING, "$rawQuery")
    def request = request(endpoint, method, body).build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == endpoint.status
    response.body().string() == endpoint.body

    and:
    assertTraces(1) {
      trace(spanCount(endpoint)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, endpoint)
        if (hasHandlerSpan()) {
          handlerSpan(it, endpoint)
        }
        controllerSpan(it)
        if (hasResponseSpan(endpoint)) {
          responseSpan(it, endpoint)
        }
      }
    }

    where:
    rawQuery | endpoint
    true     | SUCCESS
    true     | QUERY_PARAM
    true     | QUERY_ENCODED_QUERY
    false    | SUCCESS
    false    | QUERY_PARAM
    false    | QUERY_ENCODED_QUERY

    method = "GET"
    body = null
  }

  def "test encoded path for #endpoint rawQuery=#rawQuery rawResource=#rawResource"() {
    setup:
    assumeTrue(testEncodedPath())
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    injectSysConfig(HTTP_SERVER_RAW_QUERY_STRING, "$rawQuery")
    injectSysConfig(HTTP_SERVER_RAW_RESOURCE, "$rawResource")
    def request = request(endpoint, method, body).build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == endpoint.status
    response.body().string() == endpoint.body

    and:
    assertTraces(1) {
      trace(spanCount(endpoint)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, endpoint)
        if (hasHandlerSpan()) {
          handlerSpan(it, endpoint)
        }
        controllerSpan(it)
        if (hasResponseSpan(endpoint)) {
          responseSpan(it, endpoint)
        }
      }
    }

    where:
    rawQuery | rawResource | endpoint
    true     | true        | QUERY_ENCODED_BOTH
    true     | false       | QUERY_ENCODED_BOTH
    false    | true        | QUERY_ENCODED_BOTH
    false    | false       | QUERY_ENCODED_BOTH

    method = "GET"
    body = null
  }

  def "test path param"() {
    setup:
    assumeTrue(testPathParam() != null)
    def request = request(PATH_PARAM, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == PATH_PARAM.status
    response.body().string() == PATH_PARAM.body

    and:
    assertTraces(1) {
      trace(spanCount(PATH_PARAM)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, PATH_PARAM)
        if (hasHandlerSpan()) {
          handlerSpan(it, PATH_PARAM)
        }
        controllerSpan(it)
        if (hasResponseSpan(PATH_PARAM)) {
          responseSpan(it, PATH_PARAM)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test success with multiple header attached parent"() {
    setup:
    def traceId = 123G
    def parentId = 456G
    def request = request(SUCCESS, method, body)
      .header("x-datadog-trace-id", traceId.toString() + ", " + traceId.toString())
      .header("x-datadog-parent-id", parentId.toString() + ", " + parentId.toString())
      .header("x-datadog-sampling-priority", "1, 1")
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == SUCCESS.status
    response.body().string() == SUCCESS.body

    and:
    assertTraces(1) {
      trace(spanCount(SUCCESS)) {
        sortSpansByStart()
        serverSpan(it, traceId, parentId)
        if (hasHandlerSpan()) {
          handlerSpan(it)
        }
        controllerSpan(it)
        if (hasResponseSpan(SUCCESS)) {
          responseSpan(it, SUCCESS)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test redirect"() {
    setup:
    assumeTrue(testRedirect())
    def request = request(REDIRECT, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    if (bubblesResponse()) {
      assert response.code() == REDIRECT.status
      assert response.header("location") == REDIRECT.body ||
      response.header("location") == "${address.resolve(REDIRECT.body)}"
    }

    response.body().contentLength() < 1 || redirectHasBody()

    and:
    assertTraces(1) {
      trace(spanCount(REDIRECT)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, REDIRECT)
        if (hasHandlerSpan()) {
          handlerSpan(it, REDIRECT)
        }
        controllerSpan(it)
        if (hasResponseSpan(REDIRECT)) {
          responseSpan(it, REDIRECT)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test error"() {
    setup:
    def request = request(ERROR, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    if (bubblesResponse()) {
      assert response.body().string() == ERROR.body
      assert response.code() == ERROR.status
    }

    and:
    assertTraces(1) {
      trace(spanCount(ERROR)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, ERROR)
        if (hasHandlerSpan()) {
          handlerSpan(it, ERROR)
        }
        controllerSpan(it)
        if (hasResponseSpan(ERROR)) {
          responseSpan(it, ERROR)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test exception"() {
    setup:
    assumeTrue(testException())
    def request = request(EXCEPTION, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == EXCEPTION.status
    if (testExceptionBody()) {
      assert response.body().string() == EXCEPTION.body
    }

    and:
    assertTraces(1) {
      trace(spanCount(EXCEPTION)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, EXCEPTION)
        if (hasHandlerSpan()) {
          handlerSpan(it, EXCEPTION)
        }
        controllerSpan(it, EXCEPTION)
        if (hasResponseSpan(EXCEPTION)) {
          responseSpan(it, EXCEPTION)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test notFound"() {
    setup:
    assumeTrue(testNotFound())
    def request = request(NOT_FOUND, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == NOT_FOUND.status

    and:
    assertTraces(1) {
      trace(spanCount(NOT_FOUND) - 1) {
        // no controller span
        sortSpansByStart()
        serverSpan(it, null, null, method, NOT_FOUND)
        if (hasHandlerSpan()) {
          handlerSpan(it, NOT_FOUND)
        }
        if (hasResponseSpan(NOT_FOUND)) {
          responseSpan(it, NOT_FOUND)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test timeout"() {
    setup:
    assumeTrue(testTimeout())
    injectSysConfig(SERVLET_ASYNC_TIMEOUT_ERROR, "false")
    def request = request(TIMEOUT, method, body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 500
    response.body().string() == ""
    response.body().contentLength() == 0

    and:
    assertTraces(1) {
      trace(spanCount(TIMEOUT)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, TIMEOUT)
        if (hasHandlerSpan()) {
          handlerSpan(it, TIMEOUT)
        }
        controllerSpan(it)
        if (hasResponseSpan(TIMEOUT)) {
          responseSpan(it, TIMEOUT)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test timeout as error"() {
    setup:
    assumeTrue(testTimeout())
    def request = request(TIMEOUT_ERROR, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    if (bubblesResponse()) {
      assert response.code() == 500
    }
    response.body().string() == ""
    response.body().contentLength() == 0

    and:
    assertTraces(1) {
      trace(spanCount(TIMEOUT_ERROR)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, TIMEOUT_ERROR)
        if (hasHandlerSpan()) {
          handlerSpan(it, TIMEOUT_ERROR)
        }
        controllerSpan(it)
        if (hasResponseSpan(TIMEOUT_ERROR)) {
          responseSpan(it, TIMEOUT_ERROR)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  //FIXME: add tests for POST with large/chunked data

  def "test instrumentation gateway callbacks for #endpoint with #header = #value"() {
    setup:
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    def request = request(endpoint, "GET",  null).header(header, value).build()
    def traces = extraSpan ? 2 : 1

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == endpoint.status
    response.body().string() == endpoint.body

    and:
    assertTraces(traces) {
      trace(spanCount(endpoint)) {
        sortSpansByStart()
        serverSpan(it, null, null, "GET", endpoint)
        if (hasHandlerSpan()) {
          handlerSpan(it, endpoint)
        }
        controllerSpan(it)
        if (hasResponseSpan(endpoint)) {
          responseSpan(it, endpoint)
        }
      }
      if (extraSpan) {
        trace(1) {
          basicSpan(it, "$header-$value")
        }
      }
    }

    where:
    endpoint            | header              | value       | extraSpan
    QUERY_ENCODED_BOTH  | IG_TEST_HEADER      | "something" | true
    QUERY_ENCODED_BOTH  | "x-ignored"         | "something" | false
    SUCCESS             | IG_TEST_HEADER      | "whatever"  | false
    QUERY_ENCODED_QUERY | IG_TEST_HEADER      | "whatever"  | false
    QUERY_PARAM         | IG_TEST_HEADER      | "whatever"  | false
  }

  def 'test instrumentation gateway request body interception'() {
    setup:
    assumeTrue(testRequestBody())
    def request = request(
      CREATED, 'POST',
      RequestBody.create(MediaType.get('text/plain'), 'my body'))
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().charStream().text == 'created: my body'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body') == 'my body'
    }
  }

  void controllerSpan(TraceAssert trace, ServerEndpoint endpoint = null) {
    def exception = endpoint == CUSTOM_EXCEPTION ? expectedCustomExceptionType() : expectedExceptionType()
    def errorMessage = endpoint?.body
    trace.span {
      serviceName expectedServiceName()
      operationName "controller"
      resourceName "controller"
      errored errorMessage != null
      childOfPrevious()
      tags {
        if (errorMessage) {
          errorTags(exception, errorMessage)
        }
        defaultTags()
      }
    }
  }

  Class<? extends Exception> expectedExceptionType() {
    return Exception
  }

  Class<? extends Exception> expectedCustomExceptionType() {
    return InputMismatchException
  }

  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    throw new UnsupportedOperationException("handlerSpan not implemented in " + getClass().name)
  }

  void responseSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    throw new UnsupportedOperationException("responseSpan not implemented in " + getClass().name)
  }

  // If you ever feel the need to make this method non final and override it with something that is almost the
  // same, but has a slightly different behavior, then please think again, and see if you can't make that part
  // of the integrations very special behavior into something configurable here instead.
  final void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    Object expectedServerSpanRoute = expectedServerSpanRoute(endpoint)
    Map<String, Serializable> expectedExtraErrorInformation = hasExtraErrorInformation() ? expectedExtraErrorInformation(endpoint) : null
    boolean hasPeerInformation = hasPeerInformation()
    boolean hasPeerPort = hasPeerPort()
    boolean hasForwardedIP = hasForwardedIP()
    def expectedExtraServerTags = expectedExtraServerTags(endpoint)
    def expectedStatus = expectedStatus(endpoint)
    def expectedQueryTag = expectedQueryTag(endpoint)
    def expectedUrl = expectedUrl(endpoint, address)
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName expectedResourceName(endpoint, method, address)
      spanType DDSpanTypes.HTTP_SERVER
      errored expectedErrored(endpoint)
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        if (hasPeerInformation) {
          if (hasPeerPort) {
            "$Tags.PEER_PORT" Integer
          }
          "$Tags.PEER_HOST_IPV4" { it == "127.0.0.1" || (endpoint == FORWARDED && it == endpoint.body) }
        }
        "$Tags.HTTP_HOSTNAME" address.host
        "$Tags.HTTP_URL" "$expectedUrl"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" expectedStatus
        if (endpoint == FORWARDED && hasForwardedIP) {
          "$Tags.HTTP_FORWARDED_IP" endpoint.body
        }
        if (null != expectedServerSpanRoute) {
          "$Tags.HTTP_ROUTE" expectedServerSpanRoute
        }
        if (null != expectedExtraErrorInformation) {
          addTags(expectedExtraErrorInformation)
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" expectedQueryTag
        }
        // OkHttp never sends the fragment in the request.
        //        if (endpoint.fragment) {
        //          "$DDTags.HTTP_FRAGMENT" endpoint.fragment
        //        }
        defaultTags(true)
        addTags(expectedExtraServerTags)
      }
    }
  }

  static final String IG_TEST_HEADER = "x-ig-test-header"

  class IGCallbacks {
    static class Context {
      String extraSpan
      StoredBodySupplier requestBodySupplier
    }

    final Supplier<Flow<Context>> requestStartedCb =
    ({
      ->
      new Flow.ResultFlow<Context>(new Context())
    } as Supplier<Flow<Context>>)

    final TriConsumer<RequestContext<Context>, String, String> requestHeaderCb =
    { RequestContext<Context> rqCtxt, String key, String value ->
      def context = rqCtxt.data
      if (IG_TEST_HEADER.equalsIgnoreCase(key)) {
        context.extraSpan = value
      }
    } as TriConsumer<RequestContext<Context>, String, String>

    private static final String EXPECTED = "${QUERY_ENCODED_BOTH.rawPath}?${QUERY_ENCODED_BOTH.rawQuery}"

    final TriFunction<RequestContext<Context>, String, URIDataAdapter, Flow<Void>> requestUriRawCb =
    ({ RequestContext<Context> rqCtxt, String method, URIDataAdapter uri ->
      def context = rqCtxt.data
      String raw = uri.supportsRaw() ? uri.raw() : ''
      raw = uri.hasPlusEncodedSpaces() ? raw.replace('+', '%20') : raw
      // Only trigger for query path without query parameters and with special header
      if (raw.endsWith(EXPECTED) && context.extraSpan) {
        runUnderTrace("$IG_TEST_HEADER-${context.extraSpan}", false) {}
        // Only do this for the first time since some instrumentations with handler spans may call
        // DECORATE.onRequest multiple times
        context.extraSpan = null
      }
      Flow.ResultFlow.empty()
    } as TriFunction<RequestContext<Context>, String, URIDataAdapter, Flow<Void>>)

    final BiFunction<RequestContext<Context>, StoredBodySupplier, Void> requestBodyStartCb =
    { RequestContext<Context> rqCtxt, StoredBodySupplier supplier ->
      def context = rqCtxt.data
      context.requestBodySupplier = supplier
      null
    } as BiFunction<RequestContext<Context>, StoredBodySupplier, Void>

    final BiFunction<RequestContext<Context>, StoredBodySupplier, Flow<Void>> requestBodyEndCb =
    { RequestContext<Context> rqCtxt, StoredBodySupplier supplier ->
      def context = rqCtxt.data
      if (!context.requestBodySupplier.is(supplier)) {
        throw new RuntimeException("Expected same instance: ${context.requestBodySupplier} and $supplier")
      }
      activeSpan().localRootSpan.setTag('request.body', supplier.get() as String)
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext<Context>, StoredBodySupplier, Flow<Void>>
  }
}
