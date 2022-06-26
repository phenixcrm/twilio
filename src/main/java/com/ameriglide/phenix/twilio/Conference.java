package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

@WebServlet("/twilio/conference")
public class Conference extends TwiMLServlet {
  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) throws Exception {
    var reservation = request.getParameter("ReservationSid");
    return new VoiceResponse.Builder()
      .say(speak("This call may be recorded for quality assurance purposes"))
      .dial(new Dial.Builder()
        .conference(new com.twilio.twiml.voice.Conference.Builder(reservation)
          .endConferenceOnExit(true)
          .record(com.twilio.twiml.voice.Conference.Record.RECORD_FROM_START)
          .recordingStatusCallbackEvents(com.twilio.twiml.voice.Conference.RecordingEvent.COMPLETED)
          .recordingStatusCallbackMethod(HttpMethod.POST)
          .recordingStatusCallback("/twilio/conference")
          .trim(com.twilio.twiml.voice.Conference.Trim.TRIM_SILENCE)
          .build())
        .build())
      .build();
  }

  @Override
  protected TwiML postResponse(HttpServletRequest request, HttpServletResponse response) {
    var call = Locator.$(new Call(request.getParameter("CallSid")));
    if (call == null) {
      throw new NotFoundException();
    }
    try {
      if ("completed".equals(request.getParameter("RecordingStatus"))) {
        Locator.update(call, "Conference", copy -> {
          copy.setResolution(Resolution.ANSWERED);
          updateDuration(request, copy);
          info("%s call recorded", copy.sid);
        });
      } else {
        info("%s Conference changed status to %s", call.sid, request.getParameter("CallStatus"));
      }
    } catch (Throwable t) {
      error(t);
    }
    return null;
  }


}
