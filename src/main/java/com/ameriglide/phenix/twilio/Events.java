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
import java.util.function.Supplier;
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
    var eventType = request.getParameter("EventType");
    var resourceType = request.getParameter("ResourceType");
    switch (resourceType) {
      case "worker" -> {
        switch (eventType) {
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
        }
      }
      case "task", "reservation" -> {
        var task = JsonMap.parse(request.getParameter("TaskAttributes"));
        var taskSid = request.getParameter("TaskSid");
        switch (eventType) {
          case "task-queue.entered" -> debugTaskEvent(taskSid, task, request,
            () -> "entered %s queue".formatted(request.getParameter("TaskQueueName")));
          case "workflow.target-matched" -> debugTaskEvent(taskSid, task, request,
            () -> "selected target %s".formatted(request.getParameter("WorkflowFilterName")));
          case "workflow.entered" -> debugTaskEvent(taskSid, task, request, () -> "entered workflow");
          case "task.completed" -> debugTaskEvent(taskSid, task, request, () -> "task completed");
          case "task.canceled" -> {
            var reason = request.getParameter("reason");
            debugTaskEvent(taskSid, task, request, () -> "cancelled (%s)".formatted(reason));
            if (task.containsKey("VoiceCall")) {
              Locator.update(call, "Events", copy -> {
                copy.setResolution(DROPPED);
              });
              if ("Task TTL Exceeded".equals(reason)) {
                log.info(() -> "%s being sent to voicemail".formatted(call.sid));
                Startup.router.sendToVoicemail(call.sid, "Our staff are presently unavailable. Please leave a message "
                  + "and we will return your call as soon as possible");
              }
              shared
                .availability()
                .values()
                .stream()
                .filter(s -> call.sid.equals(s.call))
                .map(s -> s.id)
                .map(id -> Locator.$(new Agent(id)))
                .forEach(Assignment::clear);
            } else if (task.containsKey("Lead")) {
              Locator.update(call, "Events", copy -> {
                copy.setResolution(DROPPED);
                copy.setBlame(Agent.system());
                copy.setDuration(ChronoUnit.SECONDS.between(copy.getCreated(), LocalDateTime.now()));
                copy.setTalkTime(0);
              });
            }
          }
          case "reservation.created" -> debugTaskEvent(taskSid, task, request,
            () -> "created reservation %s for %s".formatted(request.getParameter("ResourceSid"),
              request.getParameter("WorkerName")));
          case "reservation.accepted" -> debugTaskEvent(taskSid, task, request,
            () -> "%s accepted reservation %s".formatted(request.getParameter("WorkerName"),
              request.getParameter("ResourceSid")));
          case "reservation.updated" -> debugTaskEvent(taskSid, task, request, () -> "reservation updated");
          case "reservation.completed" -> debugTaskEvent(taskSid, task, request, () -> "reservation completed");
          default -> debugTaskEvent(taskSid, task, request, () -> request.getParameter("EventDescription"));

        }
      }
      default -> log.debug(() -> "unhandled event resource=%s/%s type=%s description=%s".formatted(resourceType,
        request.getParameter("ResourceSid"), eventType, request.getParameter("EventDescription")));
    }
    response.sendError(SC_NO_CONTENT);

  }

  void debugTaskEvent(String task, JsonMap attributes, HttpServletRequest request, Supplier<String> msg) {
    log.debug(() -> "Task %s [from:%s,age:%s,status:%s] -> %s".formatted(task, attributes.get("from"),
      request.getParameter("TaskAge"), request.getParameter("TaskAssignmentStatus"), msg.get()));
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
