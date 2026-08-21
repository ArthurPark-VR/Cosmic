package provider.wz;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataTool;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reads one shared WZ document from many threads at once.
 *
 * <p>This is the shape the server actually runs: a long-lived root like ItemInformationProvider's
 * Eqp.img is parsed once and then read from every thread that needs an item's name - client
 * handlers, the scheduler, and a tick wheel driving a couple of thousand bots.
 *
 * <p>A DOM read is not read-only. Xerces builds child-list state lazily on first access, so
 * concurrent readers of one node race to build and invalidate it. Before the per-document lock in
 * XMLDomMapleData this failed reliably here, with the same NullPointerException seen in the server
 * log:
 *
 * <pre>
 *   Cannot read field "fChild" because "this.fNodeListCache" is null
 *       at ...ParentNode.nodeListGetLength
 *       at XMLDomMapleData.getChildByPath
 * </pre>
 *
 * <p>Requires the repo {@code wz/} directory.
 */
class XMLDomMapleDataConcurrencyTest {

    private static final int THREADS = 16;
    private static final int READS_PER_THREAD = 400;

    @Test
    void manyThreadsReadingOneDocumentDoNotCorruptIt() throws Exception {
        Path img = Path.of("wz", "String.wz", "Eqp.img.xml").toAbsolutePath();
        assumeTrue(Files.exists(img), "needs the repo wz/ directory");

        final Data root;
        try (FileInputStream fis = new FileInputStream(img.toFile())) {
            root = new XMLDomMapleData(fis, img.getParent());
        }

        // Real item ids off the document itself, so this stays true whatever the WZ contains.
        Data eqp = root.getChildByPath("Eqp");
        List<String> categories = eqp.getChildren().stream().map(Data::getName).toList();
        assertTrue(categories.size() > 1, "expected several equip categories in Eqp.img");

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicInteger namesRead = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            // Every thread walks the SAME shared root. Staggering the starting category rather than
            // giving each thread its own keeps them colliding on the same interior nodes, which is
            // where the lazily-built child-list state lives.
            final int offset = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < READS_PER_THREAD; i++) {
                        String category = categories.get((offset + i) % categories.size());
                        Data node = root.getChildByPath("Eqp/" + category);
                        if (node == null) {
                            continue;
                        }
                        for (Data item : node.getChildren()) {
                            // getString walks back into the document - the call the server makes
                            // through ItemInformationProvider.getName.
                            DataTool.getString("name", item, null);
                            namesRead.incrementAndGet();
                            break;   // one per pass; the point is the traversal, not the volume
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            }, "wz-reader-" + t);
            thread.setDaemon(true);
            thread.start();
        }

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS),
                "readers did not finish - a corrupted DOM can spin rather than throw");

        assertEquals(List.of(), failures.stream().map(Throwable::toString).toList(),
                "concurrent readers of one WZ document must not fail");
        assertTrue(namesRead.get() > 0, "expected to have actually read some item names");
    }

    /**
     * The lock is per document, so two separately parsed copies must not serialise against each
     * other - they share no state, and making every WZ read in the server contend on one monitor
     * would be a different bug.
     */
    @Test
    void separateDocumentsDoNotShareALock() throws Exception {
        Path img = Path.of("wz", "String.wz", "Etc.img.xml").toAbsolutePath();
        assumeTrue(Files.exists(img), "needs the repo wz/ directory");

        final Data a;
        final Data b;
        try (FileInputStream fis = new FileInputStream(img.toFile())) {
            a = new XMLDomMapleData(fis, img.getParent());
        }
        try (FileInputStream fis = new FileInputStream(img.toFile())) {
            b = new XMLDomMapleData(fis, img.getParent());
        }

        List<Throwable> failures = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch done = new CountDownLatch(2);
        for (Data doc : List.of(a, b)) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < 200; i++) {
                        doc.getChildren();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "independent documents blocked each other");
        assertEquals(List.of(), failures.stream().map(Throwable::toString).toList());
    }
}
