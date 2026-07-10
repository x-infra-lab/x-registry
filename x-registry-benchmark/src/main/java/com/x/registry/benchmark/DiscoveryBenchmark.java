package com.x.registry.benchmark;

import com.x.registry.api.model.Instance;
import com.x.registry.client.XRegistryClient;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class DiscoveryBenchmark {

    private XRegistryClient client;

    @Param({"127.0.0.1:9848"})
    private String serverAddr;

    @Setup
    public void setup() {
        client = XRegistryClient.create(serverAddr);

        for (int i = 0; i < 10; i++) {
            Instance instance = new Instance();
            instance.setIp("10.0.0." + (i + 1));
            instance.setPort(8080);
            instance.setEphemeral(true);
            client.getNamingService().registerInstance("public", "discovery-bench-service", "DEFAULT_GROUP", instance);
        }
    }

    @TearDown
    public void teardown() {
        client.close();
    }

    @Benchmark
    public List<Instance> queryInstances() {
        return client.getNamingService().getInstances("public", "discovery-bench-service", "DEFAULT_GROUP", false);
    }
}
