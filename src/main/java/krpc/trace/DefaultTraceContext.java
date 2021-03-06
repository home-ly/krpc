package krpc.trace;

import krpc.rpc.core.proto.RpcMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultTraceContext implements TraceContext {

    static Logger log = LoggerFactory.getLogger(DefaultTraceContext.class);

    RpcMeta.Trace trace;

    long requestTimeMicros = System.currentTimeMillis() * 1000;
    long startMicros = System.nanoTime() / 1000;
    AtomicInteger subCalls = new AtomicInteger();
    Deque<Span> stack = new ArrayDeque<Span>();
    Map<String, String> tagsForRpc;

    long threadId;
    String threadName = "";
    String threadGroupName = "";

    String timeUsedStr = "";

    public DefaultTraceContext(RpcMeta.Trace trace, boolean restoreFlag) {
        initThreadNames();
        this.trace = trace;
        if (!restoreFlag) return; // web server

        SpanIds newSpanIds = Trace.getAdapter().restore(trace.getParentSpanId(), trace.getSpanId());

        if (newSpanIds != null) {
            RpcMeta.Trace.Builder traceBuilder = trace.toBuilder();
            traceBuilder.setParentSpanId(newSpanIds.getParentSpanId());
            traceBuilder.setSpanId(newSpanIds.getSpanId());
            this.trace = traceBuilder.build();
        }

    }

    public DefaultTraceContext() {
        initThreadNames();

        RpcMeta.Trace.Builder traceBuilder = RpcMeta.Trace.newBuilder();

        TraceIds ids = Trace.getAdapter().newStartTraceIds(false);
        traceBuilder.setTraceId(ids.getTraceId());
        traceBuilder.setParentSpanId(ids.getParentSpanId());
        traceBuilder.setSpanId(ids.getSpanId());

        traceBuilder.setSampleFlag(Trace.getSampleFlag());

        this.trace = traceBuilder.build();
    }

    public void startForServer(String type, String action) {
        SpanIds spanIds = new SpanIds(trace.getParentSpanId(), trace.getSpanId());
        Span rootSpan = new DefaultSpan(this, spanIds, type, action, startMicros, subCalls);
        stack.addLast(rootSpan);

        if (!isEmpty(trace.getTags())) {
            parseTags(rootSpan, trace.getTags());
        }
    }

    // create a span
    public Span startAsync(String type, String action) {
        Span tail = stack.peekLast();
        if (tail == null) {
            SpanIds childIds = Trace.getAdapter().newChildSpanIds(trace.getSpanId(), subCalls);
            return new DefaultSpan(this, childIds, type, action, -1, subCalls);
        } else {
            return tail.newChild(type, action);
        }
    }

    // push the new span to the stack top
    public void start(String type, String action) {
        Span child = startAsync(type, action);
        stack.addLast(child);
    }

    public Span stopForServer(String result) {

        Span span = stack.peekFirst();
        if (span != null) {
            span.stop(result);
            if (!stack.isEmpty()) {
                stack.clear();
                // sendToTrace(span);
            }
            statsTimeUsed(span);
        }

        return span;
    }

    public void stopped(Span span) {

        if (span == stack.peekLast()) {
            stack.removeLast();
            if (stack.isEmpty()) {
                sendToTrace(span);
                Trace.clearCurrentContext();
                return;
            }
        } else {
            if (span == stack.peekFirst()) {
                stack.clear();
                sendToTrace(span);
                Trace.clearCurrentContext();
                return;
            } else if (stack.isEmpty()) {
                sendToTrace(span);
                Trace.clearCurrentContext();
                return;
            }
        }
    }

    private void sendToTrace(Span span) {
        stopAsync(span);
        doSend(span);
    }

    private void statsTimeUsed(Span span) {
        if( span == null ) return;
        timeUsedStr = "";
        if (span.getChildren() != null) {
            StringBuilder b = new StringBuilder();
            for (Span child : span.getChildren()) {
                DefaultSpan ds = (DefaultSpan) child;
                String type = ds.getType();
                long t = ds.getTimeUsedMicros();
                if( b.length() > 0 ) b.append("^");
                b.append(type).append(":").append(t);
            }
            timeUsedStr = b.toString();
        }
    }

    private void doSend(Span span) {
        if (trace.getSampleFlag() == 2) return; // ignore
        Trace.getAdapter().send(this, span);
    }

    private void stopAsync(Span span) {
        ((DefaultSpan) span).stopAsyncIfNeeded();
        if (span.getChildren() != null) {
            for (Span child : span.getChildren()) {
                ((DefaultSpan) child).stopAsyncIfNeeded();
            }
        }
    }

    public String getRemoteAddr() {
        String peers = trace.getPeers();
        if (isEmpty(peers)) return "0.0.0.0:0";
        int p = peers.lastIndexOf(",");
        if (p >= 0) return peers.substring(p + 1);
        return peers;
    }

    public Span currentSpan() {
        Span tail = stack.peekLast();
        return tail;
    }

    void parseTags(Span rootSpan, String tags) {
        String[] ss = tags.split("&");
        for (String s : ss) {
            String[] tt = s.split("=");
            String key = tt[0];
            String value = decodeValue(tt[1]);
            rootSpan.tag(key, value);
            tagForRpc(key, value);
        }
    }

    public void tagForRpc(String key, String value) {
        if (tagsForRpc == null) tagsForRpc = new HashMap<>();
        tagsForRpc.put(key, value);
    }

    public void tagForRpcIfAbsent(String key, String value) {
        if (getTagForRpc(key) != null) return;
        tagForRpc(key, value);
    }

    public String getTagForRpc(String key) {
        if (tagsForRpc == null) return null;
        return tagsForRpc.get(key);
    }

    public String getTagsForRpc() {
        if (tagsForRpc == null) return null;
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> entry : tagsForRpc.entrySet()) {
            if (b.length() > 0) b.append("&");
            b.append(entry.getKey()).append("=").append(encodeValue(entry.getValue()));
        }
        return b.toString();
    }

    void initThreadNames() {

        if (!Trace.getAdapter().needThreadInfo()) return;

        Thread t = Thread.currentThread();
        threadId = t.getId();
        threadName = t.getName();
        threadGroupName = t.getThreadGroup().getName();
    }

    String decodeValue(String value) {
        try {
            return URLDecoder.decode(value, "utf-8");
        } catch (Exception e) {
            return value;
        }
    }

    String encodeValue(String value) {
        try {
            return URLEncoder.encode(value, "utf-8");
        } catch (Exception e) {
            return value;
        }
    }

    boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public long getRequestTimeMicros() {
        return requestTimeMicros;
    }

    public long getStartMicros() {
        return startMicros;
    }

    public long getThreadId() {
        return threadId;
    }

    public String getThreadName() {
        return threadName;
    }

    public String getThreadGroupName() {
        return threadGroupName;
    }

    public RpcMeta.Trace getTrace() {
        return trace;
    }

    public String getTimeUsedStr() {
        return timeUsedStr;
    }
}
