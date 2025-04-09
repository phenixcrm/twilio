package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.common.Party;
import com.ameriglide.phenix.common.VerifiedCallerId;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Events;
import com.ameriglide.phenix.types.CallDirection;
import com.ameriglide.phenix.types.WorkerState;
import com.twilio.twiml.VoiceResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.THROW;
import static com.ameriglide.phenix.types.WorkerState.AVAILABLE;
import static com.ameriglide.phenix.types.WorkerState.BUSY;

@WebServlet("/twilio/voice/complete")
public class Complete extends TwiMLServlet {


  private static final Log log = new Log();

  public Complete() {
    super(method -> new Config(THROW, IGNORE));
  }

  public static void finish(HttpServletRequest request, Call call, Leg leg) {
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
    if(leg != null && call.getRecordingSid() != null) {
      Locator.update(leg, "Status", copy -> {
       copy.setRecordingSid(request.getParameter("RecordingSid"));
      });
    } else {
      call.setRecordingSid(request.getParameter("RecordingSid"));
    }
    var agent = call.getAgent();
    var worker = router.getWorker(agent.getSid());
    if (worker==null) {
      log.warn(() -> "could not find worker for agent %d: %s sid ? %s".formatted(agent.id, agent.getFullName(),
        agent.getSid()));
    } else {
      if (BUSY==WorkerState.from(worker)) {
        log.debug(() -> "clearing busy from %s".formatted(agent.getFullName()));
        router.setActivity(agent.getSid(), AVAILABLE.activity());

      }
    }
  }

  /**
   * handle the completion of enqueued calls
   */
  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) {
    //todo: implement queue call post handling
  }

  /**
   * handle the completion of ordinary (not enqueued) calls
   */
  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    var called = Party.fromRequest(request, "To");
    var status = request.getParameter("DialCallStatus");
    switch (status) {
      case "completed" -> {
        if(called.isAgent()) {
          Events.restorePrebusy(called.agent());
        }
        if (!"completed".equals(request.getParameter("CallStatus"))) {
          respond(response, new VoiceResponse.Builder().hangup(hangup).build());
        }
      }
      case "busy", "failed", "no-answer" -> {
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
            if ("outbound-api".equals(request.getParameter("Direction"))) {
              final String msg;
              var code = request.getParameter("ErrorCode");
              if(Strings.isNotEmpty(code)) {
                msg = switch (request.getParameter("ErrorCode")) {
                  case "13214" -> "Invalid Caller ID, please contact your phone system administrator";
                  default -> "An unknown error occured while dialing this number";
                };
              } else {
                msg = switch(status) {
                  case "busy" -> "The number you dialed is busy";
                  case "failed" -> "The number you dialed could not be reached";
                  default -> "The number you dialed is busy or could not be reached";
                };
              }
              respond(response, new VoiceResponse.Builder().say(speak(msg)).pause(pause(2)).hangup(hangup).build());

            } else {
              log.debug(() -> "Redirecting %s to the voicemail of %s".formatted(request.getParameter("CallSid"),
                agent.getFullName()));
              respond(response, new VoiceResponse.Builder()
                .redirect(toVoicemail("%s is not available. Please leave a message".formatted(agent.getFullName())))
                .build());
            }
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
          respond(response, new VoiceResponse.Builder()
            .redirect(toVoicemail("Please leave a message, and we will return your call " + "as soon as possible"))
            .build());
        } else {
          respond(response, new VoiceResponse.Builder().hangup(hangup).build());
        }
      }
      default -> respond(response, new VoiceResponse.Builder().hangup(hangup).build());
    }
  }

}
