package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Assignment;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Number;
import com.twilio.twiml.voice.Sip;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.util.List;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.twilio.TaskRouter.toDial;
import static com.ameriglide.phenix.types.Resolution.DROPPED;
import static com.twilio.http.HttpMethod.GET;
import static java.time.LocalDateTime.now;
import static java.time.temporal.ChronoUnit.SECONDS;

@WebServlet("/twilio/voice/status")
public class Status extends TwiMLServlet {
  private static final Log log = new Log();

  public Status() {
    super(Mode.CREATE, Mode.CREATE);
  }

  protected static Dial.Builder watch(Party party) {
    return watch(party, 15);
  }

  protected static Dial.Builder watch(Party party, int timeout) {
    var builder = Recorder.watch().method(GET).action("/twilio/voice/complete").answerOnBridge(true).timeout(timeout);
    if (party.isAgent()) {
      return builder.sip(new Sip.Builder(party.sip())
        .statusCallbackMethod(GET)
        .statusCallbackEvents(List.of(Sip.Event.RINGING, Sip.Event.ANSWERED, Sip.Event.COMPLETED))
        .statusCallback(router.getAbsolutePath("/twilio/voice/status", null))
        .build());
    } else {
      return builder.number(new Number.Builder(toDial(party.endpoint()))
        .statusCallbackMethod(GET)
        .statusCallbackEvents(List.of(Number.Event.ANSWERED, Number.Event.COMPLETED))
        .statusCallback(router.getAbsolutePath("/twilio/voice/status", null))
        .build());
    }

  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) throws Exception {
    // this is a recording update
    Locator.update(call, "Status", copy -> {
      Complete.finish(request, copy);

    });
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    if (leg!=null) {
      // we have an update on a leg
      Locator.update(leg, "Status", legCopy -> {
        switch (call.getDirection()) {
          case QUEUE -> legCopy.setAgent(asParty(request, "To").agent());
          case INBOUND -> {
          }
          case OUTBOUND -> {
            if ("outbound-dial".equals(request.getParameter("Direction"))) {
              var called = asParty(request, "To");
              if (called.isAgent()) {
                legCopy.setAgent(called.agent());
              } else {
                legCopy.setAgent(call.getAgent());
                asParty(request, "Called").setCNAM(legCopy);
              }
            }
          }
          case INTERNAL -> {
            var to = asParty(request, "To");
            if (to.isAgent()) {
              legCopy.setAgent(to.agent());
            } else {
              to.setCNAM(legCopy);
            }
          }
        }
        switch (request.getParameter("CallStatus")) {
          case "completed" -> {
            legCopy.setEnded(now());
            Locator.update(call, "Status", callCopy -> {
              if (legCopy.isAnswered()) {
                callCopy.setTalkTime(Optionals.of(callCopy.getTalkTime()).orElse(0L) + legCopy.getTalkTime());
                callCopy.setResolution(Resolution.ANSWERED);
                log.info(() -> "%s has finished".formatted(callCopy.sid));
              }
              callCopy.setDuration(SECONDS.between(callCopy.getCreated(), legCopy.getEnded()));
            });
            Assignment.clear(call);
          }
          case "in-progress", "answered" -> {
            legCopy.setAnswered(now());
            log.info(() -> "%s was answered".formatted(call.sid));
          }
          case "no-answer", "busy", "failed" -> {
            legCopy.setEnded(now());
            Locator.update(call, "Status", callCopy -> {
              callCopy.setDuration(SECONDS.between(callCopy.getCreated(), legCopy.getEnded()));
              callCopy.setResolution(DROPPED);
            });
            Assignment.clear(call);
          }
          default -> {
            log.info(() -> "%s is %s".formatted(call.sid, request.getParameter("CallStatus")));
          }
        }
      });
    }
  }
}
