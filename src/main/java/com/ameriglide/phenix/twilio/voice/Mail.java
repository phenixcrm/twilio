package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.twiml.VoiceResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.CREATE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;

@WebServlet("/twilio/voice/mail")
public class Mail extends TwiMLServlet {
  private static final Log log = new Log();

  public Mail() {
    super(method -> new Config(CREATE, IGNORE));
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) throws Exception {
    respond(response, new VoiceResponse.Builder().say(speak("Goodbye")).hangup(hangup).build());
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    log.info(() -> "%s entered voicemail".formatted(call.sid));
    respond(response, new VoiceResponse.Builder()
      .pause(pause(2))
      .say(speak("The party you are trying to reach is not available. Please leave a message"))
      .record(Recorder.voicemail.build())
      .build());
  }
}
