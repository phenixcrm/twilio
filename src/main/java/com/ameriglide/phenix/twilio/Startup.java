package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.PhenixServlet;
import com.ameriglide.phenix.twilio.tasks.SyncWorkerStatus;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.annotation.WebListener;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.inetalliance.potion.Locator.$1;

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
        var secs = ChronoUnit.SECONDS.between(now, nextRun);
        log.info(() -> "Scheduling next midnight logout in %d sec".formatted(secs));
        executor.schedule(() -> {
            log.info(() -> "Automatically logging out active workers");
            Startup.router.byAgent
                    .entrySet()
                    .stream()
                    .filter(Map.Entry::getValue)
                    .map(Map.Entry::getKey)
                    .peek(sid -> log.warn(
                            () -> "Setting %s to offline".formatted($1(Agent.withSid(sid)).getFullName())))
                    .forEach(sid -> router.setActivity(sid, router.offline));
            schedule();

        }, secs, TimeUnit.SECONDS);

    }

    @Override
    public void contextInitialized(final ServletContextEvent sce) {
        super.contextInitialized(sce);
        new SyncWorkerStatus(router).exec();
        schedule();

    }

    @Override
    public void contextDestroyed(final ServletContextEvent sce) {
        super.contextDestroyed(sce);
        PhenixServlet.shutdown("Midnight Logout",executor);

    }
}
