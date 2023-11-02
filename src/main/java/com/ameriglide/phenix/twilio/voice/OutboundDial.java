package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.common.Party;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Assignment;
import com.ameriglide.phenix.twilio.Startup;
import com.ameriglide.phenix.types.WorkerState;
import com.twilio.twiml.VoiceResponse;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.PersistenceError;

import java.nio.charset.StandardCharsets;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.CREATE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.topics.HudTopic.PRODUCE;
import static com.ameriglide.phenix.types.WorkerState.BUSY;
import static java.net.URLDecoder.decode;

@WebServlet("/twilio/voice/dial")
public class OutboundDial extends TwiMLServlet {
  private static final Log log = new Log();

  public OutboundDial() {
    super(method -> new Config(CREATE, IGNORE));
  }

  @Override
  protected String getCallSid(final HttpServletRequest request) {
    var callSid = request.getParameter("call");
    if(Strings.isEmpty(callSid)) {
      return super.getCallSid(request);
    }
    return callSid;
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    var callSid = request.getParameter("CallSid");
    if(call.sid.startsWith("ph") && !callSid.equals(call.sid)) {
      log.debug(()->"Attaching %s to %s".formatted(call.sid,callSid));
      try {
        Locator.update(call, "OutboundDial", copy -> {
          copy.sid = callSid;
        });
      } catch (PersistenceError e) {
        Locator.delete("OutboundDial", new Call(callSid));
        Locator.update(call, "OutboundDial", copy -> {
          copy.sid = callSid;
        });
      }
    }
    var agent = request.getParameter("agent");
    var number = Optionals
      .of(request.getParameter("number"))
      .map(n -> decode(request.getParameter("number"), StandardCharsets.UTF_8))
      .orElse(null);
    Party called;
    if (isEmpty(agent)) {
      if (isEmpty(number)) {
        throw new IllegalArgumentException();
      }
      called = new Party(new PhoneNumber(number));
    } else {
      called = new Party(Locator.$(new Agent(Integer.parseInt(agent))));
    }
    // call the caller and then connect to the called party
    log.debug(() -> "Setting %s to busy".formatted((call.getAgent().getFullName())));
    router.setActivity(call.getAgent(), BUSY.activity());
    var builder = new VoiceResponse.Builder();
    final boolean pop;
    if (called.isAgent()) {
      pop = false;
      // internal call
      var calledAgent = called.agent();
      if (WorkerState.from(router.getWorker(calledAgent.getSid()))==BUSY) {
        builder.redirect(
          toVoicemail("%s is on the phone. Please leave a message.".formatted(calledAgent.getFullName())));
      } else {
        log.debug(() -> "connecting internal call %s -> %s".formatted(call.getAgent().getFullName(),
          calledAgent.getFullName()));
        router.setActivity(calledAgent, BUSY.activity());
        builder.say(speak("Connecting you to " + calledAgent.getFullName())).dial(Status.watch(called).build());
      }
    } else {
      pop = true;
      // outbound call
      log.debug(() -> "connecting %s to PSTN %s".formatted(call.getAgent().getFullName(), called.endpoint()));
      builder
        .say(speak("Dialing the number you requested"))
        .dial(Status.watch(called, 60).callerId(call.getPhone()).build());
    }
    if (pop) {
      log.debug(() -> "Requesting call pop on %s for %s".formatted(call.sid, call.getAgent().getFullName()));
      Assignment.pop(call.getAgent(), call.sid);
    }
    log.debug(() -> "Requesting status refresh due to %s for %s".formatted(call.sid, call.getAgent().getFullName()));
    Assignment.notify(call);
    Startup.topics.hud().publish(PRODUCE);
    respond(response, builder.build());
  }

}
