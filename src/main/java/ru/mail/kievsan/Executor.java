package ru.mail.kievsan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;


public interface Executor extends CustomExecutor {

    Logger log = LoggerFactory.getLogger(Executor.class);

    @Override
    void execute(Runnable command);

    default <T> Future<T> submit(Callable<T> task) {
        validateTask(task);
        RunnableFuture<T> futureTask = new FutureTask<>(task);
        execute(futureTask);
        return futureTask;
    }

    default <T> Future<T> submit(Runnable task, T result) {
        validateTask(task);
        RunnableFuture<T> futureTask = new FutureTask<>(task, result);
        execute(futureTask);
        return futureTask;
    }

    default Future<?> submit(Runnable task) {
        validateTask(task);
        RunnableFuture<Void> futureTask = new FutureTask<>(task, null);
        execute(futureTask);
        return futureTask;}

    private void validateTask(Object task) {
        try {
            if (isShutdown()) throw new RejectedExecutionException("the thread pool is completed!");
            if (task == null) throw new NullPointerException();
        } catch (Exception e) {
            String errMsg = String.format("The task was rejected:\t%s", e.getMessage());
            log.warn(errMsg);
            throw new RuntimeException(errMsg);
        }
    }

    void shutdown();

    /**
     * Принудительное завершение работы пула — отменяет все потоки.
     * Текущие задачи прерываются, если это возможно.
     */
    void shutdownNow();

    boolean isShutdown();
}
