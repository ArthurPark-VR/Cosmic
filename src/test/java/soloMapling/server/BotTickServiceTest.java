package soloMapling.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The wheel semantics every bot depends on (audit invariants 1-2 plus the nudge
// handoff): ticks never overlap for one bot, the steady period is measured from
// tick COMPLETION, and a nudge/reschedule that lands while a tick is in flight is
// consumed at that tick's completion instead of being lost. The tests run against
// the real 100ms driver, so timing assertions use generous margins - they check
// orderings and bounds, never exact instants.
class BotTickServiceTest {

    // High ids far away from anything a real spawn could use; each test takes its own.
    private static final int BASE_ID = 9_900_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(BASE_ID);

    private final int botId = NEXT_ID.incrementAndGet();

    @AfterEach
    void cleanup() {
        BotTickService.unregister(botId);
    }

    @Test
    void ticksNeverOverlapForOneBot() throws Exception {
        AtomicInteger inTick = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger ticks = new AtomicInteger();

        // Period far below the tick's own duration: without the CAS guard the driver
        // would happily dispatch a second run while the first still sleeps.
        BotTickService.register(botId, () -> {
            int now = inTick.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            ticks.incrementAndGet();
            sleep(300);
            inTick.decrementAndGet();
        }, 0, 50);

        sleep(1500);

        assertTrue(ticks.get() >= 2, "expected repeated ticks, got " + ticks.get());
        assertEquals(1, maxConcurrent.get(), "two ticks ran concurrently for one bot");
    }

    @Test
    void periodIsMeasuredFromTickCompletion() throws Exception {
        List<Long> startTimes = new CopyOnWriteArrayList<>();

        // 300ms of work + 300ms period: fixed-delay semantics put consecutive STARTS
        // at least ~600ms apart. Fixed-rate (the wrong semantics) would fire every 300ms.
        BotTickService.register(botId, () -> {
            startTimes.add(System.currentTimeMillis());
            sleep(300);
        }, 0, 300);

        sleep(2500);
        BotTickService.unregister(botId);

        assertTrue(startTimes.size() >= 3, "expected at least 3 ticks, got " + startTimes.size());
        for (int i = 1; i < startTimes.size(); i++) {
            long gap = startTimes.get(i) - startTimes.get(i - 1);
            assertTrue(gap >= 550, "tick " + i + " started " + gap
                    + "ms after the previous start; completion-measured delay requires >= ~600ms");
        }
    }

    @Test
    void nudgeDuringInFlightTickIsConsumedAtCompletion() throws Exception {
        CountDownLatch firstTickRunning = new CountDownLatch(1);
        CountDownLatch secondTick = new CountDownLatch(2);

        // Steady period is a minute - only the pending handoff can produce a second
        // tick inside the test window.
        BotTickService.register(botId, () -> {
            firstTickRunning.countDown();
            secondTick.countDown();
            sleep(400);
        }, 0, 60_000);

        assertTrue(firstTickRunning.await(3, TimeUnit.SECONDS), "first tick never ran");
        BotTickService.nudge(botId, 0, 60_000); // lands while the tick is mid-flight

        assertTrue(secondTick.await(3, TimeUnit.SECONDS),
                "nudge sent during an in-flight tick was lost; bot stayed parked on its 60s period");
    }

    @Test
    void nudgePullsForwardAnIdleBot() throws Exception {
        CountDownLatch ticked = new CountDownLatch(1);

        BotTickService.register(botId, ticked::countDown, 60_000, 60_000);
        // Undisturbed, the first tick is a minute away.
        assertFalse(ticked.await(300, TimeUnit.MILLISECONDS), "tick fired before the nudge");

        BotTickService.nudge(botId, 100, 60_000);
        assertTrue(ticked.await(3, TimeUnit.SECONDS), "nudge did not pull the idle bot forward");
    }

    @Test
    void registerIsKeepIfPresent() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        BotTickService.register(botId, first::incrementAndGet, 0, 150);
        sleep(400);
        assertTrue(first.get() >= 1, "original tick never ran");

        // Mirrors the old startScheduledTask contract: a live registration is left alone.
        BotTickService.register(botId, second::incrementAndGet, 0, 150);
        sleep(600);

        assertEquals(0, second.get(), "re-register replaced a live entry");
        assertTrue(first.get() >= 2, "original tick stopped after re-register");
    }

    @Test
    void unregisterStopsTicking() throws Exception {
        AtomicInteger ticks = new AtomicInteger();

        BotTickService.register(botId, ticks::incrementAndGet, 0, 100);
        sleep(500);
        assertTrue(ticks.get() >= 1, "bot never ticked");

        BotTickService.unregister(botId);
        assertFalse(BotTickService.isRegistered(botId));
        sleep(250); // let any in-flight tick drain
        int settled = ticks.get();
        sleep(600);
        assertEquals(settled, ticks.get(), "bot kept ticking after unregister");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
