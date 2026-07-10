package com.x.registry.server.cluster.gossip;

import com.x.registry.server.cluster.Member;
import com.x.registry.server.cluster.MemberManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GossipProtocolTest {

    @Mock
    private GossipTransport transport;

    private MemberManager memberManager;
    private GossipConfig config;
    private GossipProtocol gossipProtocol;

    private Member selfMember;

    @BeforeEach
    void setUp() {
        selfMember = new Member("127.0.0.1", 7848, 9848, 7849, 7850);
        memberManager = new MemberManager(selfMember);
        config = new GossipConfig()
                .setProbeIntervalMs(1000)
                .setPingTimeoutMs(500)
                .setSuspectTimeoutMs(5000)
                .setIndirectProbeCount(3)
                .setGossipPort(7848);
        gossipProtocol = new GossipProtocol(memberManager, transport, config);
    }

    @Test
    void handlePing_updatesSourceMember() {
        Member newMember = new Member("10.0.0.1", 7848, 9848, 7849, 7850);
        GossipMessage ping = GossipMessage.ping(newMember, Collections.emptyList());

        gossipProtocol.handlePing(ping);

        Member found = memberManager.getMember("10.0.0.1:7848");
        assertNotNull(found, "New member should be added to memberManager");
        assertEquals("10.0.0.1", found.getAddress());
        assertEquals(7848, found.getPort());
    }

    @Test
    void handlePing_returnsAckWithMembers() {
        Member newMember = new Member("10.0.0.1", 7848, 9848, 7849, 7850);
        GossipMessage ping = GossipMessage.ping(newMember, Collections.emptyList());

        GossipMessage ack = gossipProtocol.handlePing(ping);

        assertNotNull(ack);
        assertEquals(GossipMessage.Type.ACK, ack.getType());
        assertNotNull(ack.getMembers());
        // Should contain at least self and the newly added member
        assertTrue(ack.getMembers().size() >= 2,
                "ACK should contain at least self and the new member");
    }

    @Test
    void join_sendsPingToSeeds() {
        Member seedMember = new Member("10.0.0.2", 7848, 9848, 7849, 7850);
        GossipMessage ack = GossipMessage.ack(seedMember, new ArrayList<>(), Collections.emptyList());
        when(transport.sendPing(eq("10.0.0.2"), eq(7848), any(GossipMessage.class))).thenReturn(ack);

        gossipProtocol.join(List.of("10.0.0.2:7848"));

        verify(transport).sendPing(eq("10.0.0.2"), eq(7848), any(GossipMessage.class));
    }

    @Test
    void join_addsMembersFromAck() {
        Member seedMember = new Member("10.0.0.2", 7848, 9848, 7849, 7850);
        Member otherMember = new Member("10.0.0.3", 7848, 9848, 7849, 7850);
        List<Member> ackMembers = List.of(seedMember, otherMember);
        GossipMessage ack = GossipMessage.ack(seedMember, ackMembers, Collections.emptyList());
        when(transport.sendPing(eq("10.0.0.2"), eq(7848), any(GossipMessage.class))).thenReturn(ack);

        gossipProtocol.join(List.of("10.0.0.2:7848"));

        assertNotNull(memberManager.getMember("10.0.0.2:7848"),
                "Seed member should be added from ACK member list");
        assertNotNull(memberManager.getMember("10.0.0.3:7848"),
                "Other member from ACK should be added");
    }

    @Test
    void join_skipsOwnAddress() {
        String selfId = selfMember.getId(); // "127.0.0.1:7848"

        gossipProtocol.join(List.of(selfId));

        verify(transport, never()).sendPing(anyString(), anyInt(), any(GossipMessage.class));
    }

    @Test
    void leave_marksSelfDead() {
        gossipProtocol.leave();

        assertEquals(Member.State.DEAD, selfMember.getState(),
                "Self member should be marked DEAD after leave()");
    }

    @Test
    void leave_sendsPingsToAliveMembers() {
        Member peer1 = new Member("10.0.0.1", 7848, 9848, 7849, 7850);
        Member peer2 = new Member("10.0.0.2", 7848, 9848, 7849, 7850);
        memberManager.addOrUpdate(peer1);
        memberManager.addOrUpdate(peer2);

        gossipProtocol.leave();

        verify(transport, atLeastOnce()).sendPing(anyString(), anyInt(), any(GossipMessage.class));
    }
}
