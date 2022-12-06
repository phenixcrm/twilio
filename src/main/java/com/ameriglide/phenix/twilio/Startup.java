package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.core.ExecutorServices;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.topics.HudTopic;
import com.ameriglide.phenix.twilio.tasks.SyncWorkerSkills;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.rest.taskrouter.v1.workspace.Worker;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.annotation.WebListener;
import net.inetalliance.potion.Locator;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.ameriglide.phenix.common.Call.isActiveVoiceCall;
import static net.inetalliance.potion.Locator.forEach;

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
        .forEach(worker -> router.setActivity(worker.getSid(), router.offline));
      schedule();

    }, secs, TimeUnit.SECONDS);
    executor.scheduleWithFixedDelay(() -> {
      var closed = new HashSet<>();
      forEach(isActiveVoiceCall, call -> {
          var twilioCall = router.getCall(call.sid);
          var status = twilioCall.getStatus();
          switch (status) {
            case QUEUED, RINGING, IN_PROGRESS -> {
              log.trace(() -> "%s is still in progress [%s]".formatted(call.sid, status));
            }
            case COMPLETED, BUSY, FAILED, NO_ANSWER, CANCELED -> {
              Locator.update(call, "Closer", copy -> {
                log.info(() -> "Closed stuck call %s for %s".formatted(call.sid,
                  Optionals.of(call.getActiveAgent()).map(Agent::getFullName).orElse("nobody")));
                copy.setResolution(Resolution.DROPPED);
              });
              closed.add(call.sid);
            }
          }
      });
      if (!closed.isEmpty()) {
        log.info(() -> "requesting hud refresh after stuck calls closed");
        shared
          .availability()
          .values()
          .stream()
          .filter(status -> status.call!=null && closed.contains(status.call))
          .map(s -> s.id)
          .map(Agent::new)
          .map(Locator::$)
          .forEach(Assignment::clear);
        Startup.topics.hud().publish(HudTopic.PRODUCE);
      }
    }, 0, 60, TimeUnit.SECONDS);

  }

  @Override
  public void contextInitialized(final ServletContextEvent sce) {
    super.contextInitialized(sce);
    new SyncWorkerSkills(router).exec();
    schedule();

  }

  @Override
  public void contextDestroyed(final ServletContextEvent sce) {
    super.contextDestroyed(sce);
    ExecutorServices.shutdown("Midnight Logout", executor);

  }
}
