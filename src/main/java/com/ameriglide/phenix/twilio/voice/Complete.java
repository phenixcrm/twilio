package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.common.VerifiedCallerId;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.types.CallDirection;
import com.twilio.twiml.VoiceResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.THROW;

@WebServlet("/twilio/voice/complete")
public class Complete extends TwiMLServlet {


  private static final Log log = new Log();
  public static String callback;

  public Complete() {
    super(method -> new Config(THROW, IGNORE));
  }

  public static void finish(HttpServletRequest request, Call call) {
    var duration = Optionals
      .of(request.getParameter("RecordingDuration"))
      .filter(Strings::isNotEmpty)
      .map(Long::parseLong)
      .orElse(0L);
    call.setDuration(Optionals.of(call.getDuration()).orElse(0L) + duration);
    call.setSilent(call.getDuration()==0);
    call.setTalkTime(call.getDuration());
    if (call.getDirection()==CallDirection.INTERNAL) {
      call.setBlame(null);
    }
    call.setRecordingSid(request.getParameter("RecordingSid"));
  }

  /**
   * handle the completion of enqueued calls
   */
  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) throws Exception {
    //todo: implement queue call post handling
  }

  /**
   * handle the completion of ordinary (not enqueued) calls
   */
  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws Exception {
    var called = asParty(request, "To");
    switch (request.getParameter("DialCallStatus")) {
      case "completed":
        if (!"completed".equals(request.getParameter("CallStatus"))) {
          respond(response, new VoiceResponse.Builder().hangup(hangup).build());
        }
        return;
      case "busy":
      case "failed":
      case "no-answer":
        if (called.isAgent()) {
          var agent = called.agent();
          if (agent==null) {
            log.error(() -> "somebody tried to dial %s".formatted(called));
            respond(response, new VoiceResponse.Builder()
              .say(speak("The party you have dialed, " + called.spoken() + ", does not exist"))
              .pause(pause(2))
              .hangup(hangup)
              .build());

          } else {
            log.debug(() -> "Redirecting %s to the voicemail of %s".formatted(request.getParameter("CallSid"),
              agent.getFullName()));
            respond(response, new VoiceResponse.Builder().redirect(toVoicemail()).build());
          }
        } else if (call.getDirection()!=CallDirection.OUTBOUND) {
          var agent = call.getDialingAgent();
          if (agent!=null) {
            log.debug(() -> "Redirecting %s to the voicemail of %s".formatted(request.getParameter("CallSid"),
              agent.getFullName()));
          } else {
            var vcid = Locator.$1(VerifiedCallerId.withPhoneNumber(called.endpoint()));
            if (vcid!=null && vcid.isDirect()) {
              log.debug(() -> "Redirecting %s to the voicemail of %s".formatted(request.getParameter("CallSid"),
                vcid.getDirect().getFullName()));

            } else {
              log.debug(() -> "Redirecting to generic voicemail");
            }
          }
          respond(response, new VoiceResponse.Builder().redirect(toVoicemail()).build());
        } else {
          respond(response, new VoiceResponse.Builder().hangup(hangup).build());
        }
        break;
      default:
        respond(response, new VoiceResponse.Builder().hangup(hangup).build());
        break;
    }
  }

}
