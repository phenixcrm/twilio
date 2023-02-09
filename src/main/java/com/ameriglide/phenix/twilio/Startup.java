package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.core.ExecutorServices;
import com.ameriglide.phenix.core.Log;
import com.twilio.rest.taskrouter.v1.workspace.Worker;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.annotation.WebListener;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.ameriglide.phenix.types.WorkerState.OFFLINE;

@WebListener
public class Startup extends com.ameriglide.phenix.servlet.Startup {
  private static final Log log = new Log();
  private final ScheduledExecutorService executor;

  public Startup() {
    executor = Executors.newSingleThreadScheduledExecutor();
  }

  public void schedule() {

    var nextRun = LocalDateTime.now().plusDays(1).toLocalDate().atStartOfDay();
    var now = LocalDateTime.now();
    var secs = ChronoUnit.SECONDS.between(now, nextRun) + 5;
    log.info(() -> "Scheduling next midnight logout in %d sec".formatted(secs));
    executor.schedule(() -> {
      log.info(() -> "Automatically logging out active workers");
      Startup.router
        .getWorkers()
        .filter(Worker::getAvailable)
        .peek(worker -> log.warn(() -> "Setting %s to offline".formatted(worker.getFriendlyName())))
        .forEach(worker -> router.setActivity(worker.getSid(), OFFLINE.activity()));
      schedule();

    }, secs, TimeUnit.SECONDS);


  }

  @Override
  public void contextInitialized(final ServletContextEvent sce) {
    super.contextInitialized(sce);
    schedule();

  }

  @Override
  public void contextDestroyed(final ServletContextEvent sce) {
    super.contextDestroyed(sce);
    ExecutorServices.shutdown("Midnight Logout", executor);

  }
}
