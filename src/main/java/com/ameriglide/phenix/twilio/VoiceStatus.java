package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.twiml.TwiML;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.funky.Funky;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.Locator;

import java.time.LocalDateTime;

import static com.ameriglide.phenix.types.Resolution.DROPPED;
import static java.time.LocalDateTime.now;
import static java.time.temporal.ChronoUnit.SECONDS;
import static net.inetalliance.potion.Locator.update;

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
          update(call, "VoiceStatus", callCopy -> {
            processCallStatusChange(request, call, seg, callCopy);
          });
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
            case INBOUND -> {

            }
            case OUTBOUND -> {
              if ("outbound-dial".equals(request.getParameter("Direction"))) {
                leg.setAgent(call.getAgent());
                asParty(request,"Called").setCNAM(leg);
              }
            }
            case INTERNAL -> leg.setAgent(asParty(request, "To").agent());

          }
          Locator.create("VoiceStatus", leg);
          return leg;
        });

      update(call, "VoiceStatus", callCopy -> {
        processCallStatusChange(request, call, segment, callCopy);
      });
    }
    return null;
  }

  private void processCallStatusChange(HttpServletRequest request, Call call, Leg leg, Call callCopy) {
    var now = LocalDateTime.now();
    switch (request.getParameter("CallStatus")) {
      case "completed" -> {
        if(leg == null) {
          callCopy.setResolution(DROPPED);
          callCopy.setDuration(SECONDS.between(call.getCreated(), now));
          info("%s was dropped", call.sid);
        } else {
          update(leg, "VoiceStatus", segmentCopy -> {
            segmentCopy.setEnded(now());
            if (leg.isAnswered()) {
              callCopy.setTalkTime(Funky.of(call.getTalkTime()).orElse(0L) + leg.getTalkTime());
              callCopy.setResolution(Resolution.ANSWERED);
              info("%s was answered", call.sid);
            }
          });
          callCopy.setDuration(SECONDS.between(call.getCreated(), leg.getEnded()));
        }
      }
      case "in-progress", "answered" -> update(leg, "VoiceStatus", segmentCopy -> {
        segmentCopy.setAnswered(now());
        info("%s was answered",call.sid);
      });
      case "no-answer", "busy", "failed" -> update(leg, "VoiceStatus", legCopy -> {
        legCopy.setEnded(now());
        callCopy.setDuration(SECONDS.between(call.getCreated(), legCopy.getEnded()));
        callCopy.setResolution(DROPPED);
      });
      default -> {
        info("%s had state %s", call.sid, request.getParameter("CallStatus"));
        callCopy.setDuration(SECONDS.between(call.getCreated(), leg.getEnded()));
        callCopy.setResolution(DROPPED);
      }
    }
  }
}
