package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.twiml.VoiceResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/twilio/voice/postDial")
public class PostDial extends TwiMLServlet {


  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
    var called = asParty(request, "To");
    if ("no-answer".equals(request.getParameter("DialCallStatus"))) {
      if (called.isAgent()) {
        var agent = called.agent();
        if (agent == null) {
          log.error(()->"somebody tried to dial %s".formatted(called));
          respond(response,new VoiceResponse.Builder()
            .say(speak("The party you have dialed, " + called.spoken() + ", does not exist"))
            .pause(pause(2))
            .hangup(hangup)
            .build());

        } else {
          log.debug(()->"Redirecting %s to the voicemail of %s".formatted(request.getParameter("CallSid"),
                  agent.getFullName()));
          respond(response,new VoiceResponse.Builder()
            .redirect(toVoicemail)
            .build());
        }
      }
    }
    // sometimes our side is still on the call, so we need to hang up in that case.
    respond(response,new VoiceResponse.Builder()
      .hangup(hangup)
      .build());

  }
  private static final Log log = new Log();

}
