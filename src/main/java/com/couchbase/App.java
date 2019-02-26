package com.couchbase;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.core.tracing.ThresholdLogReporter;
import com.couchbase.client.core.tracing.ThresholdLogTracer;
import com.couchbase.client.java.AsyncBucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment;
import com.couchbase.client.java.query.AsyncN1qlQueryResult;
import com.couchbase.client.java.query.N1qlQuery;
import com.couchbase.utils.Options;

import io.jaegertracing.Configuration;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

public class App {
  private static CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(App.class);

  private static AsyncBucket bucket;
  private static String bucketName;
  private static List<String> docIDs;

  private static Tracer appTracer;
  private static Tracer couchbaseTracer;

  private static N1qlQuery query;

  private static List<JsonDocument> docs;
  private static int total;
  private static int range;

  private static AtomicLong count = new AtomicLong(0);

  private static Action1<Object> onNext = object -> {};
  private static Action1<Throwable> onError = throwable -> count.getAndDecrement();
  private static Action0 onComplete = () -> count.getAndDecrement();

  public static void main(String... args) {
    JVMInfo();

    Options options = new Options(args);

    initTracers(options);

    openBucket(options, couchbaseTracer);

    prepareCaches(options);

    OPS.setOps(options);

    while (true) {
      while (count.get() > options.integerValueOf("queue")) {
        try {
          TimeUnit.MICROSECONDS.sleep(options.integerValueOf("delay"));
        } catch (InterruptedException ex) {
          // do nothing
        }
      }

      count.getAndIncrement();

      int op = ThreadLocalRandom.current().nextInt(OPS.count());

      trace(op);
    }
  }

  private static void trace(int op) {
    Span span = appTracer.buildSpan(OPS.name(op)).start();
    SpanBuilder builder = couchbaseTracer.buildSpan(OPS.name(op)).asChildOf(span.context());

    OPS.get(op).apply(builder);

    span.finish();
  }

  private static void initTracers(Options options) {
    if ("threshold".equals(options.stringValueOf("tracer"))) {
      appTracer = ThresholdLogTracer.create(ThresholdLogReporter.builder().pretty(true).build());

      couchbaseTracer = ThresholdLogTracer.create(ThresholdLogReporter.builder()
          .kvThreshold(options.integerValueOf("kv-threshold"), TimeUnit.MILLISECONDS)
          .n1qlThreshold(options.integerValueOf("n1ql-threshold"), TimeUnit.MILLISECONDS)
          .logInterval(options.integerValueOf("log"), TimeUnit.SECONDS)
          .sampleSize(options.integerValueOf("sample"))
          .pretty(true)
          .build());

      return;
    }

    if ("jaeger".equals(options.stringValueOf("tracer"))) {
      System.setProperty("JAEGER_AGENT_HOST", System.getProperty("JAEGER_AGENT_HOST", options.stringValueOf("jaeger-host")));
      System.setProperty("JAEGER_AGENT_PORT", System.getProperty("JAEGER_AGENT_PORT", "6831"));
      System.setProperty("JAEGER_SAMPLER_TYPE", System.getProperty("JAEGER_SAMPLER_TYPE", "const"));
      System.setProperty("JAEGER_SAMPLER_PARAM", System.getProperty("JAEGER_SAMPLER_PARAM", "1"));
      System.setProperty("JAEGER_REPORTER_LOG_SPANS", System.getProperty("JAEGER_REPORTER_LOG_SPANS", "true"));

      appTracer = Configuration.fromEnv("app").getTracer();
      couchbaseTracer = Configuration.fromEnv("couchbase").getTracer();

      return;
    }

    throw new IllegalArgumentException("Unknown tracer requested: " + options.stringValueOf("tracer"));
  }

  private enum OPS {
    GET(() -> bucket.get(docIDs.get(ThreadLocalRandom.current().nextInt(total))).subscribe(onNext, onError, onComplete)),
    UPSERT(() -> bucket.upsert(docs.get(ThreadLocalRandom.current().nextInt(range))).subscribe(onNext, onError, onComplete)),
    REMOVE(() -> bucket.remove(docs.get(ThreadLocalRandom.current().nextInt(range)).id()).subscribe(onNext, onError, onComplete)),
    QUERY(() -> bucket.query(query).subscribe(onNext, onError, onComplete));

    private static OPS[] ops = OPS.values();

    private Supplier<Subscription> op;

    OPS(Supplier<Subscription> op) {
      this.op = op;
    }

    private Subscription apply(SpanBuilder builder) {
      try (Scope scope = builder.startActive(true)) {
        return op.get();
      } catch (Exception ex) {
        LOGGER.error(ex);
      }

      return null;
    }

    public static void setOps(Options options) {
      String list = options.stringValueOf("execute");

      query = N1qlQuery.simple(options.stringValueOf("n1ql"));

      if ("all".equals(list)) return;

      List<String> selected = Arrays.asList(list.split(","));

      ops = Stream.of(OPS.values()).filter(op -> selected.contains(op.name())).toArray(OPS[]::new);
    }

    public static OPS get(int index) { return ops[index]; }

    public static int count() { return ops.length; }

    public static String name(int index) { return ops[index].name(); }
  }

  private static void openBucket(Options options, Tracer tracer) {
    List<String> nodes = Arrays.asList(options.stringValueOf("cluster").split(","));

    bucketName = options.stringValueOf("bucket");

    Cluster cluster = CouchbaseCluster.create(DefaultCouchbaseEnvironment.builder()
        .tracer(tracer)
        .operationTracingEnabled(true)
        .operationTracingServerDurationEnabled(true)
        .build(), nodes);

    cluster.authenticate(options.stringValueOf("user"), options.stringValueOf("password"));

    bucket = cluster.openBucket(bucketName).async();
  }

  private static void prepareCaches(Options options) {
    docIDs = bucket.query(N1qlQuery.simple( "SELECT META().id FROM `" + bucketName + "`;"))
        .flatMap(AsyncN1qlQueryResult::rows)
        .map(row -> (String)row.value().get("id"))
        .toList()
        .toBlocking()
        .single();

    total = docIDs.size();

    range = options.integerValueOf("range");

    if (-1 == range) range = total;

    docs = bucket.query(N1qlQuery.simple( "SELECT * FROM `" + bucketName + "` LIMIT " + range + ";"))
        .flatMap(AsyncN1qlQueryResult::rows)
        .map(row -> JsonDocument.create("key-" + ThreadLocalRandom.current().nextLong(), row.value()))
        .toList()
        .toBlocking()
        .single();
  }

  private static void JVMInfo() {
    /* Total number of processors or cores available to the JVM */
    System.out.println("Available processors (cores): " +
        Runtime.getRuntime().availableProcessors());

    /* Total amount of free memory available to the JVM */
    System.out.println("Free memory (bytes): " +
        Runtime.getRuntime().freeMemory());

    /* This will return Long.MAX_VALUE if there is no preset limit */
    long maxMemory = Runtime.getRuntime().maxMemory();
    /* Maximum amount of memory the JVM will attempt to use */
    System.out.println("Maximum memory (bytes): " +
        (maxMemory == Long.MAX_VALUE ? "no limit" : maxMemory));

    /* Total memory currently in use by the JVM */
    System.out.println("Total memory (bytes): " +
        Runtime.getRuntime().totalMemory());

  }
}
