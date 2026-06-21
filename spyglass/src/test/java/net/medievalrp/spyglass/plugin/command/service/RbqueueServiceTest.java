package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * #151: {@code /sg rbqueue list} enumerates and parses every {@code .resume}
 * file on disk via {@link RollbackResumeStore#listPending()}. That blocking
 * disk read must run off the command (main) thread; the render bounces back.
 */
class RbqueueServiceTest {

    @Test
    void listReadsResumeStoreOffTheMainThread() {
        RollbackJobQueue queue = mock(RollbackJobQueue.class);
        when(queue.snapshot()).thenReturn(new RollbackJobQueue.Snapshot(null, List.of(), List.of()));
        RollbackResumeStore resumeStore = mock(RollbackResumeStore.class);
        when(resumeStore.listPending()).thenReturn(List.of());
        RollbackService rollbackService = mock(RollbackService.class);
        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);
        ServiceTestSupport.RecordingSupport support = new ServiceTestSupport.RecordingSupport();

        RbqueueService service = new RbqueueService(queue, resumeStore, rollbackService, support);
        service.execute(sender, "");

        // The disk read is queued on the async pool, not run inline.
        verify(resumeStore, never()).listPending();

        support.drain();
        verify(resumeStore).listPending();
        assertThat(ServiceTestSupport.plainTexts(captured))
                .anyMatch(line -> line.contains("No rollbacks running"));
    }
}
