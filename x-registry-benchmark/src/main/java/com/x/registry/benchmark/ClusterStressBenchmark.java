package com.x.registry.benchmark;

import com.x.registry.api.model.ConfigItem;
import com.x.registry.api.model.Instance;
import com.x.registry.client.XRegistryClient;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(1)
@Threads(8)
public class ClusterStressBenchmark {

    private XRegistryClient client;
    private final AtomicInteger configCounter = new AtomicInteger(0);
    private final AtomicInteger serviceCounter = new AtomicInteger(0);
    private final AtomicInteger portCounter = new AtomicInteger(20000);

    @Param({"127.0.0.1:9848"})
    private String serverAddr;

    @Param({"100"})
    private int serviceCount;

    @Setup
    public void setup() {
        client = XRegistryClient.create(serverAddr);

        for (int i = 0; i < serviceCount; i++) {
            for (int j = 0; j < 10; j++) {
                Instance instance = new Instance();
                instance.setIp("10.0." + (i / 256) + "." + (i % 256));
                instance.setPort(8080 + j);
                instance.setEphemeral(true);
                instance.setWeight(1.0);
                client.getNamingService().registerInstance(
                        "public", "stress-svc-" + i, "DEFAULT_GROUP", instance);
            }
        }
    }

    @TearDown
    public void teardown() {
        client.close();
    }

    @Benchmark
    public boolean configPublishStress() {
        int n = configCounter.incrementAndGet();
        return client.getConfigService().publishConfig(
                "public", "stress-config-" + (n % 1000) + ".yaml", "DEFAULT_GROUP",
                "key: value-" + n + "\nts: " + System.currentTimeMillis(), "yaml",
                "stress-bench", null);
    }

    @Benchmark
    public List<Instance> discoveryStress() {
        int svcIdx = serviceCounter.incrementAndGet() % serviceCount;
        return client.getNamingService().getInstances(
                "public", "stress-svc-" + svcIdx, "DEFAULT_GROUP", false);
    }

    @Benchmark
    public void registerDeregisterStress() {
        int port = portCounter.incrementAndGet();
        Instance instance = new Instance();
        instance.setIp("10.1.0.1");
        instance.setPort(port);
        instance.setEphemeral(true);
        client.getNamingService().registerInstance(
                "public", "stress-churn-svc", "DEFAULT_GROUP", instance);
        client.getNamingService().deregisterInstance(
                "public", "stress-churn-svc", "DEFAULT_GROUP", instance);
    }

    @Benchmark
    public ConfigItem configReadStress() {
        int n = configCounter.get() % 1000;
        return client.getConfigService().getConfig(
                "public", "stress-config-" + n + ".yaml", "DEFAULT_GROUP");
    }
}
