package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.time.LocalDateTime;
import java.util.List;

@WebServlet("/twilio/voice/callAgent")
public class CallAgent extends TwiMLServlet {
  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) throws Exception {
    var call = Locator.$(new Call(request.getParameter("Assignment")));
    var leg = Locator.$(new Leg(request.getParameter("FriendlyName")));
    var task = request.getParameter("TaskSid");

    switch(request.getParameter("StatusCallbackEvent")) {
      case "conference-start" -> {
        Startup.router.acceptReservation(task, leg.sid);
        Locator.update(call, "CallAgent", copy -> {
          copy.setAgent(leg.getAgent());
        });
        Locator.update(leg, "CallAgent", copy -> {
          copy.setAnswered(LocalDateTime.now());
        });
      }
        case "conference-end" -> {
          Locator.update(call, "CallAgent", copy -> {
            copy.setResolution(Resolution.ANSWERED);
          });
        }

      }
    return null;
  }

  @Override
  protected TwiML postResponse(HttpServletRequest request, HttpServletResponse response) {
    var call = Locator.$(new Call(request.getParameter("Assignment")));
    var reservation = request.getParameter("ReservationSid");
    var task = request.getParameter("TaskSid");
    return new VoiceResponse.Builder()
      .say(speak(call.getQueue().getAnnouncement()))
      .dial(new Dial.Builder()
        .conference(new com.twilio.twiml.voice.Conference.Builder(reservation)
          .statusCallback("/twilio/voice/callAgent?Assignment=%s&TaskSid=%s".formatted(call.sid,task))
          .statusCallbackMethod(HttpMethod.GET)
          .statusCallbackEvents(List.of(Conference.Event.START, Conference.Event.JOIN))
          .endConferenceOnExit(true)
          .build())
        .build())
      .build();
  }
}
