package com.x.registry.server.cluster;

import com.x.registry.server.cluster.MemberManager.MemberEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemberManagerTest {

    private MemberManager memberManager;
    private Member self;
    private List<MemberEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        self = new Member("127.0.0.1", 8080, 9090, 9091, 9092);
        memberManager = new MemberManager(self);
        capturedEvents = new ArrayList<>();
        memberManager.addListener(capturedEvents::add);
    }

    @Test
    void constructorSetsSelfMemberCorrectly() {
        assertSame(self, memberManager.getSelf());
        assertEquals(1, memberManager.getSize());
        assertEquals("127.0.0.1:8080", self.getId());
        assertEquals(Member.State.ALIVE, self.getState());
        assertSame(self, memberManager.getMember("127.0.0.1:8080"));
    }

    @Test
    void addOrUpdateNewMemberFiresJoinedEvent() {
        Member other = new Member("192.168.1.1", 8080, 9090, 9091, 9092);

        memberManager.addOrUpdate(other);

        assertEquals(2, memberManager.getSize());
        assertSame(other, memberManager.getMember("192.168.1.1:8080"));
        assertEquals(1, capturedEvents.size());
        assertEquals(MemberEvent.Type.JOINED, capturedEvents.get(0).getType());
        assertSame(other, capturedEvents.get(0).getMember());
    }

    @Test
    void addOrUpdateDeadMemberComingBackAliveFiresJoinedEvent() {
        Member other = new Member("192.168.1.1", 8080, 9090, 9091, 9092);
        memberManager.addOrUpdate(other);
        memberManager.markDead("192.168.1.1:8080");
        capturedEvents.clear();

        Member rejoining = new Member("192.168.1.1", 8080, 9090, 9091, 9092);
        rejoining.setIncarnation(5);
        memberManager.addOrUpdate(rejoining);

        Member stored = memberManager.getMember("192.168.1.1:8080");
        assertEquals(Member.State.ALIVE, stored.getState());
        assertEquals(5, stored.getIncarnation());
        assertEquals(1, capturedEvents.size());
        assertEquals(MemberEvent.Type.JOINED, capturedEvents.get(0).getType());
    }

    @Test
    void addOrUpdateWithHigherIncarnationUpdatesMember() {
        Member other = new Member("192.168.1.1", 8080, 9090, 9091, 9092);
        memberManager.addOrUpdate(other);
        capturedEvents.clear();

        Member updated = new Member("192.168.1.1", 8080, 9090, 9091, 9092);
        updated.setIncarnation(10);
        memberManager.addOrUpdate(updated);

        Member stored = memberManager.getMember("192.168.1.1:8080");
        assertEquals(10, stored.getIncarnation());
        assertTrue(capturedEvents.isEmpty(), "No event should fire for a simple incarnation update");
    }

    @Test
    void markSuspectChangesStateAndFiresSuspectedEvent() {
        Member other = new Member("192.168.1.1", 8080, 9090, 9091, 9092);
        memberManager.addOrUpdate(other);
        capturedEvents.clear();

        memberManager.markSuspect("192.168.1.1:8080");

        assertEquals(Member.State.SUSPECT, other.getState());
        assertEquals(1, capturedEvents.size());
        assertEquals(MemberEvent.Type.SUSPECTED, capturedEvents.get(0).getType());
        assertSame(other, capturedEvents.get(0).getMember());
    }

    @Test
    void markDeadChangesStateAndFiresLeftEvent() {
        Member other = new Member("192.168.1.1", 8080, 9090, 9091, 9092);
        memberManager.addOrUpdate(other);
        capturedEvents.clear();

        memberManager.markDead("192.168.1.1:8080");

        assertEquals(Member.State.DEAD, other.getState());
        assertEquals(1, capturedEvents.size());
        assertEquals(MemberEvent.Type.LEFT, capturedEvents.get(0).getType());
        assertSame(other, capturedEvents.get(0).getMember());
    }

    @Test
    void markAliveRecoversSuspectMemberAndFiresJoinedEvent() {
        Member other = new Member("192.168.1.1", 8080, 9090, 9091, 9092);
        memberManager.addOrUpdate(other);
        memberManager.markSuspect("192.168.1.1:8080");
        long incarnationBefore = other.getIncarnation();
        capturedEvents.clear();

        memberManager.markAlive("192.168.1.1:8080");

        assertEquals(Member.State.ALIVE, other.getState());
        assertEquals(incarnationBefore + 1, other.getIncarnation());
        assertEquals(1, capturedEvents.size());
        assertEquals(MemberEvent.Type.JOINED, capturedEvents.get(0).getType());
    }

    @Test
    void markAliveRecoversDeadMemberAndFiresJoinedEvent() {
        Member other = new Member("192.168.1.1", 8080, 9090, 9091, 9092);
        memberManager.addOrUpdate(other);
        memberManager.markDead("192.168.1.1:8080");
        long incarnationBefore = other.getIncarnation();
        capturedEvents.clear();

        memberManager.markAlive("192.168.1.1:8080");

        assertEquals(Member.State.ALIVE, other.getState());
        assertEquals(incarnationBefore + 1, other.getIncarnation());
        assertEquals(1, capturedEvents.size());
        assertEquals(MemberEvent.Type.JOINED, capturedEvents.get(0).getType());
    }

    @Test
    void getAliveMembersReturnsOnlyAliveMembers() {
        Member alive1 = new Member("10.0.0.1", 8080, 9090, 9091, 9092);
        Member alive2 = new Member("10.0.0.2", 8080, 9090, 9091, 9092);
        Member dead = new Member("10.0.0.3", 8080, 9090, 9091, 9092);
        memberManager.addOrUpdate(alive1);
        memberManager.addOrUpdate(alive2);
        memberManager.addOrUpdate(dead);
        memberManager.markDead("10.0.0.3:8080");

        List<Member> aliveMembers = memberManager.getAliveMembers();

        assertEquals(3, aliveMembers.size()); // self + alive1 + alive2
        assertTrue(aliveMembers.contains(self));
        assertTrue(aliveMembers.contains(alive1));
        assertTrue(aliveMembers.contains(alive2));
        assertFalse(aliveMembers.contains(dead));
    }

    @Test
    void getAliveMembersExcludingSelfExcludesSelf() {
        Member alive1 = new Member("10.0.0.1", 8080, 9090, 9091, 9092);
        memberManager.addOrUpdate(alive1);

        List<Member> result = memberManager.getAliveMembersExcludingSelf();

        assertEquals(1, result.size());
        assertFalse(result.contains(self));
        assertTrue(result.contains(alive1));
    }

    @Test
    void selfMemberCannotBeMarkedSuspect() {
        memberManager.markSuspect(self.getId());

        assertEquals(Member.State.ALIVE, self.getState());
        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    void selfMemberCannotBeMarkedDead() {
        memberManager.markDead(self.getId());

        assertEquals(Member.State.ALIVE, self.getState());
        assertTrue(capturedEvents.isEmpty());
    }
}
