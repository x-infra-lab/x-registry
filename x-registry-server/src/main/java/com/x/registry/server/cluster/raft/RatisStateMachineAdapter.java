package com.x.registry.server.cluster.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

public class RatisStateMachineAdapter extends BaseStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RatisStateMachineAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigStateMachine configStateMachine;
    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();

    public RatisStateMachineAdapter(ConfigStateMachine configStateMachine) {
        this.configStateMachine = configStateMachine;
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
        super.initialize(server, groupId, raftStorage);
        storage.init(raftStorage);
        loadSnapshot(storage.getLatestSnapshot());
    }

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        LogEntryProto entry = trx.getLogEntry();
        long index = entry.getIndex();
        long term = entry.getTerm();

        try {
            ByteString logData = entry.getStateMachineLogEntry().getLogData();
            byte[] bytes = logData.toByteArray();
            RatisLogEntry ratisEntry = MAPPER.readValue(bytes, RatisLogEntry.class);

            LogEntry logEntry = new LogEntry(index, term, ratisEntry.type(), ratisEntry.data());
            configStateMachine.onApply(logEntry);

            updateLastAppliedTermIndex(term, index);
            return CompletableFuture.completedFuture(Message.EMPTY);
        } catch (Exception e) {
            log.error("Failed to apply transaction at index {}", index, e);
            return CompletableFuture.completedFuture(Message.EMPTY);
        }
    }

    @Override
    public long takeSnapshot() throws IOException {
        byte[] snapshotData = configStateMachine.onSnapshotSave();
        if (snapshotData == null || snapshotData.length == 0) {
            return getLastAppliedTermIndex() != null ? getLastAppliedTermIndex().getIndex() : 0;
        }

        long term = getLastAppliedTermIndex() != null ? getLastAppliedTermIndex().getTerm() : 0;
        long index = getLastAppliedTermIndex() != null ? getLastAppliedTermIndex().getIndex() : 0;

        File snapshotFile = storage.getSnapshotFile(term, index);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(snapshotFile.toPath()))) {
            out.write(snapshotData);
        }
        FileInfo fileInfo = new FileInfo(snapshotFile.toPath(), null);
        storage.updateLatestSnapshot(new SingleFileSnapshotInfo(fileInfo, term, index));
        log.info("Snapshot saved at term={}, index={}, size={}", term, index, snapshotData.length);
        return index;
    }

    private void loadSnapshot(SingleFileSnapshotInfo snapshotInfo) {
        if (snapshotInfo == null) {
            return;
        }
        File snapshotFile = snapshotInfo.getFile().getPath().toFile();
        if (!snapshotFile.exists()) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(snapshotFile.toPath());
            configStateMachine.onSnapshotLoad(data);
            setLastAppliedTermIndex(snapshotInfo.getTermIndex());
            log.info("Snapshot loaded: term={}, index={}", snapshotInfo.getTerm(), snapshotInfo.getIndex());
        } catch (Exception e) {
            log.error("Failed to load snapshot from {}", snapshotFile, e);
        }
    }

    public void notifyLeaderChanged(boolean isLeader, long term) {
        if (isLeader) {
            configStateMachine.onLeaderStart(term);
        } else {
            configStateMachine.onLeaderStop();
        }
    }

    record RatisLogEntry(LogEntry.Type type, byte[] data) {}
}
