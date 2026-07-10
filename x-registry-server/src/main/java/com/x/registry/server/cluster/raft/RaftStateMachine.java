package com.x.registry.server.cluster.raft;

/**
 * State machine interface for Raft consensus.
 * The config data state machine applies committed log entries to the config store.
 */
public interface RaftStateMachine {

    /**
     * Apply a committed log entry to the state machine.
     */
    void onApply(LogEntry entry);

    /**
     * Take a snapshot of the current state for persistence.
     */
    byte[] onSnapshotSave();

    /**
     * Restore state from a snapshot.
     */
    void onSnapshotLoad(byte[] snapshot);

    /**
     * Called when this node becomes the leader.
     */
    void onLeaderStart(long term);

    /**
     * Called when this node stops being the leader.
     */
    void onLeaderStop();
}
