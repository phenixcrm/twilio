package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/twilio/voice/postDial")
public class PostDial extends TwiMLServlet {


  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) {
    var called = asParty(request, "To");
    if ("no-answer".equals(request.getParameter("DialCallStatus"))) {
      if (called.isAgent()) {
        var agent = called.agent();
        if (agent == null) {
          return new VoiceResponse.Builder()
            .say(speak("The party you have dialed, " + called.spoken() + ", does not exist"))
            .pause(pause)
            .hangup(hangup)
            .build();

        } else {
          info("Redirecting %s to the voicemail of %s", request.getParameter("CallSid"), agent.getFullName());
          return new VoiceResponse.Builder()
            .redirect(toVoicemail)
            .build();
        }
      }
    }
    // sometimes our side is still on the call, so we need to hang up in that case.
    return new VoiceResponse.Builder()
      .hangup(hangup)
      .build();

  }

}
