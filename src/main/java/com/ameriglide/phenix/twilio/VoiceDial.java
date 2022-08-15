package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.nio.charset.StandardCharsets;

import static java.net.URLDecoder.decode;
import static net.inetalliance.funky.StringFun.isEmpty;

@WebServlet("/twilio/voice/dial")
public class VoiceDial extends TwiMLServlet {
    @Override
    protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) {
        var agent = request.getParameter("agent");
        var number = decode(request.getParameter("number"), StandardCharsets.UTF_8);
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
        if (called.isAgent()) {
            // internal call
            return new VoiceResponse.Builder()
                    .say(speak("Connecting you to " + called.agent().getFullName()))
                    .dial(new Dial.Builder()
                            .action("/twilio/voice/postDial")
                            .method(HttpMethod.GET)
                            .answerOnBridge(true)
                            .timeout(15)
                            .sip(buildSip(called))
                            .build())
                    .build();
        } else {
            // outbound call
            return new VoiceResponse.Builder()
                    .say(speak("Dialing the number you requested"))
                    .dial(new Dial.Builder()
                            .action("/twilio/voice/postDial")
                            .method(HttpMethod.GET)
                            .answerOnBridge(true)
                            .timeout(60)
                            .number(buildNumber(asParty(new PhoneNumber(called.endpoint()))))
                            .callerId(call.getPhone())
                            .build())
                    .build();
        }
    }
