// Copyright 2021 LinkedIn Corporation. All rights reserved.
// Licensed under the BSD-2 Clause license.
// See LICENSE in the project root for license information.

package com.linkedin.cdi.factory.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.linkedin.cdi.util.JsonUtils;
import com.linkedin.cdi.util.VariableUtils;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Enum object to facilitate the handling of different types of HTTP requests.
 *
 * The difference between GET and POST/PUT is that URI parameters are coded differently.
 *
 * However, in all request types, an URI path might be dynamically constructed. For
 * example, https://domain/api/v1.5/surveys/{{id}} is a dynamic URI. The end point might
 * support GET or POST.
 *
 * So if a GET request has 2 parameters, id=1 and format=avro, then the URI will be transformed to
 * https://domain/api/v1.5/surveys/1?format=avro.
 *
 * However if a POST request has 2 parameters, id=1 and name=xxx, then the URI will be transformed to
 * https://domain/api/v1.5/surveys/1, and the name=xxx will be in the POST request's entity content.
 *
 * Note:
 *
 * - URI path variables or placeholders are defined using {{placeholder-name}}
 * - Placeholders or URI variables have to be alpha numeric
 *
 * @author chrli
 */

public enum HttpRequestMethod {
  /**
   * Note: This works when the URI template has all the variable parameters.
   * If there are any additional parameters, then URIBuilder encodes all parameters while building.
   */
  GET_XE("GET_XE") {
    @Override
    protected HttpUriRequest getHttpRequestContentJson(String uriTemplate,
        JsonObject parameters, JsonObject payloads)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = VariableUtils.replaceWithTracking(uriTemplate, parameters, false);
      //ignore payloads
      return new HttpGet(appendParameters(replaced.getKey(), replaced.getValue()));
    }

    @Override
    protected HttpUriRequest getHttpRequestContentUrlEncoded(String uriTemplate, JsonObject parameters)
        throws UnsupportedEncodingException {
      return getHttpRequestContentJson(uriTemplate, parameters, new JsonObject());
    }
  },

  GET("GET") {
    @Override
    protected HttpUriRequest getHttpRequestContentJson(String uriTemplate,
        JsonObject parameters, JsonObject payloads)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = replaceVariables(uriTemplate, parameters);
      //ignore payloads
      return new HttpGet(appendParameters(replaced.getKey(), replaced.getValue()));
    }

    @Override
    protected HttpUriRequest getHttpRequestContentUrlEncoded(String uriTemplate, JsonObject parameters)
        throws UnsupportedEncodingException {
      return getHttpRequestContentJson(uriTemplate, parameters, new JsonObject());
    }
  },

  POST("POST") {
    @Override
    protected HttpUriRequest getHttpRequestContentJson(String uriTemplate,
        JsonObject parameters, JsonObject payloads)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = replaceVariables(uriTemplate, parameters);
      for (Map.Entry<String, JsonElement> entry: payloads.entrySet()) {
        replaced.getValue().add(entry.getKey(), entry.getValue());
      }
      return setEntity(new HttpPost(replaced.getKey()), replaced.getValue().toString());
    }

    @Override
    protected HttpUriRequest getHttpRequestContentUrlEncoded(String uriTemplate, JsonObject parameters)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = replaceVariables(uriTemplate, parameters);
      return setEntity(new HttpPost(replaced.getKey()), jsonToUrlEncodedEntity(replaced.getValue()));
    }
  },

  PUT("PUT") {
    @Override
    protected HttpUriRequest getHttpRequestContentJson(String uriTemplate,
        JsonObject parameters, JsonObject payloads)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = replaceVariables(uriTemplate, parameters);
      for (Map.Entry<String, JsonElement> entry: payloads.entrySet()) {
        replaced.getValue().add(entry.getKey(), entry.getValue());
      }
      return setEntity(new HttpPut(replaced.getKey()), replaced.getValue().toString());
    }

    @Override
    protected HttpUriRequest getHttpRequestContentUrlEncoded(String uriTemplate, JsonObject parameters)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = replaceVariables(uriTemplate, parameters);
      return setEntity(new HttpPut(replaced.getKey()), jsonToUrlEncodedEntity(replaced.getValue()));
    }
  },

  DELETE("DELETE") {
    @Override
    protected HttpUriRequest getHttpRequestContentJson(String uriTemplate,
        JsonObject parameters, JsonObject payloads)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = replaceVariables(uriTemplate, parameters);
      for (Map.Entry<String, JsonElement> entry: payloads.entrySet()) {
        replaced.getValue().add(entry.getKey(), entry.getValue());
      }
      return new HttpDelete(replaced.getKey());
    }

    @Override
    protected HttpUriRequest getHttpRequestContentUrlEncoded(String uriTemplate, JsonObject parameters)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = replaceVariables(uriTemplate, parameters);
      return new HttpDelete(replaced.getKey());
    }
  },

  /**
   * POST_BATCH issues a single {@code multipart/mixed} request that embeds many independent
   * sub-requests (RFC 2046 multipart/mixed, where each part is an {@code application/http}
   * message). It is the batching alternative to POST: instead of one JSON body, each element of
   * the payload {@code data} array becomes its own embedded HTTP sub-request. All sub-requests
   * share the same method, relative URL, and content type. Use it against a REST API that exposes
   * a single {@code /batch} endpoint.
   *
   * Expected payload shape (supplied via an ms.secondary.input of category "payload"):
   * <pre>
   *   {
   *     "batchRelativeUrl": "/v1/entries",       // required
   *     "batchMethod": "POST",                   // optional, default POST
   *     "batchContentType": "application/json",  // optional, default application/json
   *     "data": [ { ...sub-request body... }, ... ]  // required, one part per element
   *   }
   * </pre>
   *
   * Note: do NOT configure a Content-Type request header for POST_BATCH. The entity sets
   * {@code multipart/mixed; boundary=...} with a generated boundary; a manual header would
   * duplicate/override it and drop the boundary. Non-payload parameters are used only for URI
   * template substitution, not for the request body.
   */
  POST_BATCH("POST_BATCH") {
    @Override
    protected HttpUriRequest getHttpRequestContentJson(String uriTemplate,
        JsonObject parameters, JsonObject payloads)
        throws UnsupportedEncodingException {
      Pair<String, JsonObject> replaced = replaceVariables(uriTemplate, parameters);
      return setEntity(new HttpPost(replaced.getKey()), buildMultipartMixedEntity(payloads));
    }

    @Override
    protected HttpUriRequest getHttpRequestContentUrlEncoded(String uriTemplate, JsonObject parameters)
        throws UnsupportedEncodingException {
      // URL-encoding is meaningless for a multipart batch; the batch body always comes from payloads.
      return getHttpRequestContentJson(uriTemplate, parameters, new JsonObject());
    }
  };

  private static final Logger LOG = LoggerFactory.getLogger(HttpRequestMethod.class);

  // POST_BATCH (multipart/mixed) rendering constants and payload keywords.
  private static final String CRLF = "\r\n";
  private static final String BATCH_BOUNDARY_PREFIX = "cdi_batch_";
  private static final String CONTENT_TYPE_MULTIPART_MIXED = "multipart/mixed";
  private static final String CONTENT_TYPE_APPLICATION_HTTP = "application/http";
  private static final String KEY_WORD_DATA = "data";
  private static final String KEY_WORD_BATCH_RELATIVE_URL = "batchRelativeUrl";
  private static final String KEY_WORD_BATCH_METHOD = "batchMethod";
  private static final String KEY_WORD_BATCH_CONTENT_TYPE = "batchContentType";
  private static final String DEFAULT_BATCH_METHOD = "POST";
  private static final String DEFAULT_BATCH_CONTENT_TYPE = "application/json";

  private final String name;

  HttpRequestMethod(String name) {
    this.name = name;
  }

  @Override
  public String toString() {
    return name;
  }

  /**
   * This is the public method to generate HttpUriRequest for each type of Http Method
   * @param uriTemplate input URI, which might contain place holders
   * @param parameters parameters to be add to URI or to request Entity
   * @param headers Http header tags
   * @return HttpUriRequest ready for connection
   */
  public HttpUriRequest getHttpRequest(final String uriTemplate, final JsonObject parameters, final Map<String, String> headers)
      throws UnsupportedEncodingException {
    return getHttpRequest(uriTemplate, parameters, headers, new JsonObject());
  }

  /**
   * This is the public method to generate HttpUriRequest for each type of Http Method
   * @param uriTemplate input URI, which might contain place holders
   * @param parameters parameters to be add to URI or to request Entity
   * @param headers Http header tags
   * @param payloads additional payloads to be included in the body of the Http request
   * @return HttpUriRequest ready for connection
   */
  public HttpUriRequest getHttpRequest(final String uriTemplate,
      final JsonObject parameters,
      final Map<String, String> headers,
      final JsonObject payloads)
      throws UnsupportedEncodingException {
    HttpUriRequest request;

    // substitute variables in headers
    Map<String, String> headersCopy = new HashMap<>();
    JsonObject parametersCopy = JsonUtils.deepCopy(parameters).getAsJsonObject();
    for (Map.Entry<String, String> entry: headers.entrySet()) {
      Pair<String, JsonObject> replaced = VariableUtils.replaceWithTracking(entry.getValue(), parameters);
      if (!replaced.getLeft().equals(entry.getValue())) {
        parametersCopy = JsonUtils.deepCopy(replaced.getRight()).getAsJsonObject();
        headersCopy.put(entry.getKey(), replaced.getLeft());
        LOG.info("Substituted header string: {} = {}", entry.getKey(), replaced.getLeft());
      } else {
        headersCopy.put(entry.getKey(), entry.getValue());
      }
    }

    LOG.info("Final parameters for HttpRequest: {}", parametersCopy.toString());
    if (headersCopy.containsKey("Content-Type")
        && headersCopy.get("Content-Type").equals("application/x-www-form-urlencoded")) {
      request = getHttpRequestContentUrlEncoded(uriTemplate, parametersCopy);
    } else {
      request = getHttpRequestContentJson(uriTemplate, parametersCopy, payloads);
    }

    for (Map.Entry<String, String> entry: headersCopy.entrySet()) {
      request.addHeader(entry.getKey(), entry.getValue());
    }
    return request;
  }

  /**
   * This method shall be overwritten by each enum element.
   * @param uriTemplate input URI, which might contain place holders
   * @param parameters parameters to be add to URI or to request Entity
   * @param payloads additional payloads to be included in the body of the Http request
   * @return HttpUriRequest object where content is set per application/json
   */
  protected abstract HttpUriRequest getHttpRequestContentJson(String uriTemplate,
      JsonObject parameters, JsonObject payloads)
      throws UnsupportedEncodingException;

  /**
   * This method shall be overwritten by each enum element.
   * @param uriTemplate input URI, which might contain place holders
   * @param parameters parameters to be add to URI or to request Entity
   * @return HttpUriRequest object where content is set per application/x-www-form-urlencoded
   */
  protected abstract HttpUriRequest getHttpRequestContentUrlEncoded(String uriTemplate, JsonObject parameters)
      throws UnsupportedEncodingException;

  protected Pair<String, JsonObject> replaceVariables(String uriTemplate, JsonObject parameters)
      throws UnsupportedEncodingException {
    return VariableUtils.replaceWithTracking(uriTemplate, parameters, true);
  }

  protected String appendParameters(String uri, JsonObject parameters) {
    try {
      URIBuilder builder = new URIBuilder(new URI(uri));
      for (Map.Entry<String, JsonElement> entry : parameters.entrySet()) {
        if (!entry.getKey().matches("tmp.*")) {
          builder.addParameter(entry.getKey(), entry.getValue().getAsString());
        }
      }
      return builder.build().toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Convert Json formatted parameter set to Url Encoded Entity as requested by
   * Content-Type: application/x-www-form-urlencoded
   * Json Example:
   *    {"param1": "value1", "param2": "value2"}
   *
   * URL Encoded Example:
   *   param1=value1%26param2=value2
   *
   * @param parameters Json structured parameters
   * @return URL encoded entity
   */
  protected UrlEncodedFormEntity jsonToUrlEncodedEntity(JsonObject parameters) {
    try {
      List<NameValuePair> nameValuePairs = new ArrayList<>();
      for (Map.Entry<String, JsonElement> entry : parameters.entrySet()) {
        nameValuePairs.add(new BasicNameValuePair(entry.getKey(),entry.getValue().getAsString()));
      }
      return new UrlEncodedFormEntity(nameValuePairs, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected HttpUriRequest setEntity(HttpEntityEnclosingRequestBase requestBase, String stringEntity)
      throws UnsupportedEncodingException {
    return setEntity(requestBase, new StringEntity(stringEntity));
  }

  protected HttpUriRequest setEntity(HttpEntityEnclosingRequestBase requestBase, StringEntity stringEntity) {
    requestBase.setEntity(stringEntity);
    return requestBase;
  }

  /**
   * Build a {@code multipart/mixed} entity for POST_BATCH from the payload object. Each element of
   * the payload {@code data} array is rendered as an embedded {@code application/http} sub-request
   * that shares the batch method, relative URL, and content type. The entity's content type carries
   * the generated MIME boundary.
   *
   * @param payloads the payload object (see the POST_BATCH javadoc for the expected shape)
   * @return a StringEntity whose body is the multipart/mixed batch and whose content type is
   *         {@code multipart/mixed; boundary=...}
   */
  protected StringEntity buildMultipartMixedEntity(JsonObject payloads) {
    if (!hasNonNull(payloads, KEY_WORD_BATCH_RELATIVE_URL)) {
      throw new IllegalArgumentException(
          "POST_BATCH requires a '" + KEY_WORD_BATCH_RELATIVE_URL + "' string in the payload");
    }
    if (!payloads.has(KEY_WORD_DATA) || !payloads.get(KEY_WORD_DATA).isJsonArray()
        || payloads.getAsJsonArray(KEY_WORD_DATA).size() == 0) {
      throw new IllegalArgumentException(
          "POST_BATCH requires a non-empty '" + KEY_WORD_DATA + "' array in the payload");
    }

    String relativeUrl = payloads.get(KEY_WORD_BATCH_RELATIVE_URL).getAsString();
    String subMethod = getPayloadString(payloads, KEY_WORD_BATCH_METHOD, DEFAULT_BATCH_METHOD);
    String subContentType = getPayloadString(payloads, KEY_WORD_BATCH_CONTENT_TYPE, DEFAULT_BATCH_CONTENT_TYPE);
    JsonArray subRequests = payloads.getAsJsonArray(KEY_WORD_DATA);

    String boundary = BATCH_BOUNDARY_PREFIX + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    int contentId = 0;
    for (JsonElement subRequest : subRequests) {
      contentId++;
      body.append("--").append(boundary).append(CRLF)
          .append("Content-Type: ").append(CONTENT_TYPE_APPLICATION_HTTP).append(CRLF)
          .append("Content-Transfer-Encoding: binary").append(CRLF)
          .append("Content-ID: <cdi-batch-").append(contentId).append(">").append(CRLF)
          .append(CRLF)
          .append(subMethod).append(" ").append(relativeUrl).append(CRLF)
          .append("Content-Type: ").append(subContentType).append(CRLF)
          .append(CRLF)
          .append(subRequest.toString()).append(CRLF);
    }
    body.append("--").append(boundary).append("--").append(CRLF);

    StringEntity entity = new StringEntity(body.toString(), StandardCharsets.UTF_8);
    entity.setContentType(CONTENT_TYPE_MULTIPART_MIXED + "; boundary=" + boundary);
    return entity;
  }

  private static boolean hasNonNull(JsonObject object, String key) {
    return object.has(key) && !object.get(key).isJsonNull();
  }

  private static String getPayloadString(JsonObject payloads, String key, String defaultValue) {
    return hasNonNull(payloads, key) ? payloads.get(key).getAsString() : defaultValue;
  }
}
