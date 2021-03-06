/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zipkin2.elasticsearch.internal;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSource;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.elasticsearch.internal.client.HttpCall;
import zipkin2.internal.Nullable;

import static zipkin2.internal.JsonEscaper.jsonEscape;

// See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html
// exposed to re-use for testing writes of dependency links
public final class HttpBulkIndexer {
  static final MediaType APPLICATION_JSON = MediaType.parse("application/json");

  final String tag;
  final HttpCall.Factory http;
  final String pipeline;
  final boolean waitForRefresh;

  // Mutated for each call to add
  final Buffer body = new Buffer();

  public HttpBulkIndexer(String tag, ElasticsearchStorage es) {
    this.tag = tag;
    http = es.http();
    pipeline = es.pipeline();
    waitForRefresh = es.flushOnWrites();
  }

  enum CheckForErrors implements HttpCall.BodyConverter<Void> {
    INSTANCE;

    @Override
    public Void convert(BufferedSource b) throws IOException {
      String content = b.readUtf8();
      if (content.contains("\"status\":429")) throw new RejectedExecutionException(content);
      if (content.contains("\"errors\":true")) throw new IllegalStateException(content);
      return null;
    }

    @Override
    public String toString() {
      return "CheckForErrors";
    }
  }

  public void add(String index, String typeName, byte[] document, @Nullable String id) {
    writeIndexMetadata(index, typeName, id);
    writeDocument(document);
  }

  void writeIndexMetadata(String index, String typeName, @Nullable String id) {
    body.writeUtf8("{\"index\":{\"_index\":\"").writeUtf8(index).writeByte('"');
    // the _type parameter is needed for Elasticsearch <6.x
    body.writeUtf8(",\"_type\":\"").writeUtf8(typeName).writeByte('"');
    if (id != null) {
      body.writeUtf8(",\"_id\":\"").writeUtf8(jsonEscape(id).toString()).writeByte('"');
    }
    body.writeUtf8("}}\n");
  }

  void writeDocument(byte[] document) {
    body.write(document);
    body.writeByte('\n');
  }

  /** Creates a bulk request when there is more than one object to store */
  public HttpCall<Void> newCall() {
    HttpUrl.Builder urlBuilder = http.baseUrl.newBuilder("_bulk");
    if (pipeline != null) urlBuilder.addQueryParameter("pipeline", pipeline);
    if (waitForRefresh) urlBuilder.addQueryParameter("refresh", "wait_for");

    Request request = new Request.Builder()
      .url(urlBuilder.build())
      .tag(tag)
      .post(RequestBody.create(APPLICATION_JSON, body.readByteString()))
      .build();

    return http.newCall(request, CheckForErrors.INSTANCE);
  }
}
