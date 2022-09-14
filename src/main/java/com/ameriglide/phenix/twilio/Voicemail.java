package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.types.CallDirection;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Record;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.ameriglide.phenix.servlet.Startup.router;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/voicemail")
public class Voicemail extends TwiMLServlet {
    private static final Log log = new Log();

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        log.info(() -> "%s entered voicemail".formatted(request.getParameter("CallSid")));
        respond(response, new VoiceResponse.Builder()
                .say(speak("The party you are trying to reach is not available. Please leave a message"))
                .record(new Record.Builder()
                        .playBeep(true)
                        .method(HttpMethod.POST)
                        .action(router.getAbsolutePath("/twilio/voicemail", null))
                        .trim(Record.Trim.TRIM_SILENCE)
                        .transcribeCallback(router.getAbsolutePath("/twilio/voicemail", null))
                        .maxLength(300)
                        .build())
                .build());
    }

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var call = Locator.$(new Call(request.getParameter("CallSid")));
        if (call==null) {
            throw new NotFoundException();
        }
        try {
            if ("completed".equals(request.getParameter("CallStatus"))) {
                Locator.update(call, "Voicemail", copy -> {
                    copy.setResolution(Resolution.VOICEMAIL);
                    updateDuration(request, copy);

                    if (call.getDirection()==CallDirection.INTERNAL) {
                        copy.setBlame(null);
                    }
                    Optionals
                            .of(request.getParameter("TranscriptionText"))
                            .filter(Strings::isNotEmpty)
                            .ifPresent(copy::setTranscription);
                    log.debug(() -> "%s voicemail recorded%s".formatted(copy.sid,
                            isNotEmpty(copy.getTranscription()) ? " transcribed":""));
                });
            } else {
                log.trace(() -> "%s voicemail changed status to %s".formatted(call.sid,
                        request.getParameter("CallStatus")));
            }
        } catch (Throwable t) {
            log.error(t);
        }
        response.sendError(SC_NO_CONTENT);
    }
}
