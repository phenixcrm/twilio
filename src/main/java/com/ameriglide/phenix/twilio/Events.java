package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.AgentStatus;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.types.Resolution;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static com.ameriglide.phenix.servlet.Startup.shared;
import static com.ameriglide.phenix.servlet.Startup.topics;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/events")
public class Events extends TwiMLServlet {

    private static final Log log = new Log();

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        response.sendError(SC_NO_CONTENT);
    }

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        try {
            switch (request.getParameter("EventType")) {
                case "worker.activity.update" -> {
                    var from = Startup.router.byActivitySid.get(request.getParameter("WorkerPreviousActivitySid"));
                    var to = Startup.router.byActivitySid.get(request.getParameter("WorkerActivitySid"));
                    var workerSid = request.getParameter("WorkerSid");
                    var agent = Locator.$1(Agent.withSid(workerSid));
                    log.debug(() -> "%s %s->%s".formatted(agent.getFullName(), from.getFriendlyName(),
                            to.getFriendlyName()));

                    var availableNow = Startup.router.available.equals(to);
                    var status = shared.availability().get(agent.id);
                    if(status == null) {
                      shared.availability().put(agent.id,new AgentStatus(agent,availableNow));
                    }
                      if(status != null && status.available() != availableNow) {
                        shared.availability().put(agent.id, status.toggleAvailability());
                        topics.hud().publishAsync("PRODUCE");
                      }
                }
                case "task.canceled" -> {
                    var task = JsonMap.parse(request.getParameter("TaskAttributes"));
                    if (task.containsKey("VoiceCall")) {
                        var call = Locator.$(new Call(task.get("VoiceCall")));
                        log.debug(() -> "%s cancelled (%s)".formatted(call.sid, request.getParameter("Reason")));
                        Startup.router.sendToVoicemail(call.sid);
                        Locator.update(call, "Events", copy -> {
                            copy.setResolution(Resolution.VOICEMAIL);
                        });
                    } else if (task.containsKey("Lead")) {
                        var call = Locator.$(new Call(request.getParameter("TaskSid")));
                        Locator.update(call, "Events", copy -> {
                            copy.setResolution(Resolution.DROPPED);
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
}
