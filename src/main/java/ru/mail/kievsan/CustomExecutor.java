package ru.mail.kievsan;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * Интерфейс пользовательского пула потоков.
 * Совместим с Executor и поддерживает submit().
 */
public interface CustomExecutor extends Executor {
    @Override
    void execute(Runnable command);

    <T> Future<T> submit(Callable<T> task);
    <T> Future<T> submit(Runnable task, T result);
    Future<?> submit(Runnable task);

    void shutdown();

    /**
     * Принудительное завершение работы пула — отменяет все потоки.
     * Текущие задачи прерываются, если это возможно.
     */
    void shutdownNow();
}


