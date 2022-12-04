package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.AgentStatus;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.stream.Stream;

import static com.ameriglide.phenix.servlet.Startup.shared;
import static com.ameriglide.phenix.servlet.Startup.topics;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.OPTIONAL;
import static com.ameriglide.phenix.servlet.topics.HudTopic.PRODUCE;
import static com.ameriglide.phenix.types.Resolution.DROPPED;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/events")
public class Events extends TwiMLServlet {
  private static final Log log = new Log();

  public Events() {
    super(method -> new Config(OPTIONAL, IGNORE));
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    try {
      switch (request.getParameter("EventType")) {
        case "worker.activity.update" -> {
          var from = Startup.router.byActivitySid.get(request.getParameter("WorkerPreviousActivitySid"));
          var to = Startup.router.byActivitySid.get(request.getParameter("WorkerActivitySid"));
          var workerSid = request.getParameter("WorkerSid");
          var agent = Locator.$1(Agent.withSid(workerSid));
          log.debug(() -> "%s %s->%s".formatted(agent.getFullName(), from.getFriendlyName(), to.getFriendlyName()));
          var availableNow = Startup.router.available.equals(to);
          var status = shared.availability().get(agent.id);
          if (status==null) {
            shared.availability().put(agent.id, new AgentStatus(agent, availableNow));
          }
          if (status!=null && status.available!=availableNow) {
            shared.availability().put(agent.id, status.toggleAvailability());
            topics
              .events()
              .publishAsync(new JsonMap()
                .$("type", "status")
                .$("agent", agent.id)
                .$("event", new JsonMap().$("available", availableNow)));
            topics.hud().publishAsync(PRODUCE);
          }
        }
        case "task.canceled" -> {
          var task = JsonMap.parse(request.getParameter("TaskAttributes"));
          if (task.containsKey("VoiceCall")) {
            var reason = request.getParameter("Reason");
            log.debug(() -> "%s cancelled (%s)".formatted(task.get("VoiceCall"), reason));
            Locator.update(call, "Events", copy -> {
              copy.setResolution(DROPPED);
            });
            if ("Task TTL Exceeded".equals(reason)) {
              log.info(() -> "%s being sent to voicemail".formatted(call.sid));
              Startup.router.sendToVoicemail(call.sid, "Our staff are presently unavailable. Please leave a message "
                + "and we will return your call as soon as possible");
            }
          } else if (task.containsKey("Lead")) {
            Locator.update(call, "Events", copy -> {
              copy.setResolution(DROPPED);
              copy.setBlame(Agent.system());
              copy.setDuration(ChronoUnit.SECONDS.between(copy.getCreated(), LocalDateTime.now()));
              copy.setTalkTime(0);
            });
          }
        }
      }
      response.sendError(SC_NO_CONTENT);
    } catch (Throwable t) {
      log.error(t);
      throw t;
    }

  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    response.sendError(SC_NO_CONTENT);
  }

  @Override
  protected String getCallSid(HttpServletRequest request) {
    switch (request.getParameter("EventType")) {
      case "task.canceled" -> {
        var task = JsonMap.parse(request.getParameter("TaskAttributes"));
        return Stream.of("VoiceCall", "Lead").map(task::get).filter(Objects::nonNull).findFirst().orElse(null);
      }
      default -> {
        return null;
      }
    }
  }
}
