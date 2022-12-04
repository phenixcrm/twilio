package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.twiml.VoiceResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

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
    Locator.update(call, "Mail", copy -> {
      copy.setResolution(Resolution.VOICEMAIL);
    });
    var msg = Optionals
      .of(request.getParameter("prompt"))
      .filter(Strings::isNotEmpty)
      .orElse("The party you are trying to reach is not available. Please leave a message");
    respond(response,
      new VoiceResponse.Builder().pause(pause(2)).say(speak(msg)).record(Recorder.voicemail.build()).build());
  }
}
