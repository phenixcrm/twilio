package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.common.Party;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Events;
import com.ameriglide.phenix.types.Resolution;
import com.ameriglide.phenix.types.WorkerState;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Number;
import com.twilio.twiml.voice.Sip;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.util.List;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.CREATE;
import static com.ameriglide.phenix.twilio.TaskRouter.toDial;
import static com.ameriglide.phenix.types.Resolution.DROPPED;
import static com.twilio.http.HttpMethod.GET;
import static java.time.LocalDateTime.now;
import static java.time.temporal.ChronoUnit.SECONDS;

@WebServlet("/twilio/voice/status")
public class Status extends TwiMLServlet {
  private static final Log log = new Log();

  public Status() {
    super(method -> new Config(CREATE, CREATE));
  }

  protected static Dial.Builder watch(Party party) {
    return watch(party, 15);
  }

  protected static Dial.Builder watch(Party party, int timeout) {
    var builder = Recorder
      .dial()
      .method(GET)
      .action(router.getApi("/voice/complete"))
      .answerOnBridge(true)
      .timeout(timeout);
    if (party.isAgent()) {
      return builder.sip(new Sip.Builder(party.sip())
        .statusCallbackMethod(GET)
        .statusCallbackEvents(List.of(Sip.Event.RINGING, Sip.Event.ANSWERED, Sip.Event.COMPLETED))
        .statusCallback(router.getApi("/voice/status"))
        .build());
    } else {
      return builder.number(new Number.Builder(toDial(party.endpoint()))
        .statusCallbackMethod(GET)
        .statusCallbackEvents(List.of(Number.Event.ANSWERED, Number.Event.COMPLETED))
        .statusCallback(router.getApi("/voice/status"))
        .build());
    }

  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) {
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
          case QUEUE,INBOUND -> legCopy.setAgent(Party.fromRequest(request, "To").agent());
          case OUTBOUND -> {
            if ("outbound-dial".equals(request.getParameter("Direction"))) {
              var called = Party.fromRequest(request, "To");
              if (called.isAgent()) {
                legCopy.setAgent(called.agent());
              } else {
                legCopy.setAgent(call.getAgent());
                Party.fromRequest(request, "Called").setCNAM(legCopy);
              }
            }
          }
          case INTERNAL -> {
            var to = Party.fromRequest(request, "To");
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
            Events.restorePrebusy(call.getAgent());
            if(legCopy.getAgent() != null) {
              Events.restorePrebusy(legCopy.getAgent());
            }
          }
          case "in-progress", "answered" -> {
            legCopy.setAnswered(now());
            log.info(() -> "%s was answered".formatted(call.sid));
            router.setActivity(legCopy.getAgent(), WorkerState.BUSY.activity());
          }
          case "no-answer", "busy", "failed" -> {
            legCopy.setEnded(now());
            Locator.update(call, "Status", callCopy -> {
              callCopy.setDuration(SECONDS.between(callCopy.getCreated(), legCopy.getEnded()));
              callCopy.setResolution(DROPPED);
            });
            Events.restorePrebusy(call.getAgent());
          }
          default -> log.info(() -> "%s is %s".formatted(call.sid, request.getParameter("CallStatus")));
        }
      });
    }
  }
}
