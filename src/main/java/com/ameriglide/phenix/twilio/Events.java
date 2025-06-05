package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.types.Resolution;
import com.ameriglide.phenix.types.WorkerState;
import com.twilio.http.HttpMethod;
import com.twilio.rest.taskrouter.v1.workspace.Worker;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.ameriglide.phenix.common.Source.FORM;
import static com.ameriglide.phenix.core.Optionals.of;
import static com.ameriglide.phenix.servlet.Startup.*;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.OPTIONAL;
import static com.ameriglide.phenix.servlet.topics.HudTopic.PRODUCE;
import static com.ameriglide.phenix.types.Resolution.ANSWERED;
import static com.ameriglide.phenix.types.Resolution.DROPPED;
import static com.ameriglide.phenix.types.WorkerState.BUSY;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static net.inetalliance.potion.Locator.$1;
import static net.inetalliance.potion.Locator.update;

@WebServlet("/twilio/events")
public class Events extends TwiMLServlet {
  private static final Log log = new Log();
  private static final Map<Integer, WorkerState> prebusy = new ConcurrentHashMap<>();

  public Events() {
    super(method -> new Config(OPTIONAL, IGNORE));
  }

  public Events(final Function<HttpMethod, Config> config) {
    super(config);
  }

  public static WorkerState knownWorker(final Agent agent, final Worker worker) {
    var workerState = WorkerState.from(worker);
    if (workerState!=BUSY) {
      prebusy.put(agent.id, workerState);
    }
    return workerState;

  }

  public static void restorePrebusy(final Agent agent) {
    var currentState = WorkerState.from(router.getWorker(agent.getSid()));
    if(currentState==BUSY) {
      var old = prebusy.computeIfAbsent(agent.id, key -> WorkerState.AVAILABLE);
      log.trace(() -> "restoring pre-busy state for %s (%s)".formatted(agent.getFullName(), old));
      router.setActivity(agent, old.activity());
    } else {
      log.trace(() -> "not restoring pre-busy state for non-busy %s (%s)".formatted(agent.getFullName(),
        currentState));
    }
  }

  private static Call getActiveCall(Agent agent) {
    return of($1(Call.isActiveVoiceCall.and(Call.withAgent(agent)))).orElseGet(
      () -> of($1(Call.isActiveVoiceCall.join(Leg.class, "call").and(Leg.withAgent(agent))))
        .map(leg -> leg.call)
        .orElse(null));
  }

  public static boolean verifyActiveCall(final Call activeCall) {
    var call = router.getCall(activeCall.sid);
    if (call==null) {
      log.warn(() -> "could not find twilio call for %s".formatted(activeCall.sid));
      closeCall(activeCall, null, null, null);
      return false;
    } else {
      switch (call.getStatus()) {
        case QUEUED, RINGING, IN_PROGRESS -> {
          // legit call
          log.trace(() -> "%s is an active call".formatted(activeCall.sid));
          return true;
        }
        default -> {
          var leg = activeCall.getActiveLeg();
          if (leg==null) {
            log.warn(() -> "Call %s has no active leg, manually closing".formatted(activeCall.sid));
            closeCall(activeCall, call);
            return false;
          }
          var twilioLeg = router.getCall(leg.sid);
          if (twilioLeg==null) {
            log.warn(() -> "could not find twilio call for active leg %s, manually closing".formatted(leg.sid));
            closeCall(activeCall, call, leg);
            return false;
          } else {
            switch (twilioLeg.getStatus()) {
              case QUEUED, RINGING, IN_PROGRESS -> {
                log.trace(() -> "%s is an active leg".formatted(leg.sid));
                return true;
              }
              default -> {
                log.warn(() -> "Active leg %s is inactive in twilio, manually closing".formatted(leg.sid));
                closeCall(activeCall, call, leg, twilioLeg);
                return false;
              }

            }
          }

        }

      }
    }
  }

  public static void closeCall(final Call activeCall, final com.twilio.rest.api.v2010.account.Call twilioCall,
                               final Leg activeLeg, final com.twilio.rest.api.v2010.account.Call twilioLeg) {
    update(activeCall, "Events", (copy) -> {
      if (twilioCall==null) {
        copy.setResolution(ANSWERED);
      } else {
        copy.setResolution(switch (twilioCall.getStatus()) {
          case COMPLETED -> Resolution.ANSWERED;
          case BUSY, FAILED, NO_ANSWER, CANCELED -> Resolution.DROPPED;
          default -> throw new IllegalStateException();
        });
      }
    });
    if (activeLeg!=null) {
      if (twilioLeg==null) {
        Locator.update(activeLeg, "Events", copy -> {
          copy.setEnded(twilioCall.getEndTime().toLocalDateTime());
        });
      } else {
        Locator.update(activeLeg, "Events", copy -> {
          copy.setEnded(twilioLeg.getEndTime().toLocalDateTime());
        });

      }
    }
  }

  public static void closeCall(final Call activeCall, final com.twilio.rest.api.v2010.account.Call twilioCall) {
    closeCall(activeCall, twilioCall, null, null);
  }

  public static void closeCall(final Call activeCall, final com.twilio.rest.api.v2010.account.Call twilioCall,
                               final Leg activeLeg) {
    closeCall(activeCall, twilioCall, activeLeg, null);

  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    response.sendError(SC_NO_CONTENT);
    var eventType = request.getParameter("EventType");
    var resourceType = request.getParameter("ResourceType");
    switch (resourceType) {
      case "worker" -> {
        switch (eventType) {
          case "worker.activity.update" -> {
            var from = WorkerState.from(request.getParameter("WorkerPreviousActivitySid"));
            var to = WorkerState.from(request.getParameter("WorkerActivitySid"));
            var workerSid = request.getParameter("WorkerSid");
            var agent = $1(Agent.withSid(workerSid));
            try {

              Locator.create("Events",
                new ActivityChange(request.getParameter("Sid"), utcSecondsToDateTime(request.getParameter("Timestamp")),
                  from, of(request.getParameter("WorkerTimeInPreviousActivity"))
                  .filter(Strings::isNotEmpty)
                  .map(Long::parseLong)
                  .orElse(null), to, agent));
            } catch (Throwable t) {
              log.error(t);
            }
            if (to==BUSY) {
              prebusy.put(agent.id, from);
            }
            log.debug(() -> "%s %s->%s".formatted(agent.getFullName(), from, to));
            var status = shared.availability().get(agent.id);
            if (status==null) {
              shared.availability().put(agent.id, new AgentStatus(agent, to));
            } else if (!Objects.equals(from, to)) {
              shared.availability().put(agent.id, status.withWorkerState(to));
              topics
                .events()
                .publishAsync(new JsonMap()
                  .$("type", "status")
                  .$("agent", agent.id)
                  .$("event", new JsonMap().$("workerState", to)));
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
          case "workflow.entered" -> debugTaskEvent(task, attributes, request,
            () -> "entered workflow (%s)".formatted(request.getParameter("EventDescription")));
          case "task.completed" -> debugTaskEvent(task, attributes, request, () -> "task completed");
          case "task.canceled" -> {
            var reason = request.getParameter("Reason");
            debugTaskEvent(task, attributes, request, () -> "cancelled (%s)".formatted(reason));
            if (attributes.containsKey("VoiceCall")) {
              update(call, "Events", copy -> {
                copy.setResolution(DROPPED);
              });
              if ("Task TTL Exceeded".equals(reason)) {
                log.info(() -> "%s being sent to voicemail".formatted(call.sid));
                router.sendToVoicemail(call.sid, "Our staff are presently unavailable. Please leave a message "
                  + "and we will return your call as soon as possible");
              }
            } else if (attributes.containsKey("Lead")) {
              update(call, "Events", copy -> {
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
            var agent = $1(Agent.withSid(request.getParameter("WorkerSid")));
            if (attributes.containsKey("VoiceCall")) {
              update(call, "Assignment", copy -> {
                copy.setAgent(agent);
                copy.setBlame(agent);
              });
              router.voiceTask(task, reservation, new PhoneNumber(attributes.get("caller")), agent, call.sid);
            } else {
              var lead = attributes.containsKey("Lead") ? Locator.$(
                new Lead(Integer.valueOf(attributes.get("Lead")))):call.getOpportunity();
              if (lead==null) {
                log.error(() -> "Could not find opp for assignment: %s".formatted(attributes));
              } else {
                if (Agent.system().id.equals(lead.getAssignedTo().id)) {
                  update(lead, "Assignment", copy -> {
                    copy.setAssignedTo(agent);
                  });
                }
                var assignment = new Leg(call, reservation);
                assignment.setCreated(LocalDateTime.now());
                assignment.setAgent(agent);
                Locator.create("Events", assignment);
                router.acceptReservation(task, reservation);
              }
            }
            Assignment.pop(agent, call.sid);
            Assignment.notify(call);
          }
          case "reservation.accepted" -> {
            var agent = $1(Agent.withSid(request.getParameter("WorkerSid")));
            router.setActivity(agent, BUSY.activity());
            debugTaskEvent(task, attributes, request,
              () -> "%s accepted reservation %s".formatted(request.getParameter("WorkerName"),
                request.getParameter("ResourceSid")));
            router.completeTask(task);
            if(call.getSource() == FORM) {
              var lead = call.getOpportunity();
              if(lead == null) {
                log.warn(()->"Somehow we have a form call %s with no lead?".formatted(call.sid));
              } else {
                Locator.update(lead, "Events", copy -> {
                  copy.setAssignedTo(agent);
                });
                log.info(()->"Auto-assigning form call %s to %s".formatted(call.sid, agent.getFullName()));

              }

            }
          }
          case "reservation.updated" -> debugTaskEvent(task, attributes, request, () -> "reservation updated");
          case "reservation.completed" -> debugTaskEvent(task, attributes, request, () -> "reservation completed");
          default -> debugTaskEvent(task, attributes, request, () -> request.getParameter("EventDescription"));

        }
      }
      default -> log.debug(() -> "unhandled event resource=%s/%s type=%s description=%s".formatted(resourceType,
        request.getParameter("ResourceSid"), eventType, request.getParameter("EventDescription")));
    }

  }

  private LocalDateTime utcSecondsToDateTime(String field) {
    if (Strings.isEmpty(field)) {
      return null;
    }
    var utcSecs = Long.parseLong(field);
    return Instant.ofEpochSecond(utcSecs).atZone(ZoneId.systemDefault()).toLocalDateTime();
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
        if (router.digitalLeadsChannel.getSid().equals(request.getParameter("TaskChannelSid"))) {
          return request.getParameter("TaskSid");
        } else {
          var leadId = task.get("Lead");
          if (Strings.isNotEmpty(leadId)) {
            var lead = Locator.$(new Lead(Integer.valueOf(leadId)));
            if (lead!=null) {
              var call = $1(Call.withLead(lead));
              if (call!=null) {
                return call.sid;
              }
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