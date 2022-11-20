package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.common.VerifiedCallerId;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Mode.CREATE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Mode.THROW;

@WebServlet("/twilio/voice/transfer")
public class Transfer extends TwiMLServlet {
  public Transfer() {
    super(THROW, CREATE);
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    var agentId = request.getParameter("agent");
    var number = request.getParameter("number");
    final Dial.Builder dial;
    if (isEmpty(agentId)) {
      if (isEmpty(number)) {
        throw new IllegalArgumentException("Must provide agent or number parameter");
      } else {
        var vCid = Locator.$1(VerifiedCallerId.isDefault);
        dial = Status.watch(asParty(new PhoneNumber(number))).callerId(vCid.getPhoneNumber());
      }
    } else {
      dial = Status.watch(asParty(Locator.$(new Agent(Integer.parseInt(agentId)))));
    }
    respond(response, new VoiceResponse.Builder().dial(dial.build()).build());
  }
}
