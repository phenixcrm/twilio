package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.types.CallDirection;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Record;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.funky.Funky;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.Locator;

import static com.ameriglide.phenix.servlet.Startup.router;

@WebServlet("/twilio/voicemail")
public class Voicemail extends TwiMLServlet {
    @Override
    protected TwiML postResponse(HttpServletRequest request, HttpServletResponse response) {
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
                    Funky
                            .of(request.getParameter("TranscriptionText"))
                            .filter(StringFun::isNotEmpty)
                            .ifPresent(copy::setTranscription);
                    info("%s voicemail recorded%s", copy.sid,
                            StringFun.isNotEmpty(copy.getTranscription()) ? " transcribed":"");
                });
            } else {
                info("%s voicemail changed status to %s", call.sid, request.getParameter("CallStatus"));
            }
        } catch (Throwable t) {
            error(t);
        }
        return null;
    }

    @Override
    protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) {
        info("%s entered voicemail", request.getParameter("CallSid"));
        return new VoiceResponse.Builder()
                .say(speak("The party you are trying to reach is not available. Please leave a message"))
                .record(new Record.Builder()
                        .playBeep(true)
                        .method(HttpMethod.POST)
                        .action(router.getAbsolutePath("/twilio/voicemail", null))
                        .trim(Record.Trim.TRIM_SILENCE)
                        .transcribeCallback(router.getAbsolutePath("/twilio/voicemail", null))
                        .maxLength(300)
                        .build())
                .build();
    }
}
