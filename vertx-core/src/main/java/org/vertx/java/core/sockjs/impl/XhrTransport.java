/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.vertx.java.core.sockjs.impl;

import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.RouteMatcher;
import org.vertx.java.core.impl.VertxInternal;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.core.sockjs.SockJSSocket;

import java.util.Map;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
class XhrTransport extends BaseTransport {

  private static final Logger log = LoggerFactory.getLogger(XhrTransport.class);

  private static final Buffer H_BLOCK;

  static {
    byte[] bytes = new byte[2048 + 1];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte)'h';
    }
    bytes[bytes.length - 1] = (byte)'\n';
    H_BLOCK = new Buffer(bytes);
  }

  XhrTransport(VertxInternal vertx, RouteMatcher rm, String basePath, final Map<String, Session> sessions, final JsonObject config,
            final Handler<SockJSSocket> sockHandler) {

    super(vertx, sessions, config);

    String xhrBase = basePath + COMMON_PATH_ELEMENT_RE;
    String xhrRE = xhrBase + "xhr";
    String xhrStreamRE = xhrBase + "xhr_streaming";

    Handler<HttpServerRequest> xhrOptionsHandler = createCORSOptionsHandler(config, "OPTIONS, POST");

    rm.optionsWithRegEx(xhrRE, xhrOptionsHandler);
    rm.optionsWithRegEx(xhrStreamRE, xhrOptionsHandler);

    registerHandler(rm, sockHandler, xhrRE, false, config);
    registerHandler(rm, sockHandler, xhrStreamRE, true, config);

    String xhrSendRE = basePath + COMMON_PATH_ELEMENT_RE + "xhr_send";

    rm.optionsWithRegEx(xhrSendRE, xhrOptionsHandler);

    rm.postWithRegEx(xhrSendRE, new Handler<HttpServerRequest>() {
      public void handle(final HttpServerRequest req) {
        if (log.isTraceEnabled()) log.trace("XHR send, post, " + req.uri());
        String sessionID = req.params().get("param0");
        final Session session = sessions.get(sessionID);
        if (session != null) {
          handleSend(req, session);
        } else {
          req.response().setStatusCode(404);
          setJSESSIONID(config, req);
          req.response().end();
        }
      }
    });
  }

  private void registerHandler(RouteMatcher rm, final Handler<SockJSSocket> sockHandler, String re,
                               final boolean streaming, final JsonObject config) {
    rm.postWithRegEx(re, new Handler<HttpServerRequest>() {
      public void handle(final HttpServerRequest req) {
        if (log.isTraceEnabled()) log.trace("XHR, post, " + req.uri());
        setNoCacheHeaders(req);
        String sessionID = req.params().get("param0");
        Session session = getSession(config.getLong("session_timeout"), config.getLong("heartbeat_period"), sessionID, sockHandler);
        session.setInfo(req.localAddress(), req.remoteAddress(), req.uri(), req.headers());
        session.register(streaming? new XhrStreamingListener(config.getInteger("max_bytes_streaming"), req, session) : new XhrPollingListener(req, session));
      }
    });
  }

  private void handleSend(final HttpServerRequest req, final Session session) {

    req.bodyHandler(new Handler<Buffer>() {
      public void handle(Buffer buff) {
        String msgs = buff.toString();
        if (msgs.equals("")) {
          req.response().setStatusCode(500);
          req.response().end("Payload expected.");
          return;
        }
        if (!session.handleMessages(msgs)) {
          sendInvalidJSON(req.response());
        } else {
          req.response().headers().set("Content-Type", "text/plain; charset=UTF-8");
          setNoCacheHeaders(req);
          setJSESSIONID(config, req);
          setCORS(req);
          req.response().setStatusCode(204);
          req.response().end();
        }
        if (log.isTraceEnabled()) log.trace("XHR send processed ok");
      }
    });
  }

  private abstract class BaseXhrListener extends BaseListener {

    boolean headersWritten;

    BaseXhrListener(HttpServerRequest req, Session session) {
      super(req, session);
    }

    public void sendFrame(String body) {
      if (log.isTraceEnabled()) log.trace("XHR sending frame");
      if (!headersWritten) {
        req.response().headers().set("Content-Type", "application/javascript; charset=UTF-8");
        setJSESSIONID(config, req);
        setCORS(req);
        req.response().setChunked(true);
        headersWritten = true;
      }
    }

    public void close() {
    }

    @Override
    public void sessionClosed() {
      session.writeClosed(this);
      close();
    }
  }

  private class XhrPollingListener extends BaseXhrListener {

    XhrPollingListener(HttpServerRequest req, final Session session) {
      super(req, session);
      addCloseHandler(req.response(), session);
    }

    public void sendFrame(String body) {
      super.sendFrame(body);
      req.response().write(body + "\n");
      close();
    }

    public void close() {
      if (log.isTraceEnabled()) log.trace("XHR poll closing listener");
      if (!closed) {
        try {
          session.resetListener();
          req.response().end();
          req.response().close();
          closed = true;
        } catch (IllegalStateException e) {
          // Underlying connection might already be closed - that's fine
        }
      }
    }
  }

  private class XhrStreamingListener extends BaseXhrListener {

    int bytesSent;
    int maxBytesStreaming;

    XhrStreamingListener(int maxBytesStreaming, HttpServerRequest req, final Session session) {
      super(req, session);
      this.maxBytesStreaming = maxBytesStreaming;
      addCloseHandler(req.response(), session);
    }

    public void sendFrame(String body) {
      boolean hr = headersWritten;
      super.sendFrame(body);
      if (!hr) {
        req.response().write(H_BLOCK);
      }
      String sbody = body + "\n";
      Buffer buff = new Buffer(sbody);
      req.response().write(buff);
      bytesSent += buff.length();
      if (bytesSent >= maxBytesStreaming) {
        close();
      }
    }

    public void close() {
      if (log.isTraceEnabled()) log.trace("XHR stream closing listener");
      if (!closed) {
        session.resetListener();
        try {
          req.response().end();
          req.response().close();
          closed = true;
        } catch (IllegalStateException e) {
          // Underlying connection might already be closed - that's fine
        }
      }
    }
  }



}
