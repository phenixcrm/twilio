package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.core.ExecutorServices;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.topics.HudTopic;
import com.ameriglide.phenix.twilio.tasks.SyncWorkerSkills;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.taskrouter.v1.workspace.Worker;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.annotation.WebListener;
import net.inetalliance.potion.Locator;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.ameriglide.phenix.common.Call.isActiveVoiceCall;

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
    executor.scheduleWithFixedDelay(()-> {
      var activeSids = Startup.router.getCalls().map(Call::getSid).collect(Collectors.toSet());
      var closed = new AtomicBoolean(false);
      Locator.forEach(isActiveVoiceCall, call-> {
        if(!activeSids.contains(call.sid)) {
          Locator.update(call,"Closer",copy->{
            log.info(()->"Closed stuck call %s for %s".formatted(call.sid,
              Optionals.of(call.getActiveAgent()).map(Agent::getFullName).orElse("nobody")));
            copy.setResolution(Resolution.DROPPED);
          });
          Assignment.clear(call.getAgent());
          closed.set(true);
        }
      });
      if(closed.get()) {
        log.info(()->"requesting hud refresh after stuck calls closed");
        Startup.topics.hud().publish(HudTopic.PRODUCE);
      }
    }, 0, 15, TimeUnit.SECONDS);

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
