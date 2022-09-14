package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.util.List;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.twilio.http.HttpMethod.POST;
import static com.twilio.twiml.voice.Conference.Event.END;
import static com.twilio.twiml.voice.Conference.Event.START;
import static com.twilio.twiml.voice.Conference.Record.RECORD_FROM_START;
import static com.twilio.twiml.voice.Conference.RecordingEvent.COMPLETED;
import static com.twilio.twiml.voice.Conference.Trim.TRIM_SILENCE;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/conference")
public class Conference extends TwiMLServlet {
    private static final Log log = new Log();

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var reservation = request.getParameter("ReservationSid");
        var task = request.getParameter("TaskSid");
        var callSid = request.getParameter("CallSid");
        log.debug(()->"%s:%s creating conference".formatted(task,reservation));
        String qs = "TaskSid=%s&ReservationSid=%s&Assignment=%s".formatted(task, reservation, callSid);
        respond(response, new VoiceResponse.Builder()
                .dial(new Dial.Builder()
                        .conference(new com.twilio.twiml.voice.Conference.Builder(reservation)
                                .endConferenceOnExit(true)
                                .statusCallbackMethod(HttpMethod.GET)
                                .statusCallbackEvents(List.of(START, END))
                                .statusCallback(router.getAbsolutePath(
                                        "/twilio/voice/callAgent", qs))
                                .record(RECORD_FROM_START)
                                .recordingStatusCallbackEvents(COMPLETED)
                                .recordingStatusCallbackMethod(POST)
                                .recordingStatusCallback(router.getAbsolutePath(
                                        "/twilio/conference","CallSid=%s&TaskSid=%s".formatted(callSid, task)))
                                .trim(TRIM_SILENCE)
                                .build())
                        .build())
                .build());
    }

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var sid = request.getParameter("CallSid");
        var call = Locator.$(new Call(sid));
        if (call==null) {
            throw new NotFoundException("No call sid %s".formatted(sid));
        }
        try {
            var task = request.getParameter("TaskSid");
            if ("completed".equals(request.getParameter("RecordingStatus"))) {
                router.completeTask(task);
                Locator.update(call, "Conference", copy -> {
                    copy.setResolution(Resolution.ANSWERED);
                    updateDuration(request, copy);
                    log.debug(() -> "%s call recorded".formatted(copy.sid));
                });
            } else {
                log.trace(() -> "%s Conference changed status to %s".formatted(call.sid,
                        request.getParameter("CallStatus")));
            }
        } catch (Throwable t) {
            log.error(t);
        }
        response.sendError(SC_NO_CONTENT);
    }

}
