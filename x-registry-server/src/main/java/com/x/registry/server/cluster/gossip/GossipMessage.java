package com.x.registry.server.cluster.gossip;

import com.x.registry.server.cluster.Member;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class GossipMessage implements Serializable {

    public enum Type {
        PING, ACK, SUSPECT, DEAD, ALIVE, INDIRECT_PING
    }

    private Type type;
    private Member sourceMember;
    private String targetMemberId;
    private List<Member> members;
    private List<GossipMessage> piggybackMessages;

    public GossipMessage() {
    }

    public static GossipMessage ping(Member source, List<GossipMessage> piggyback) {
        GossipMessage msg = new GossipMessage();
        msg.type = Type.PING;
        msg.sourceMember = source;
        msg.piggybackMessages = piggyback != null ? piggyback : new ArrayList<>();
        return msg;
    }

    public static GossipMessage ack(Member source, List<Member> members, List<GossipMessage> piggyback) {
        GossipMessage msg = new GossipMessage();
        msg.type = Type.ACK;
        msg.sourceMember = source;
        msg.members = members;
        msg.piggybackMessages = piggyback != null ? piggyback : new ArrayList<>();
        return msg;
    }

    public static GossipMessage suspect(Member source, Member target) {
        GossipMessage msg = new GossipMessage();
        msg.type = Type.SUSPECT;
        msg.sourceMember = source;
        msg.targetMemberId = target != null ? target.getId() : null;
        return msg;
    }

    public static GossipMessage dead(Member source, Member target) {
        GossipMessage msg = new GossipMessage();
        msg.type = Type.DEAD;
        msg.sourceMember = source;
        msg.targetMemberId = target != null ? target.getId() : null;
        return msg;
    }

    public static GossipMessage alive(Member source, Member target) {
        GossipMessage msg = new GossipMessage();
        msg.type = Type.ALIVE;
        msg.sourceMember = source;
        msg.targetMemberId = target != null ? target.getId() : null;
        return msg;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Member getSourceMember() {
        return sourceMember;
    }

    public void setSourceMember(Member sourceMember) {
        this.sourceMember = sourceMember;
    }

    public String getTargetMemberId() {
        return targetMemberId;
    }

    public void setTargetMemberId(String targetMemberId) {
        this.targetMemberId = targetMemberId;
    }

    public List<Member> getMembers() {
        return members;
    }

    public void setMembers(List<Member> members) {
        this.members = members;
    }

    public List<GossipMessage> getPiggybackMessages() {
        return piggybackMessages;
    }

    public void setPiggybackMessages(List<GossipMessage> piggybackMessages) {
        this.piggybackMessages = piggybackMessages;
    }
}
