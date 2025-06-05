package ru.mail.kievsan;

import lombok.extern.slf4j.Slf4j;

import java.text.DecimalFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
public class Main {

    private static final AtomicInteger rejected = new AtomicInteger(0);
    private static final AtomicInteger completed = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        int cores = Runtime.getRuntime().availableProcessors();

        var executor = new MultiQueueThreadPoolExecutor(
                cores - 1, 2 * cores, 10, 5, TimeUnit.SECONDS, 2
        );

//        logStats(test(100, 100, 10, executor));
        logStats(test(1000, 100, 10, executor));

        executor.shutdown();
    }

    private static void logStats(long resultTime) {
        double avgTime = completed.get() > 0 ? (double) resultTime / completed.get() : 0.0;
        DecimalFormat df = new DecimalFormat("#.##");

        log.info("📊 === ");
        log.info("📊 === Результаты выполнения ===");
        log.info("⏱ Общее время: {} мс", resultTime);
        log.info("✅ Выполнено задач: {}", completed.get());
        log.info("❌ Отклонено задач: {}", rejected.get());
        log.info("⏲ Среднее время на задачу: {} мс", df.format(avgTime));
        log.info("📊 === ");

        completed.set(0);
        rejected.set(0);
    }

    private static long test(int taskCount, int taskDelay, int finishDelay, Executor executor) throws InterruptedException {
        final CountDownLatch endGate = new CountDownLatch(taskCount);
        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= taskCount; i++) {
            final int taskId = i;
            try {
                executor.execute(() -> {
                    log.info("▶ Задача #{} начата", taskId);
                    try {
                        Thread.sleep(taskId % 2 == 0 ? taskDelay : taskDelay * 2L); // имитация работы
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endGate.countDown();
                        log.info("\t\ttaskID = {},\tendGate.count = {}", taskId, endGate.getCount());
                    }
                    log.info("✔ Задача #{} завершена", taskId);
                });
                completed.incrementAndGet();
            } catch (RejectedExecutionException e) {
                endGate.countDown();
                log.warn("❌ Задача #{} отклонена", taskId);
                rejected.incrementAndGet();
            }
            // интервал между задачами
            try {
                Thread.sleep(taskId % 2 == 0 ? finishDelay : finishDelay / 2L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        endGate.await();
        return  System.currentTimeMillis() - startTime;
    }
}
