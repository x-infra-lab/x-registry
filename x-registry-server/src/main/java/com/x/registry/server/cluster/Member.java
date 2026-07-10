package com.x.registry.server.cluster;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.Objects;

public class Member implements Serializable {

    public enum State {
        ALIVE, SUSPECT, DEAD
    }

    private String address;
    private int port;
    private int grpcPort;
    private int distroPort;
    private int raftPort;
    private State state;
    private long lastStateChange;
    private long incarnation;

    public Member() {
    }

    public Member(String address, int port, int grpcPort) {
        this(address, port, grpcPort, 0, 0);
    }

    public Member(String address, int port, int grpcPort, int distroPort, int raftPort) {
        this.address = address;
        this.port = port;
        this.grpcPort = grpcPort;
        this.distroPort = distroPort;
        this.raftPort = raftPort;
        this.state = State.ALIVE;
        this.lastStateChange = System.currentTimeMillis();
        this.incarnation = 0;
    }

    @JsonIgnore
    public String getId() {
        return address + ":" + port;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public void setGrpcPort(int grpcPort) {
        this.grpcPort = grpcPort;
    }

    public int getDistroPort() {
        return distroPort;
    }

    public void setDistroPort(int distroPort) {
        this.distroPort = distroPort;
    }

    public int getRaftPort() {
        return raftPort;
    }

    public void setRaftPort(int raftPort) {
        this.raftPort = raftPort;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
        this.lastStateChange = System.currentTimeMillis();
    }

    public long getLastStateChange() {
        return lastStateChange;
    }

    public void setLastStateChange(long lastStateChange) {
        this.lastStateChange = lastStateChange;
    }

    public long getIncarnation() {
        return incarnation;
    }

    public void setIncarnation(long incarnation) {
        this.incarnation = incarnation;
    }

    public void incrementIncarnation() {
        this.incarnation++;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Member member = (Member) o;
        return port == member.port && Objects.equals(address, member.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, port);
    }

    @Override
    public String toString() {
        return "Member{" + address + ":" + port + ", state=" + state + ", incarnation=" + incarnation + "}";
    }
}
