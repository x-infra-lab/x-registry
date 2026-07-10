package com.x.registry.benchmark;

import com.x.registry.api.model.Instance;
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
public class RegisterBenchmark {

    private XRegistryClient client;
    private final AtomicInteger portCounter = new AtomicInteger(10000);

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
    public void registerInstance() {
        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(portCounter.incrementAndGet());
        instance.setEphemeral(true);
        client.getNamingService().registerInstance("public", "bench-service", "DEFAULT_GROUP", instance);
    }
}
