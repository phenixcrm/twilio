package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.util.List;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.types.Resolution.ANSWERED;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static java.time.LocalDateTime.now;

@WebServlet("/twilio/voice/callAgent")
public class CallAgent extends TwiMLServlet {
    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var call = Locator.$(new Call(request.getParameter("Assignment")));
        var leg = Locator.$(new Leg(request.getParameter("ReservationSid")));
        var task = request.getParameter("TaskSid");

        switch (request.getParameter("StatusCallbackEvent")) {
            case "conference-start" -> {
                router.acceptReservation(task, leg.sid);
                log.trace(()->"%s conference started".formatted(leg.sid));
                Locator.update(call, "CallAgent", copy -> {
                    copy.setAgent(leg.getAgent());
                });
                Locator.update(leg, "CallAgent", copy -> {
                    copy.setAnswered(now());
                });
            }
            case "conference-end" -> Locator.update(call, "CallAgent", copy -> {
                log.trace(()->"%s conference ended".formatted(leg.sid));
                copy.setResolution(ANSWERED);
            });

        }
        response.sendError(SC_NO_CONTENT);

    }

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var call = Locator.$(new Call(request.getParameter("Assignment")));
        var reservation = request.getParameter("ReservationSid");
        var task = request.getParameter("TaskSid");
        log.debug(()->"%s starting conference".formatted(reservation));
        respond(response, new VoiceResponse.Builder()
                .dial(new Dial.Builder()
                        .conference(new com.twilio.twiml.voice.Conference.Builder(reservation)
                                .statusCallback(router.getAbsolutePath("/twilio/voice/callAgent",
                                        "Assignment=%s&TaskSid=%s".formatted(call.sid, task)))
                                .statusCallbackMethod(HttpMethod.GET)
                                .statusCallbackEvents(List.of(Conference.Event.START, Conference.Event.JOIN))
                                .endConferenceOnExit(true)
                                .build())
                        .build())
                .build());
    }
    private static final Log log = new Log();
}
