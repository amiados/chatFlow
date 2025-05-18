package utils;

import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import model.MessageDAO;
import org.bridj.util.Pair;
import security.AES_GCM;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for re-encrypting chat history using a thread pool.
 */
public class ReencryptionService {
    private static final Logger logger = Logger.getLogger(ReencryptionService.class.getName());

    private final MessageDAO messageDAO;
    private final int batchSize;
    private final ExecutorService pool;
    private final long batchTimeoutSeconds;

    public ReencryptionService(int batchSize, MessageDAO messageDAO, int threadCount, long batchTimeoutSeconds) {
        this.batchSize = batchSize;
        this.messageDAO = messageDAO;
        this.pool = Executors.newFixedThreadPool(threadCount);
        this.batchTimeoutSeconds = batchTimeoutSeconds;
    }

    /**
     * Gracefully shuts down the service, waiting for tasks to complete.
     */
    public void shutdown() throws InterruptedException {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(1, TimeUnit.MINUTES)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Re-encrypts all messages in the given chat.
     *
     * @param chatId        the chat ID
     * @param oldKeys  old round keys for decryption
     * @param newKeys  new round keys for encryption
     * @param aadOld        old AAD bytes
     * @param aadNew        new AAD bytes
     * @throws SQLException if database access fails
     */
    public void reencrypt(UUID chatId,
                          byte[][] oldKeys,
                          byte[][] newKeys,
                          byte[] aadOld,
                          byte[] aadNew)
            throws SQLException, InterruptedException {

        int offset = 0;
        while (true) {
            // 1. load one page of (id, oldCipher)
            List<Pair<UUID, byte[]>> page = messageDAO.fetchContentBatch(chatId, offset, batchSize);
            if (page.isEmpty()) break;

            // 2. build tasks: decrypt → encrypt → return (id, newCipher)
            List<Callable<List<Pair<UUID, byte[]>>>> tasks = new ArrayList<>();
            for (var p : page) {
                tasks.add(() -> {
                    byte[] plain = AES_GCM.decrypt(p.getValue(), aadOld, oldKeys);
                    byte[] cipher = AES_GCM.encrypt(plain, aadNew, newKeys);
                    return List.of(new Pair<>(p.getKey(), cipher));
                });
            }

            // 3. invoke all with timeout
            List<Future<List<Pair<UUID, byte[]>>>> futures =
                    pool.invokeAll(tasks, batchTimeoutSeconds, TimeUnit.SECONDS);

            // 4. collect results and batch-update
            List<Pair<UUID, byte[]>> toUpdate = new ArrayList<>();
            for (Future<List<Pair<UUID, byte[]>>> f : futures) {
                if (f.isCancelled()) {
                    logger.warning("Re-encrypt task timed out");
                } else {
                    try {
                        toUpdate.addAll(f.get());
                    } catch (ExecutionException e) {
                        logger.log(Level.WARNING, "Error in re-encrypt task", e.getCause());
                    }
                }
            }
            // 5. write back in one batch
            messageDAO.updateContentBatch(toUpdate);

            offset += batchSize;
        }
        logger.info("Re-encryption completed");
    }
}
