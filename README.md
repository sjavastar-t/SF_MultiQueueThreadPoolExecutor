## Multithreading tasks
### Custom thread pool

Кастомный пул потоков на Java с настраиваемыми очередями и параметрами. 
Реализован в виде классов `MultiQueueThreadPoolExecutor` и `QueueWorker`, поддерживает интерфейс `CustomExecutor`.



## Тестирование

Запуск `Main.java` демонстрирует базовую работу пула, например с параметрами:

```
corePoolSize = 7
maxPoolSize  = 16
queueSize    = 10
keepAliveTime= 5 секунд
minSpareThreads = 2
```

В консоли выводятся логи о приёме и выполнении задач, а также итоговая статистика: число выполненных/отклонённых задач и среднее время на задачу.

## Отчёт

Сохранены логи для разных пулов (2, 4, 6, 8, 10 cores)

Лучшие результаты получены для пулов, содержащих максимальное количество потоков от 4 * cores, где

```
int cores = Runtime.getRuntime().availableProcessors();
var executor_4_default_cors = new MultiQueueThreadPoolExecutor(cores - 1, 4 * cores, 10, 5, TimeUnit.SECONDS, 2);
logStats(test(1000, 300, 10, executor_4_default_cors));
```

## Основные функции

* **Round-Robin** распределение задач по нескольким очередям.
* **Настраиваемые параметры пула**: `corePoolSize`, `maxPoolSize`, `queueSize`, `keepAliveTime`, `minSpareThreads`.
* **Интерфейс** `CustomExecutor` (`execute`, `submit`, `shutdown`, `shutdownNow`).
* **Обработка отказов**: выброс `RejectedExecutionException` при переполнении очереди.
* **Логирование** ключевых событий: создание/запуск/завершение потоков, приём и выполнение задач, таймаут бездействия.
* **Graceful shutdown**: мягкое и принудительное завершение воркеров.

## Технологии

* Java 21
* Maven
* SLF4J API + Logback

