package com.x.registry.server.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MemberManager {

    private static final Logger log = LoggerFactory.getLogger(MemberManager.class);

    private final Member self;
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final List<Consumer<MemberEvent>> listeners = new CopyOnWriteArrayList<>();

    public MemberManager(Member self) {
        this.self = self;
        members.put(self.getId(), self);
    }

    public Member getSelf() {
        return self;
    }

    public Member getMember(String memberId) {
        return members.get(memberId);
    }

    public Collection<Member> getAllMembers() {
        return Collections.unmodifiableCollection(members.values());
    }

    public List<Member> getAliveMembers() {
        return members.values().stream()
                .filter(m -> m.getState() == Member.State.ALIVE)
                .collect(Collectors.toList());
    }

    public List<Member> getAliveMembersExcludingSelf() {
        return members.values().stream()
                .filter(m -> m.getState() == Member.State.ALIVE && !m.getId().equals(self.getId()))
                .collect(Collectors.toList());
    }

    public void addOrUpdate(Member member) {
        Member existing = members.get(member.getId());
        if (existing == null) {
            members.put(member.getId(), member);
            log.info("New member joined: {}", member);
            fireEvent(new MemberEvent(MemberEvent.Type.JOINED, member));
        } else if (existing.getState() == Member.State.DEAD && member.getState() == Member.State.ALIVE) {
            existing.setState(Member.State.ALIVE);
            existing.setIncarnation(member.getIncarnation());
            existing.setGrpcPort(member.getGrpcPort());
            existing.setDistroPort(member.getDistroPort());
            existing.setRaftPort(member.getRaftPort());
            log.info("Member rejoined: {}", existing);
            fireEvent(new MemberEvent(MemberEvent.Type.JOINED, existing));
        } else if (member.getIncarnation() > existing.getIncarnation()) {
            existing.setIncarnation(member.getIncarnation());
            if (existing.getState() != member.getState()) {
                existing.setState(member.getState());
            }
        }
    }

    public void markSuspect(String memberId) {
        Member member = members.get(memberId);
        if (member != null && member.getState() == Member.State.ALIVE && !member.getId().equals(self.getId())) {
            member.setState(Member.State.SUSPECT);
            log.warn("Member suspected: {}", member);
            fireEvent(new MemberEvent(MemberEvent.Type.SUSPECTED, member));
        }
    }

    public void markDead(String memberId) {
        Member member = members.get(memberId);
        if (member != null && member.getState() != Member.State.DEAD && !member.getId().equals(self.getId())) {
            member.setState(Member.State.DEAD);
            log.error("Member declared dead: {}", member);
            fireEvent(new MemberEvent(MemberEvent.Type.LEFT, member));
        }
    }

    public void markAlive(String memberId) {
        Member member = members.get(memberId);
        if (member != null && member.getState() != Member.State.ALIVE) {
            member.setState(Member.State.ALIVE);
            member.incrementIncarnation();
            log.info("Member recovered: {}", member);
            fireEvent(new MemberEvent(MemberEvent.Type.JOINED, member));
        }
    }

    public void removeMember(String memberId) {
        Member removed = members.remove(memberId);
        if (removed != null) {
            log.info("Member removed: {}", removed);
        }
    }

    public int getSize() {
        return members.size();
    }

    public void addListener(Consumer<MemberEvent> listener) {
        listeners.add(listener);
    }

    private void fireEvent(MemberEvent event) {
        for (Consumer<MemberEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Error in member event listener", e);
            }
        }
    }

    public static class MemberEvent {
        public enum Type { JOINED, LEFT, SUSPECTED }

        private final Type type;
        private final Member member;

        public MemberEvent(Type type, Member member) {
            this.type = type;
            this.member = member;
        }

        public Type getType() {
            return type;
        }

        public Member getMember() {
            return member;
        }
    }
}
