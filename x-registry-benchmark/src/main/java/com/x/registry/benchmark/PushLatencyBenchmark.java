package com.x.registry.benchmark;

import com.x.registry.api.model.Instance;
import com.x.registry.client.XRegistryClient;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class PushLatencyBenchmark {

    private XRegistryClient publishClient;
    private XRegistryClient subscribeClient;
    private final AtomicInteger portCounter = new AtomicInteger(20000);
    private volatile CountDownLatch latch;

    @Param({"127.0.0.1:9848"})
    private String serverAddr;

    @Setup
    public void setup() throws Exception {
        publishClient = XRegistryClient.create(serverAddr);
        subscribeClient = XRegistryClient.create(serverAddr);

        latch = new CountDownLatch(1);
        subscribeClient.getNamingService().subscribe("public", "latency-bench-service", "DEFAULT_GROUP",
                instances -> {
                    if (!instances.isEmpty()) {
                        latch.countDown();
                    }
                });
        Thread.sleep(500);
    }

    @TearDown
    public void teardown() {
        publishClient.close();
        subscribeClient.close();
    }

    @Benchmark
    public void measurePushLatency() throws Exception {
        latch = new CountDownLatch(1);

        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(portCounter.incrementAndGet());
        instance.setEphemeral(true);

        publishClient.getNamingService().registerInstance("public", "latency-bench-service", "DEFAULT_GROUP", instance);
        latch.await(5, TimeUnit.SECONDS);
    }
}
