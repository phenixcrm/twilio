package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.types.CallDirection;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.*;
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
    try {
      if (caller.isAgent()) {
        call.setAgent(caller.agent());
        if (called.isAgent()) {
          info("%s is a new internal call %s->%s", callSid, caller, called);
          call.setDirection(CallDirection.INTERNAL);
          return new VoiceResponse.Builder()
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
          return new VoiceResponse.Builder()
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
          return null;
        } else {
          info("%s is a new inbound call %s->%s", callSid, caller, called);
          var vCid = Locator.$1(VerifiedCallerId.withPhoneNumber(called.endpoint()));
          if (vCid == null || vCid.isIvr()) {
            call.setDirection(CallDirection.QUEUE);
            // main IVR
            return new VoiceResponse.Builder()
              .gather(new Gather.Builder()
                .action("/twilio/menu/show")
                .numDigits(1)
                .timeout(19)
                .build())
              .say(speak("Thank you for calling AmeriGlide, your headquarters for Home Safety."))
              .pause(new Pause.Builder().length(1).build())
              .say(new Say.Builder().build())
              .build();
          } else if (vCid.isDirect()) {
            info("DIRECT %s %s->%s", call.sid, caller.endpoint(), vCid.getDirect().getFullName());
            call.setDirection(CallDirection.INBOUND);
            call.setContact(Locator.$1(Contact.withPhoneNumber(caller.endpoint())));
            info("Inbound %s -> %s", caller.endpoint(), vCid.getDirect().getFullName());
            return new VoiceResponse.Builder()
              .dial(new Dial.Builder()
                .action("/twilio/voice/postDial")
                .method(HttpMethod.GET)
                .answerOnBridge(true)
                .timeout(15)
                .sip(TwiMLServlet.buildSip(asParty(vCid.getDirect())))
                .build())
              .build();
          } else {
            // straight to task router
            call.setDirection(CallDirection.QUEUE);
            var q = vCid.getQueue();
            call.setQueue(q);
            call.setBusiness(q.getBusiness());
            info("ENQUEUE %s %s", call.sid, caller.endpoint());
            var task = new JsonMap().$("VoiceCall",call.sid);
            var p = q.getProduct();
            if(p == null) {
              var s = q.getSkill();
              if (s != null) {
                task.$("type", q.getSkill().getValue());
              }
            } else {
              task.$("type","sales");
              task.$("product",p.getAbbreviation());
            }
            var c = Locator.$1(Contact.withPhoneNumber(caller.endpoint()));
            if(c != null) {
              task.$("preferred",
                Funky.of(Locator.$1(Opportunity.withPreferredAgents(c)))
                  .map(Opportunity::getAssignedTo)
                  .map(Agent::getSid)
                  .map(JsonString::new)
                  .orElse(JsonString.NULL));
              }
            return new VoiceResponse.Builder()
              .say(speak(q.getWelcomeMessage()))
              .enqueue(new Enqueue.Builder()
                .workflowSid(Startup.router.workflow.getSid())
                .task(new Task.Builder(Json.ugly(task)).build())
                .build())
              .build();
          }
        }
      }
    } finally {
      if (call != null) {
        Locator.create("VoiceCall", call);
      }
    }
  }


}
