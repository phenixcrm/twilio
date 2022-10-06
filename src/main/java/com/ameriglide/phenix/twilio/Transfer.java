package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.VerifiedCallerId;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

@WebServlet("/twilio/transfer")
public class Transfer extends TwiMLServlet {
    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var agentId = request.getParameter("agent");
        var number = request.getParameter("number");
        var dial = new Dial.Builder()
                .action("/twilio/voice/postDial?agent=" + agentId)
                .method(HttpMethod.GET)
                .answerOnBridge(true)
                .timeout(15);
        var parentSid = request.getParameter("ParentCallSid");
        if (Strings.isEmpty(agentId)) {
            if (Strings.isEmpty(number)) {
                throw new IllegalArgumentException("Must provide agent or number parameter");
            } else {
                var vCid = Locator.$1(VerifiedCallerId.isDefault);
                dial.callerId(vCid.getPhoneNumber());
                dial.number(buildNumber(TwiMLServlet.asParty(new PhoneNumber(number))));
            }

        } else {
            dial.sip(buildSip(TwiMLServlet.asParty(Locator.$(new Agent(Integer.parseInt(agentId))))));
        }
        respond(response, new VoiceResponse.Builder().dial(dial.build()).build());
    }
}
