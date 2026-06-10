package net.medievalrp.spyglass.plugin.rollback;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.jetbrains.annotations.ApiStatus;

// The withheld-head seal protocol, shared by both UndoStack backends so
// the capture semantics cannot drift between them:
//
//   - chunk 0 (the first effectsPerChunk effects) is withheld in memory
//     and only written by seal(), carrying the authoritative chunkCount;
//   - chunks 1..N-1 are flushed as appends stream in, with chunkCount=0
//     ("unsealed");
//   - readers recognise an operation solely by a chunk 0 with
//     chunkCount > 0, so a crash mid-capture leaves rows/documents no
//     reader returns, and abandon()/close() erases them eagerly.
//
// Backends supply only the storage primitives: writeChunk and
// eraseOperation. Not thread-safe; callers serialize all writer calls
// (the rollback routes them through one I/O thread).
@ApiStatus.Internal
abstract class ChunkedUndoWriter implements UndoStack.UndoWriter {

    private final int effectsPerChunk;

    private List<RollbackEffect> head;
    private List<RollbackEffect> pending = new ArrayList<>();
    private int nextStreamedIndex = 1;
    private long appended = 0;
    private boolean sealed = false;
    private boolean abandoned = false;

    ChunkedUndoWriter(int effectsPerChunk) {
        this.effectsPerChunk = Math.max(1, effectsPerChunk);
    }

    // Persists one chunk. chunkCount is 0 for streamed chunks and the
    // real total for the sealing head chunk. Implementations MAY
    // perform streamed-chunk (index > 0) writes asynchronously — chunk
    // rows are independent and carry their own index — but the head
    // chunk (index 0) write must be synchronous and durable on return.
    protected abstract void writeChunk(int chunkIndex, int chunkCount,
                                       List<RollbackEffect> effects);

    // Barrier for implementations with asynchronous streamed-chunk
    // writes: returns once every chunk handed to writeChunk is durable,
    // throwing if any failed. Called before the head chunk is written
    // (a sealed head must never publish missing chunks) and before an
    // abandon erases. Default: synchronous implementations need none.
    protected void awaitStreamedChunks() {
    }

    // Erases everything written for this operation so far (abandon).
    // streamedChunks is the count of streamed rows, i.e. indices
    // 1..streamedChunks; the head was never written.
    protected abstract void eraseOperation(int streamedChunks);

    @Override
    public final void append(List<RollbackEffect> effects) {
        ensureOpen();
        for (RollbackEffect effect : effects) {
            if (head == null) {
                head = new ArrayList<>(effectsPerChunk);
            }
            if (head.size() < effectsPerChunk) {
                head.add(effect);
            } else {
                pending.add(effect);
                if (pending.size() >= effectsPerChunk) {
                    writeChunk(nextStreamedIndex++, 0, pending);
                    pending = new ArrayList<>();
                }
            }
            appended++;
        }
    }

    @Override
    public final long appended() {
        return appended;
    }

    @Override
    public final void seal() {
        ensureOpen();
        if (!pending.isEmpty()) {
            writeChunk(nextStreamedIndex++, 0, pending);
            pending = new ArrayList<>();
        }
        // Every streamed chunk must be durable before the head goes in:
        // the head is what publishes the operation, and a published op
        // with holes would fail its replay.
        awaitStreamedChunks();
        int chunkCount = nextStreamedIndex; // indices 0..nextStreamedIndex-1
        writeChunk(0, chunkCount, head == null ? List.of() : head);
        head = null;
        sealed = true;
    }

    @Override
    public final void abandon() {
        if (sealed || abandoned) {
            return;
        }
        abandoned = true;
        head = null;
        pending = new ArrayList<>();
        try {
            awaitStreamedChunks();
        } catch (RuntimeException ignored) {
            // Abandoning anyway; in-flight failures change nothing.
        }
        if (nextStreamedIndex > 1) {
            eraseOperation(nextStreamedIndex - 1);
        }
    }

    @Override
    public final void close() {
        if (!sealed) {
            abandon();
        }
    }

    private void ensureOpen() {
        if (sealed) {
            throw new IllegalStateException("undo writer already sealed");
        }
        if (abandoned) {
            throw new IllegalStateException("undo writer abandoned");
        }
    }
}
