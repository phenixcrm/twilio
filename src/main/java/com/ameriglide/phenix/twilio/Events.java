package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
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
                    var from = Startup.router.bySid.get(request.getParameter("WorkerPreviousActivitySid"));
                    var to = Startup.router.bySid.get(request.getParameter("WorkerActivitySid"));
                    var workerSid = request.getParameter("WorkerSid");
                    log.debug(() -> "%s %s->%s".formatted(Locator.$1(Agent.withSid(workerSid)).getFullName(),
                            from.getFriendlyName(), to.getFriendlyName()));
                    Startup.router.byAgent.put(workerSid, Startup.router.available.equals(to));
                }
                case "task.cancelled" -> {
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
