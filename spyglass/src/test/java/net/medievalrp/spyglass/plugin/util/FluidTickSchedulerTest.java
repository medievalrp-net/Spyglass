package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * FluidTickScheduler (#270) is reflective NMS, so its behavior on a live
 * server is covered by in-game verification; what unit tests can and
 * must pin is the degradation contract: when the reflection handles are
 * unavailable (as here, where the World is a mock without getHandle),
 * every pass is a silent no-op - the pre-#270 behavior - and never
 * throws into the rollback apply path.
 */
class FluidTickSchedulerTest {

    @Test
    void unavailableReflectionDegradesToNoOpWithoutThrowing() {
        World world = mock(World.class);
        assertThatCode(() -> {
            FluidTickScheduler.Pass pass = FluidTickScheduler.begin(world);
            pass.touch(0, 64, 0);
            pass.touch(0, -64, 0);
            FluidTickScheduler.touchSingle(world, 1, 64, 1);
        }).doesNotThrowAnyException();
    }
}
