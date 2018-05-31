/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.dnsoverhttps;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import javax.annotation.Nullable;
import okhttp3.CacheControl;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;
import okio.ByteString;

/**
 * DNS over HTTPS implementation.
 *
 * Implementation of https://tools.ietf.org/html/draft-ietf-doh-dns-over-https-09
 *
 * <blockquote>A DNS API client encodes a single DNS query into an HTTP request
 * using either the HTTP GET or POST method and the other requirements
 * of this section.  The DNS API server defines the URI used by the
 * request through the use of a URI Template.</blockquote>
 */
public class DnsOverHttps implements Dns {
  public static final MediaType DNS_MESSAGE = MediaType.parse("application/dns-message");
  public static final MediaType UDPWIREFORMAT = MediaType.parse("application/dns-udpwireformat");
  private final OkHttpClient client;
  private final HttpUrl url;
  private final boolean includeIPv6;
  private final boolean post;
  private final MediaType contentType;

  DnsOverHttps(Builder builder) {
    if (builder.client == null) {
      throw new NullPointerException("client not set");
    }
    if (builder.url == null) {
      throw new NullPointerException("url not set");
    }

    this.client =
        builder.bootstrapDns != null ? builder.client.newBuilder().dns(builder.bootstrapDns).build()
            : builder.client;
    this.url = builder.url;
    this.includeIPv6 = builder.includeIPv6;
    this.post = builder.post;
    this.contentType = builder.contentType;
  }

  public HttpUrl url() {
    return url;
  }

  public boolean post() {
    return post;
  }

  public boolean includeIPv6() {
    return includeIPv6;
  }

  public MediaType contentType() {
    return contentType;
  }

  public OkHttpClient client() {
    return client;
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    try {
      ByteString query = DnsRecordCodec.encodeQuery(hostname, includeIPv6);

      Request request = buildRequest(query);
      Response response = executeRequest(request);

      return readResponse(hostname, response);
    } catch (UnknownHostException uhe) {
      throw uhe;
    } catch (Exception e) {
      UnknownHostException unknownHostException = new UnknownHostException(hostname);
      unknownHostException.initCause(e);
      throw unknownHostException;
    }
  }

  private Response executeRequest(Request request) throws IOException {
    // cached request
    if (!post && client.cache() != null) {
      Request cacheRequest = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build();

      Response response = client.newCall(cacheRequest).execute();

      if (response.isSuccessful()) {
        return response;
      }
    }

    return client.newCall(request).execute();
  }

  private List<InetAddress> readResponse(String hostname, Response response) throws Exception {
    if (response.cacheResponse() == null && response.protocol() != Protocol.HTTP_2) {
      Platform.get().log(Platform.WARN, "Incorrect protocol: " + response.protocol(), null);
    }

    try {
      if (!response.isSuccessful()) {
        throw new IOException("response: " + response.code() + " " + response.message());
      }

      ByteString responseBytes = response.body().source().readByteString();

      List<InetAddress> results = DnsRecordCodec.decodeAnswers(hostname, responseBytes);

      return results;
    } finally {
      response.close();
    }
  }

  private Request buildRequest(ByteString query) {
    Request.Builder requestBuilder = new Request.Builder().header("Accept", contentType.toString());

    if (post) {
      requestBuilder = requestBuilder.url(url).post(RequestBody.create(contentType, query));
    } else {
      String encoded = query.base64Url().replace("=", "");
      HttpUrl requestUrl = url.newBuilder().addQueryParameter("dns", encoded).build();

      requestBuilder = requestBuilder.url(requestUrl);
    }

    return requestBuilder.build();
  }

  public static final class Builder {
    @Nullable OkHttpClient client = null;
    @Nullable HttpUrl url = null;
    boolean includeIPv6 = true;
    boolean post = false;
    MediaType contentType = DNS_MESSAGE;
    @Nullable Dns bootstrapDns = null;

    public DnsOverHttps build() {
      return new DnsOverHttps(this);
    }

    public Builder client(OkHttpClient client) {
      this.client = client;
      return this;
    }

    public Builder url(HttpUrl url) {
      this.url = url;
      return this;
    }

    public Builder includeIPv6(boolean includeIPv6) {
      this.includeIPv6 = includeIPv6;
      return this;
    }

    public Builder post(boolean post) {
      this.post = post;
      return this;
    }

    public Builder contentType(MediaType contentType) {
      this.contentType = contentType;
      return this;
    }

    public Builder bootstrapDns(@Nullable Dns bootstrapDns) {
      this.bootstrapDns = bootstrapDns;
      return this;
    }
  }
}