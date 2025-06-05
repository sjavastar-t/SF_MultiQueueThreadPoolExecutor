package ru.mail.kievsan;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Рабочий поток, обрабатывающий задачи из своей очереди.
 * Завершается, если простаивает дольше заданного времени.
 */
@Slf4j
public class QueueWorker implements Runnable {
    @Getter
    private volatile Thread thread;
    @Getter
    private boolean running;
    @Getter
    private final int id;
    private final BlockingQueue<Runnable> taskQueue;

    private final long keepAliveTime;
    private final TimeUnit timeUnit;



    /**
     * Конструктор рабочего потока.
     *
     * @param taskQueue      очередь задач
     * @param taskQueueId       идентификатор потока
     * @param keepAliveTime  время простоя перед завершением
     * @param timeUnit       единицы измерения времени
     */
    public QueueWorker(BlockingQueue<Runnable> taskQueue, int taskQueueId, long keepAliveTime, TimeUnit timeUnit) {
        this.id = taskQueueId;
        this.taskQueue = taskQueue;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        activate();
    }

    public void totalActivate() {
        this.thread = new Thread(this, "Worker-" + id);
        log.info("'{}' поставлен в 'резерв'", this.thread.getName());
    }

    public void activate() {
        this.running = true;
        if (this.thread == null)
            totalActivate();
        if (this.thread.isAlive()) {
            log.warn("'{}' уже в работе'", this.thread.getName());
            return;
        }
        if (!this.thread.getState().name().equals("NEW"))
            totalActivate();
    }

    public void deactivate() {
        if (this.thread != null) {
            if (this.thread.isAlive()) {
                log.warn("'{}' еще в работе'", this.thread.getName());
                return;
            }
            if (this.thread.getState().name().equals("NEW")) this.thread = null;
        }
        stop();
    }

    public void start() {
        if (!this.isRunning()) throw new IllegalThreadStateException();
        try {
            this.thread.start();
        } catch (NullPointerException | IllegalThreadStateException e) {
            String errMsg = "Не удалось запустить Worker - не активирован:\t" + e.getMessage();
            log.error(errMsg);
            throw e;
        }
    }

    public void stop() {
        this.running = false;
    }

    @Override
    public void run() {
        running = true;
        log.info("{} запущен", thread.getName());
        try {
            while (running) {
                // Берем задачу (с таймаутом)
                Runnable task = taskQueue.poll(keepAliveTime, timeUnit);
                if (task != null) {
                    log.debug("'{}' получил задачу", thread.getName());
                    try {
                        task.run();
                    } catch (Exception e) {
                        String errMsg = String.format("Ошибка при выполнении задачи в %s:\t%s",
                                thread.getName(), e.getMessage());
                        log.error(errMsg);
                        throw new RejectedExecutionException(errMsg);
                    }
                } else {
                    // Если задач' не было в течение времени ожидания (после простоя)
                    stop();
                }
            }
            log.info("'{}' остановлен'", thread.getName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("'{}' был прерван ({})", thread.getName(), Thread.currentThread().getState());
        }
    }
}
