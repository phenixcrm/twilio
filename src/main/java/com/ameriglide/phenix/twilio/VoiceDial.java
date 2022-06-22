package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.Auth;
import com.ameriglide.phenix.Startup;
import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.type.Endpoint;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Sip;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import static java.time.LocalDateTime.now;

@WebServlet({"/api/twilio/voice/dial", "/twilio/voice/dial"})
public class VoiceDial extends TwiMLServlet {

  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) throws Exception {
    var agent = request.getParameter("agent");
    var number = request.getParameter("number");
    var caller = asParty(Auth.getAgent(request));
    Party called;
    Endpoint to;
    if (StringFun.isEmpty(agent)) {
      if (StringFun.isEmpty(number)) {
        throw new IllegalArgumentException();
      }
      called = asParty(new PhoneNumber(number));
    } else {
      called = asParty(Locator.$(new Agent(Integer.parseInt(agent))));
    }
    if (request.getRequestURI().startsWith("/api")) {
      // create new Call with twilio

      var from = new Sip(asParty(Auth.getAgent(request)).sip());

      var call =
        new Call(Startup.router.call(from, "/twilio/voice/dial", request.getQueryString()).getSid());
      call.setAgent(caller.agent());
      caller.setCNAM(call);
      call.setDirection(called.isAgent() ? CallDirection.INTERNAL : CallDirection.OUTBOUND);
      call.setCreated(now());
      call.setResolution(Resolution.ACTIVE);
      Locator.create("VoiceDial", call);
      info("New API dial %s %s -> %s", call.sid, caller.endpoint(), called.endpoint());
      return null;
    } else {
      // call the caller and then connect to the called party
      var call = Locator.$(new Call(request.getParameter("CallSid")));
      if (call == null) {
        throw new NotFoundException();
      }
      if (called.isAgent()) {
        // internal call
        return new VoiceResponse.Builder()
          .say(speak("Connecting you to " + called.agent().getFullName()))
          .dial(new Dial.Builder()
            .action("/twilio/voice/postDial")
            .method(HttpMethod.GET)
            .answerOnBridge(true)
            .timeout(15)
            .sip(VoiceStatus.buildSip(called))
            .build())
          .build();
      } else {
        // outbound call
        return new VoiceResponse.Builder()
          .say(speak("Dialing the number you requested"))
          .dial(new Dial.Builder()
            .action("/twilio/voice/postDial")
            .method(HttpMethod.GET)
            .answerOnBridge(true)
            .timeout(15)
            .number(VoiceStatus.buildNumber(asParty(new PhoneNumber(called.endpoint()))))
            .build())
          .build();
      }
    }

  }
}
