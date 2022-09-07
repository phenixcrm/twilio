package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.menu.Menu;
import com.ameriglide.phenix.types.CallDirection;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Enqueue;
import com.twilio.twiml.voice.Task;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.funky.Funky;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;

import java.time.LocalDateTime;

@WebServlet("/twilio/voice/call")
public class VoiceCall extends TwiMLServlet {


  @Override
  protected void post(HttpServletRequest request, HttpServletResponse response) {


  }

  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) {
    var callSid = request.getParameter("CallSid");
    var called = asParty(request, "Called");
    var caller = asParty(request, "Caller");
    var call = new Call(callSid);
    caller.setCNAM(call);
    call.setResolution(Resolution.ACTIVE);
    call.setCreated(LocalDateTime.now());
    final TwiML twiml;
    if (caller.isAgent()) {
      call.setAgent(caller.agent());
      if (called.isAgent()) {
        info("%s is a new internal call %s->%s", callSid, caller, called);
        call.setDirection(CallDirection.INTERNAL);
        twiml = new VoiceResponse.Builder()
          .dial(new Dial.Builder()
            .action("/twilio/voice/postDial")
            .method(HttpMethod.GET)
            .answerOnBridge(true)
            .timeout(15)
            .sip(TwiMLServlet.buildSip(called))
            .build())
          .build();
      } else {
        info("%s is a new outbound call %s->%s", callSid, caller, called);
        call.setDirection(CallDirection.OUTBOUND);
        var vCid = Locator.$1(VerifiedCallerId.isDefault);
        call.setContact(Locator.$1(Contact.withPhoneNumber(called.endpoint())));
        info("Outbound %s -> %s", caller.agent().getFullName(), called);
        twiml = new VoiceResponse.Builder()
          .dial(new Dial.Builder()
            .number(TwiMLServlet.buildNumber(called))
            .callerId(vCid.getPhoneNumber())
            .build())
          .build();
      }
    } else {
      // INBOUND or IVR/QUEUE call
      if (called.isAgent()) {
        // Task router assignment
        var task = JsonMap.parse(request.getParameter("Task"));
        var taskCall = Locator.$(new Call(task.get("VoiceCall")));
        call = null; // we are going to preserve this data as a leg
        var leg = new Leg(taskCall, callSid);
        leg.setAgent(called.agent());
        caller.setCNAM(leg);
        leg.setCreated(LocalDateTime.now());
        Locator.create("VoiceCall", leg);
        twiml = null;
      } else {
        info("%s is a new inbound call %s->%s", callSid, caller, called);
        var vCid = Locator.$1(VerifiedCallerId.withPhoneNumber(called.endpoint()));
        if (vCid == null || vCid.isIvr()) {
          call.setDirection(CallDirection.QUEUE);
          // main IVR
          twiml = Menu.enter("main", request, response);
        } else if (vCid.isDirect()) {
          info("DIRECT %s %s->%s", call.sid, caller.endpoint(), vCid.getDirect().getFullName());
          call.setDirection(CallDirection.INBOUND);
          call.setContact(Locator.$1(Contact.withPhoneNumber(caller.endpoint())));
          info("Inbound %s -> %s", caller.endpoint(), vCid.getDirect().getFullName());
          twiml = new VoiceResponse.Builder()
            .dial(new Dial.Builder()
              .action("/twilio/voice/postDial")
              .method(HttpMethod.GET)
              .answerOnBridge(true)
              .timeout(15)
              .sip(TwiMLServlet.buildSip(asParty(vCid.getDirect())))
              .build())
            .build();
        } else {
          info("ENQUEUE %s %s", call.sid, caller.endpoint());
          twiml = enqueue(new VoiceResponse.Builder(), caller, call, vCid.getQueue(), vCid.getSource()).build();
       }
      }
    }
    if (call != null) {
      Locator.create("VoiceCall", call);
    }
    return twiml;
  }

  public static VoiceResponse.Builder enqueue(VoiceResponse.Builder builder, Party caller, Call call, SkillQueue q,
                                              Source src) {
    // straight to task router
    call.setDirection(CallDirection.QUEUE);
    call.setQueue(q);
    call.setSource(src);
    call.setBusiness(q.getBusiness());
    var task = new JsonMap().$("VoiceCall", call.sid);
    var p = q.getProduct();
    if (p == null) {
      var s = q.getSkill();
      if (s != null) {
        task.$("type", q.getSkill().getValue());
      }
    } else {
      task.$("type", "sales");
      task.$("product", p.getAbbreviation());
    }
    var c = Locator.$1(Contact.withPhoneNumber(caller.endpoint()));
    if (c != null) {
      task.$("preferred",
        Funky.of(Locator.$1(Opportunity.withPreferredAgents(c)))
          .map(Opportunity::getAssignedTo)
          .map(Agent::getSid)
          .map(JsonString::new)
          .orElse(JsonString.NULL));
    }
    builder
      .pause(pause(2))
      .say(speak(q.getWelcomeMessage()))
      .enqueue(new Enqueue.Builder()
        .workflowSid(Startup.router.workflow.getSid())
        .task(new Task.Builder(Json.ugly(task))
          .timeout(120)
          .build())
        .build());
    return builder;
  }


}
