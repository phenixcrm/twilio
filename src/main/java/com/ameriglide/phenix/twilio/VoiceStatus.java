package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.voice.Number;
import com.twilio.twiml.voice.Sip;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.Locator;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static java.time.LocalDateTime.now;

@WebServlet("/twilio/voice/status")
public class VoiceStatus extends TwiMLServlet {
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) {
    if (StringFun.isEmpty(request.getParameter("ParentCallSid"))) {
      // we are operating on the primary call
      var call = Locator.$(new Call(request.getParameter("CallSid")));
      if (call == null) {
        throw new NotFoundException();
      }
      var seg = call.getActiveLeg();
      if ("inbound".equals(request.getParameter("Direction"))) {
        if (seg != null) {
          Locator.update(call, "VoiceStatus", callCopy -> {
            processCallStatusChange(request, call, seg, callCopy);
          });
        }
      }

    } else {
      // we have an update on a leg
      var call = Locator.$(new Call(request.getParameter("ParentCallSid")));
      var legSid = request.getParameter("CallSid");
      var segment = Funky.of(Locator.$(new Leg(call, legSid)))
        .orElseGet(() -> {
          var leg = new Leg(call, legSid);
          leg.setCreated(now());
          switch(call.getDirection()) {
            case CallDirection.INBOUND -> {

            }
            case CallDirection.OUTBOUND -> {
              if ("outbound-dial".equals(request.getParameter("Direction"))) {
                leg.setAgent(call.getAgent());
                asParty(request,"Called").setCNAM(leg);
              }
            }
            case CallDirection.INTERNAL -> leg.setAgent(asParty(request, "To").agent());

          }
          Locator.create("VoiceStatus", leg);
          return leg;
        });

      Locator.update(call, "VoiceStatus", callCopy -> {
        processCallStatusChange(request, call, segment, callCopy);
      });
    }
    return null;
  }

  protected static Number buildNumber(Party party) {
    return new Number.Builder(party.endpoint())
      .statusCallbackMethod(HttpMethod.GET)
      .statusCallbackEvents(List.of(Number.Event.ANSWERED, Event.COMPLETED))
      .statusCallback("/twilio/voice/status")
      .build();
  }

  protected static Sip buildSip(Party party) {
    return new Sip.Builder(party.sip())
      .statusCallbackMethod(HttpMethod.GET)
      .statusCallbackEvents(List.of(com.twilio.twiml.voice.Sip.Event.ANSWERED,
        com.twilio.twiml.voice.Sip.Event.COMPLETED))
      .statusCallback("/twilio/voice/status")
      .build();

  }

  private void processCallStatusChange(HttpServletRequest request, Call call, Leg leg, Call callCopy) {
    switch (request.getParameter("CallStatus")) {
      case "completed" -> {
        Locator.update(leg, "VoiceStatus", segmentCopy -> {
          segmentCopy.setEnded(now());
          if (leg.isAnswered()) {
            callCopy.setTalkTime(Funky.of(call.getTalkTime()).orElse(0L) + leg.getTalkTime());
            callCopy.setResolution(Resolution.ANSWERED);
            info("%s was answered",call.sid);
          }
        });
        if (call.getResolution() == null) {
          callCopy.setResolution(Resolution.DROPPED);
          info("%s was dropped",call.sid);
        }
        callCopy.setDuration(ChronoUnit.SECONDS.between(call.getCreated(), leg.getEnded()));
      }
      case "in-progress", "answered" -> Locator.update(leg, "VoiceStatus", segmentCopy -> {
        segmentCopy.setAnswered(now());
        info("%s was answered",call.sid);
      });
      case "no-answer", "busy", "failed" -> Locator.update(leg, "VoiceStatus", legCopy -> {
        legCopy.setEnded(now());
        callCopy.setDuration(ChronoUnit.SECONDS.between(call.getCreated(), legCopy.getEnded()));
        callCopy.setResolution(Resolution.DROPPED);
      });
      default -> {
        info("%s had state %s", call.sid, request.getParameter("CallStatus"));
        callCopy.setDuration(ChronoUnit.SECONDS.between(call.getCreated(), leg.getEnded()));
        callCopy.setResolution(Resolution.DROPPED);
      }
    }
  }
}
