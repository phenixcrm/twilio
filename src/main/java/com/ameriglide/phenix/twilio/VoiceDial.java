package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.nio.charset.StandardCharsets;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static java.net.URLDecoder.decode;

@WebServlet("/twilio/voice/dial")
public class VoiceDial extends TwiMLServlet {
    private static final Log log = new Log();

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var agent = request.getParameter("agent");
        var number = Optionals
                .of(request.getParameter("number"))
                .map(n -> decode(request.getParameter("number"), StandardCharsets.UTF_8))
                .orElse(null);
        Party called;
        if (isEmpty(agent)) {
            if (isEmpty(number)) {
                throw new IllegalArgumentException();
            }
            called = asParty(new PhoneNumber(number));
        } else {
            called = asParty(Locator.$(new Agent(Integer.parseInt(agent))));
        }
        // call the caller and then connect to the called party
        var call = Locator.$(new Call(request.getParameter("CallSid")));
        if (call==null) {
            throw new NotFoundException();
        }
        var builder = new VoiceResponse.Builder();
        if (called.isAgent()) {
            // internal call
            log.debug(() -> "connecting internal call %s -> %s".formatted(call.getAgent().getFullName(),
                    called.agent().getFullName()));
            builder
                    .say(speak("Connecting you to " + called.agent().getFullName()))
                    .dial(new Dial.Builder()
                            .action("/twilio/voice/postDial")
                            .method(HttpMethod.GET)
                            .answerOnBridge(true)
                            .timeout(15)
                            .sip(buildSip(called))
                            .build());
        } else {
            // outbound call
            log.debug(() -> "connecting %s to PSTN %s".formatted(call.getAgent().getFullName(), called.endpoint()));
            builder
                    .say(speak("Dialing the number you requested"))
                    .dial(new Dial.Builder()
                            .action("/twilio/voice/postDial")
                            .method(HttpMethod.GET)
                            .answerOnBridge(true)
                            .timeout(60)
                            .number(buildNumber(asParty(new PhoneNumber(called.endpoint()))))
                            .callerId(call.getPhone())
                            .build());
        }
        respond(response, builder.build());
    }
}
