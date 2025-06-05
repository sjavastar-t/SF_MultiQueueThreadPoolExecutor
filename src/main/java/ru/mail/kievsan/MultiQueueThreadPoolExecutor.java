package ru.mail.kievsan;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Кастомный пул потоков с несколькими очередями задач.
 * Реализует интерфейс CustomExecutor.
 */
@Slf4j
public class MultiQueueThreadPoolExecutor implements Executor {

    private final int corePoolSize;
    private final int maxPoolSize;
    private final int queueSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final int minSpareThreads;

    private final ReentrantReadWriteLock mainLock = new ReentrantReadWriteLock();

    private final List<QueueWorker> workers;
    private final List<BlockingQueue<Runnable>> taskQueues;

    private final AtomicInteger queueIndex = new AtomicInteger(0);
    private static final AtomicInteger rejected = new AtomicInteger(0);

    @Getter
    private boolean isShutdown = false;

    public MultiQueueThreadPoolExecutor(int corePoolSize, int maxPoolSize, int queueSize,
                                        long keepAliveTime, TimeUnit timeUnit, int minSpareThreads) {
        if (
                corePoolSize <= 0 || maxPoolSize <= 0 || maxPoolSize <= corePoolSize ||
                keepAliveTime < 0 || queueSize <= 0 || minSpareThreads <= 0
        ) throw new IllegalArgumentException("Invalid thread pool parameters");

        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.queueSize = queueSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.minSpareThreads = minSpareThreads;

        this.taskQueues = new ArrayList<>(maxPoolSize);
        this.workers = new ArrayList<>(maxPoolSize);

        log.info("Инициализация пула потоков: core={}, max={}, queueSize={}, keepAlive={} {} ...",
                corePoolSize, maxPoolSize, queueSize, keepAliveTime, timeUnit.name());
        try {
            for (int i = 0; i < maxPoolSize; i++) createWorker(i);
            log.info("Пул потоков... OK");
        } catch (Exception e) {
            log.info("Пул потоков... Fail");
            throw new RuntimeException(e);
        }
    }

    private void createWorker(int index) {
        if (index >= maxPoolSize) {
            String errMsg = "The worker thread number (" + index +
                    ") not validate. The worker creating request was rejected.";
            log.warn(errMsg);
            throw new IllegalArgumentException(errMsg);
        }

        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueSize);
        QueueWorker worker = new QueueWorker(queue, index, keepAliveTime, timeUnit);

        final ReentrantReadWriteLock.WriteLock mainLock = this.mainLock.writeLock();
        mainLock.lock();
        try {
            taskQueues.add(queue);
            workers.add(worker);
        } finally {
            mainLock.unlock();
        }

//        worker.getThread().start();
    }

    @Override
    public void execute(Runnable command) {
        if (isShutdown) {
            String errMsg = "The thread pool is completed. The task was rejected.";
            log.warn(errMsg);
            throw new RejectedExecutionException(errMsg);
        }

        dynamicWorkersControl();

        // выбираем по кругу работающий поток
        int index, counter = 0;
        do {
            if (++counter == maxPoolSize) {
                // если нет работающих потоков
                workers.get(queueIndex.get()).start();
            }
            index = (queueIndex.get()) % maxPoolSize;
            //log.info("проверяю 'Worker-{}':\t{}", index, workers.get(index).isRunning());
            queueIndex.set(index + 1);

        } while (workers.get(index).getThread() == null || !workers.get(index).getThread().isAlive());

        // Пробуем добавить задачу этому потоку
        BlockingQueue<Runnable> queue = taskQueues.get(index);
        if (!queue.offer(command)) {
            if (rejected.incrementAndGet() > 1 && startSpareWorker())
                rejected.set(0);
            String errMsg = String.format("The task queue of 'Worker-%d' is full, and the task has been rejected.", index);
            log.warn(errMsg);
            throw new RejectedExecutionException(errMsg);
        }

        log.info("Задача отправлена в очередь {}", index);
    }

    private void dynamicWorkersControl() {

        while (getRunningWorkerCount() < corePoolSize) {
            // Если число работающих потоков недостаточно
            if (startSpareWorker() || startDeactivatedWorker()) continue;
            log.error("Все выделенные потоки в работе ({} из {}), но так и не достигнуто их целевое количество ({} из {})",
                    getRunningWorkerCount(), maxPoolSize, getRunningWorkerCount(), corePoolSize);
            break;
        }

        while (getSpareWorkerCount() < minSpareThreads) {
            // Если в резерве недостаточно потоков
            if (activateWorker()) continue;
            log.warn("недостаточно резервных потоков ({} из {}), работают {} из {}",
                    getSpareWorkerCount(), minSpareThreads, getRunningWorkerCount(), maxPoolSize);
            break;
        }

        while (getSpareWorkerCount() > minSpareThreads) {
            // Если в резерве стало больше потоков, чем нужно
            if (deactivateSpareWorker()) continue;
            log.warn("слишком много резервных потоков ({} из {})", getSpareWorkerCount(), maxPoolSize);
            break;
        }
    }

    public boolean startSpareWorker() {
        // Запуск резервного worker
        AtomicBoolean result = new AtomicBoolean(false);
        getAnySpareWorker().ifPresent(worker -> {
            final ReentrantReadWriteLock.WriteLock mainLock = this.mainLock.writeLock();
            mainLock.lock();
            try {
                worker.start();
                log.info("'{}'\t({})\t{}\t({} running workers...)",
                        worker.getThread().getName(), worker.getThread().getState().name(),
                        worker.isRunning() ? "running" : "deactivated", getRunningWorkerCount());
            } finally {
                mainLock.unlock();
            }
            result.set(true);
        });
        return result.get();
    }

    public boolean startDeactivatedWorker() {
        AtomicBoolean result = new AtomicBoolean(false);
        getAnyDeactivatedWorker().ifPresent(worker -> {
            final ReentrantReadWriteLock.WriteLock mainLock = this.mainLock.writeLock();
            mainLock.lock();
            try {
                worker.totalActivate();
                worker.start();
            } finally {
                mainLock.unlock();
            }
            log.info("деактивированный '{}' запущен", worker.getThread().getName());
            result.set(true);
        });
        return result.get();
    }

    public boolean activateWorker() {
        AtomicBoolean result = new AtomicBoolean(false);
        getAnyDeactivatedWorker().ifPresent(worker -> {
            final ReentrantReadWriteLock.WriteLock mainLock = this.mainLock.writeLock();
            mainLock.lock();
            try {
                worker.activate();
            } finally {
                mainLock.unlock();
            }
            log.debug("'{}' активирован и переведен в 'резерв'", worker.getThread().getName());
            result.set(true);
        });
        return result.get();
    }

    public boolean stopRunningWorker() {
        AtomicBoolean result = new AtomicBoolean(false);
        getAnyRunningWorker().ifPresent(worker -> {
            final ReentrantReadWriteLock.WriteLock mainLock = this.mainLock.writeLock();
            mainLock.lock();
            try {
                worker.stop();
            } finally {
                mainLock.unlock();
            }
            log.info("'{}' остановлен - переведен в 'резерв'", worker.getThread().getName());
            result.set(true);
        });
        return result.get();
    }

    public boolean deactivateSpareWorker() {
        AtomicBoolean result = new AtomicBoolean(false);
        getAnySpareWorker().ifPresent(worker -> {
            String workerName = worker.getThread().getName();
            final ReentrantReadWriteLock.WriteLock mainLock = this.mainLock.writeLock();
            mainLock.lock();
            try {
                worker.deactivate();
            } finally {
                mainLock.unlock();
            }
            log.info("резервный '{}' деактивирован - завершил работу после простоя, выведен из резерва", workerName);
            result.set(true);
        });
        return result.get();
    }

    @Override
    public void shutdown() {
        isShutdown = true;
        log.info("Пул переводится в режим завершения (shutdown)");
    }

    @Override
    public void shutdownNow() {
        isShutdown = true;
        log.warn("Принудительное завершение всех потоков (shutdownNow)");

        final ReentrantReadWriteLock.WriteLock mainLock = this.mainLock.writeLock();
        mainLock.lock();
        try {
            for (QueueWorker worker : workers) worker.stop();
        } finally {
            mainLock.unlock();
        }
    }

    public int getRunningWorkerCount() {
        return (int) workers.stream()
                .filter(worker -> worker.getThread() != null &&
                        worker.getThread().isAlive() &&
                        worker.isRunning())
                .count();
    }

    public Optional<QueueWorker> getAnyRunningWorker() {
        return workers.stream()
                .filter(worker -> worker.getThread() != null &&
                        worker.getThread().isAlive() &&
                        worker.isRunning())
                .findAny();
    }

    public int getSpareWorkerCount() {
        return (int) workers.stream()
                .filter(worker -> worker.isRunning() &&
                        worker.getThread() != null &&
                        worker.getThread().getState().name().equals("NEW"))
                .count();
    }

    public Optional<QueueWorker> getAnySpareWorker() {
        return workers.stream()
                .filter(worker -> worker.isRunning() &&
                        worker.getThread() != null &&
                        worker.getThread().getState().name().equals("NEW"))
                .findAny();
    }

    public int getDeactivatedWorkerCount() {
        return (int) workers.stream()
                .filter(worker -> worker.getThread() == null || !worker.isRunning())
                .count();
    }

    public Optional<QueueWorker> getAnyDeactivatedWorker() {
        return workers.stream()
                .filter(worker -> worker.getThread() == null || !worker.isRunning())
                .findAny();
    }
}
