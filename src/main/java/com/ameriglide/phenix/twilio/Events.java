package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.types.WorkerState;
import com.twilio.rest.taskrouter.v1.workspace.Worker;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.ameriglide.phenix.servlet.Startup.*;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.OPTIONAL;
import static com.ameriglide.phenix.servlet.topics.HudTopic.PRODUCE;
import static com.ameriglide.phenix.types.Resolution.DROPPED;
import static com.ameriglide.phenix.types.WorkerState.AVAILABLE;
import static com.ameriglide.phenix.types.WorkerState.BUSY;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static net.inetalliance.potion.Locator.update;

@WebServlet("/twilio/events")
public class Events extends TwiMLServlet {
  private static final Log log = new Log();

  public Events() {
    super(method -> new Config(OPTIONAL, IGNORE));
  }

  private static Map<Integer,WorkerState> prebusy = new ConcurrentHashMap<>();

  public static void restorePrebusy(final Agent agent) {
    var old = prebusy.get(agent.id);
    if(old == null) {
      log.trace(()->"pre-busy state unknown for %s, assuming %s".formatted(agent.getFullName(),AVAILABLE));
      router.setActivity(agent, AVAILABLE.activity());
    } else {
      log.trace(()->"restoring pre-busy state for %s (%s)".formatted(agent.getFullName(),old));
      router.setActivity(agent, old.activity());
    }
  }

  public static void restorePrebusyIfPresent(final Agent agent) {
    if(prebusy.containsKey(agent.id)) {
      restorePrebusy(agent);
    }
  }

  public static WorkerState knownWorker(final Agent agent, final Worker worker) {
    var workerState = WorkerState.from(worker);
    if(workerState != BUSY) {
      prebusy.put(agent.id,workerState);
    }
    return workerState;
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
            var from = WorkerState.from(request.getParameter("WorkerPreviousActivitySid"));
            var to = WorkerState.from(request.getParameter("WorkerActivitySid"));
            var workerSid = request.getParameter("WorkerSid");
            var agent = Locator.$1(Agent.withSid(workerSid));
            if(to == BUSY) {
              prebusy.put(agent.id,from);
            }
            log.debug(() -> "%s %s->%s".formatted(agent.getFullName(), from, to));
            var status = shared.availability().get(agent.id);
            if (status==null) {
              shared.availability().put(agent.id, new AgentStatus(agent,to));
            } else if (!Objects.equals(from,to)) {
                shared.availability().put(agent.id, status.withWorkerState(to));
                topics.events().publishAsync(new JsonMap().$("type", "status").$("agent", agent.id).$("event",
                  new JsonMap().$("workerState", to)));
                topics.hud().publishAsync(PRODUCE);
            }

          }
        }
      }
      case "task", "reservation" -> {
        var attributes = JsonMap.parse(request.getParameter("TaskAttributes"));
        var task = request.getParameter("TaskSid");
        switch (eventType) {
          case "task-queue.entered" -> debugTaskEvent(task, attributes, request,
            () -> "entered %s queue".formatted(request.getParameter("TaskQueueName")));
          case "workflow.target-matched" -> debugTaskEvent(task, attributes, request,
            () -> "selected target %s".formatted(request.getParameter("WorkflowFilterName")));
          case "workflow.entered" -> debugTaskEvent(task, attributes, request, () -> "entered workflow");
          case "task.completed" -> debugTaskEvent(task, attributes, request, () -> "task completed");
          case "task.canceled" -> {
            var reason = request.getParameter("Reason");
            debugTaskEvent(task, attributes, request, () -> "cancelled (%s)".formatted(reason));
            if (attributes.containsKey("VoiceCall")) {
              Locator.update(call, "Events", copy -> {
                copy.setResolution(DROPPED);
              });
              if ("Task TTL Exceeded".equals(reason)) {
                log.info(() -> "%s being sent to voicemail".formatted(call.sid));
                router.sendToVoicemail(call.sid, "Our staff are presently unavailable. Please leave a message "
                  + "and we will return your call as soon as possible");
              }
            } else if (attributes.containsKey("Lead")) {
              Locator.update(call, "Events", copy -> {
                copy.setResolution(DROPPED);
                copy.setBlame(Agent.system());
                copy.setDuration(ChronoUnit.SECONDS.between(copy.getCreated(), LocalDateTime.now()));
                copy.setTalkTime(0);
              });
            }
          }
          case "reservation.created" -> {
            debugTaskEvent(task, attributes, request,
              () -> "created reservation %s for %s".formatted(request.getParameter("ResourceSid"),
                request.getParameter("WorkerName")));
            var reservation = request.getParameter("ReservationSid");
            var agent = Locator.$1(Agent.withSid(request.getParameter("WorkerSid")));
            update(call, "Assignment", copy -> {
              copy.setAgent(agent);
              copy.setBlame(agent);
            });
            if (attributes.containsKey("VoiceCall")) {
              router.voiceTask(task, reservation, new PhoneNumber(attributes.get("caller")), agent, call.sid);
            } else {
              var opp = attributes.containsKey("Lead") ? Locator.$(
                new Opportunity(Integer.valueOf(attributes.get("Lead")))):call.getOpportunity();
              if (opp==null) {
                log.error(() -> "Could not find opp for assignment: %s".formatted(attributes));
              } else if (Agent.system().id.equals(opp.getAssignedTo().id)) {
                update(opp, "Assignment", copy -> {
                  copy.setAssignedTo(agent);
                });
              }
              router.acceptReservation(task, reservation);
              router.completeTask(task);
            }
            Assignment.pop(agent, call.sid);
            Assignment.notify(call);
          }
          case "reservation.accepted" -> {
            var agent = Locator.$1(Agent.withSid(request.getParameter("WorkerSid")));
            router.setActivity(agent,BUSY.activity());
            debugTaskEvent(task, attributes, request,
              () -> "%s accepted reservation %s".formatted(request.getParameter("WorkerName"),
                request.getParameter("ResourceSid")));
          }
          case "reservation.updated" -> debugTaskEvent(task, attributes, request, () -> "reservation updated");
          case "reservation.completed" -> {
            var agent = Locator.$1(Agent.withSid(request.getParameter("WorkerSid")));
            Events.restorePrebusyIfPresent(agent);
            debugTaskEvent(task, attributes, request, () -> "reservation completed");
          }
          default -> debugTaskEvent(task, attributes, request, () -> request.getParameter("EventDescription"));

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
      case "task.canceled", "task.created", "reservation.created" -> {
        var task = JsonMap.parse(request.getParameter("TaskAttributes"));
        var voiceCall = task.get("VoiceCall");
        if (Strings.isNotEmpty(voiceCall)) {
          return voiceCall;
        }
        var lead = task.get("Lead");
        if (Strings.isNotEmpty(lead)) {
          var opp = Locator.$(new Opportunity(Integer.valueOf(lead)));
          if (opp!=null) {
            var call = Locator.$1(Call.withOpportunity(opp));
            if (call!=null) {
              return call.sid;
            }
          }
        }
        return null;
      }
      default -> {
        return null;
      }
    }
  }
}
