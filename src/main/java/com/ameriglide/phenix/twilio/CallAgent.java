package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.util.List;

@WebServlet("/twilio/voice/callAgent")
public class CallAgent extends TwiMLServlet {
  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) throws Exception {

    return null;
  }

  @Override
  protected TwiML postResponse(HttpServletRequest request, HttpServletResponse response) {
    var call = Locator.$(new Call(request.getParameter("Assignment")));
    return new VoiceResponse.Builder()
      .say(speak(call.getQueue().getAnnouncement()))
      .dial(new Dial.Builder()
        .conference(new com.twilio.twiml.voice.Conference.Builder(request.getParameter("ReservationSid"))
          .statusCallback("/twilio/voice/callAgent")
          .statusCallbackMethod(HttpMethod.GET)
          .statusCallbackEvents(List.of(Conference.Event.START, Conference.Event.JOIN))
          .endConferenceOnExit(true)
          .build())
        .build())
      .build();
  }
}
