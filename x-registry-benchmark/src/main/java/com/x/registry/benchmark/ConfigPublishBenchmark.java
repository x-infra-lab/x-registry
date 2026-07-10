package com.x.registry.benchmark;

import com.x.registry.client.XRegistryClient;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class ConfigPublishBenchmark {

    private XRegistryClient client;
    private final AtomicInteger counter = new AtomicInteger(0);

    @Param({"127.0.0.1:9848"})
    private String serverAddr;

    @Setup
    public void setup() {
        client = XRegistryClient.create(serverAddr);
    }

    @TearDown
    public void teardown() {
        client.close();
    }

    @Benchmark
    public boolean publishConfig() {
        int n = counter.incrementAndGet();
        return client.getConfigService().publishConfig(
                "public", "bench-config-" + (n % 100) + ".yaml", "DEFAULT_GROUP",
                "key: value-" + n, "yaml", "benchmarker", null);
    }
}
