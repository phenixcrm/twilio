package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

@WebServlet("/twilio/transfer")
public class Transfer extends TwiMLServlet {
    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var agentId = request.getParameter("agent");
        if(Strings.isEmpty(agentId)) {
            throw new IllegalArgumentException("Must provide agent parameter");
        }
        var agent = Locator.$(new Agent(Integer.parseInt(agentId)));
        if(agent == null) {
            throw new NotFoundException("Could not find agent %s",agentId);
        }
        respond(response, new VoiceResponse.Builder()
                .dial( new Dial.Builder()
                        .action("/twilio/voice/postDial?agent="+agentId)
                        .method(HttpMethod.GET)
                        .answerOnBridge(true)
                        .timeout(15)
                        .sip(TwiMLServlet.buildSip(TwiMLServlet.asParty(agent)))
                        .build())
                .build());
    }
}
