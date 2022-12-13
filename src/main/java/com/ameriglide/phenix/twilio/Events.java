package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.PhenixServlet;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

import static com.ameriglide.phenix.servlet.Startup.*;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.OPTIONAL;
import static com.ameriglide.phenix.servlet.topics.HudTopic.PRODUCE;
import static com.ameriglide.phenix.types.Resolution.DROPPED;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static net.inetalliance.potion.Locator.update;

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
                        var from = router.byActivitySid.get(request.getParameter("WorkerPreviousActivitySid"));
                        var to = router.byActivitySid.get(request.getParameter("WorkerActivitySid"));
                        var workerSid = request.getParameter("WorkerSid");
                        var agent = Locator.$1(Agent.withSid(workerSid));
                        log.debug(() -> "%s %s->%s".formatted(agent.getFullName(), from.getFriendlyName(),
                                to.getFriendlyName()));
                        var availableNow = router.available.equals(to);
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
                                router.sendToVoicemail(call.sid,
                                        "Our staff are presently unavailable. Please leave a message "
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
                            router.voiceTask(task, reservation, new PhoneNumber(attributes.get("caller")), agent,
                                    call.sid);
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
                            PhenixServlet.respond(response, new JsonMap().$("instruction", "accept"));
                        }
                        Assignment.pop(agent, call.sid);
                        Assignment.notify(call);
                    }
                    case "reservation.accepted" -> debugTaskEvent(task, attributes, request,
                            () -> "%s accepted reservation %s".formatted(request.getParameter("WorkerName"),
                                    request.getParameter("ResourceSid")));
                    case "reservation.updated" ->
                            debugTaskEvent(task, attributes, request, () -> "reservation updated");
                    case "reservation.completed" ->
                            debugTaskEvent(task, attributes, request, () -> "reservation completed");
                    default ->
                            debugTaskEvent(task, attributes, request, () -> request.getParameter("EventDescription"));

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
            case "task.canceled", "task.created","reservation.created" -> {
                var task = JsonMap.parse(request.getParameter("TaskAttributes"));
                var voiceCall = task.get("VoiceCall");
                if(Strings.isNotEmpty(voiceCall)) {
                    return voiceCall;
                }
                var lead = task.get("Lead");
                if(Strings.isNotEmpty(lead)) {
                    var opp = Locator.$(new Opportunity(Integer.valueOf(lead)));
                    if(opp != null) {
                        var call = Locator.$1(Call.withOpportunity(opp));
                        if(call != null) {
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
