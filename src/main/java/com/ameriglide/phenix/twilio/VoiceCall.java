package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.common.ws.Action;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
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
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/voice/call")
public class VoiceCall extends TwiMLServlet {


    private static final Log log = new Log();

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var callSid = request.getParameter("CallSid");
        var called = asParty(request, "Called");
        var caller = asParty(request, "Caller");
        var call = new Call(callSid);
        caller.setCNAM(call);
        final boolean notify;
        final boolean pop;

        call.setResolution(Resolution.ACTIVE);
        call.setCreated(LocalDateTime.now());
        final TwiML twiml;
        if (caller.isAgent()) {
            notify = true;
            call.setAgent(caller.agent());
            if (called.isAgent()) {
                pop = false; // no need to pop internal calls
                log.info(() -> "%s is a new internal call %s->%s".formatted(callSid, caller, called));
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
                pop = true; // pop outbounds
                log.info(() -> "%s is a new outbound call %s->%s".formatted(callSid, caller, called));
                call.setDirection(CallDirection.OUTBOUND);
                var vCid = Locator.$1(VerifiedCallerId.isDefault);
                call.setContact(Locator.$1(Contact.withPhoneNumber(called.endpoint())));
                log.debug(() -> "Outbound %s -> %s".formatted(caller.agent().getFullName(), called));
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
                pop = false; // task router assignment will handle the pops
                notify = false;
                // Task router assignment
                var task = JsonMap.parse(request.getParameter("Task"));
                var taskCall = Locator.$(new Call(task.get("VoiceCall")));
                var leg = new Leg(taskCall, callSid);
                leg.setAgent(called.agent());
                caller.setCNAM(leg);
                leg.setCreated(LocalDateTime.now());
                Locator.create("VoiceCall", leg);
                twiml = null;
                log.info(() -> "%s is a new call to %s in service  of task %s".formatted(callSid,
                        called.agent().getFullName(), taskCall.sid));
            } else {
                log.info(() -> "%s is a new inbound call %s->%s".formatted(callSid, caller, called));
                var vCid = Locator.$1(VerifiedCallerId.withPhoneNumber(called.endpoint()));
                if (vCid==null || vCid.isIvr()) {
                    pop = false; // nobody to pop the call to yet, in IVR
                    notify = false;
                    call.setDirection(CallDirection.QUEUE);
                    // main IVR
                    twiml = Menu.enter("main", request, response);
                } else if (vCid.isDirect()) {
                    pop = true;
                    notify = true;
                    log.info(() -> "%s is a new call from %s direct to %s".formatted(call.sid, caller.endpoint(),
                            vCid.getDirect().getFullName()));
                    call.setDirection(CallDirection.INBOUND);
                    call.setContact(Locator.$1(Contact.withPhoneNumber(caller.endpoint())));
                    twiml = new VoiceResponse.Builder()
                            .dial(new Dial.Builder()
                                    .action("/twilio/voice/postDial")
                                    .method(HttpMethod.GET)
                                    .answerOnBridge(true)
                                    .timeout(15)
                                    .sip(TwiMLServlet.buildSip(asParty(vCid.getDirect())))
                                    .build())
                            .build();
                } else { // brand new queue call
                    log.info(() -> "%s is a new queue call %s->%s (%s)".formatted(callSid, caller, called,
                            vCid.getQueue().getName()));
                    pop = false; // assignment will handle
                    notify = false;
                    var now = LocalDateTime.now();
                    if(now.getDayOfWeek() == DayOfWeek.SUNDAY || now.getHour() < 8 || now.getHour()>20) {
                      twiml = new VoiceResponse.Builder().redirect(toVoicemail).build();
                    } else {
                      twiml = enqueue(new VoiceResponse.Builder(), caller, call, vCid.getQueue(), vCid.getSource()).build();
                    }
                }
            }
        }
        Locator.create("VoiceCall", call);
        if (pop) {
            log.debug(()->"Requesting call pop on %s for %s".formatted( callSid,call.getAgent().getFullName()));
            Startup.router
                    .getTopic("events")
                    .publish(new JsonMap()
                            .$("agent", call.getAgent().id)
                            .$("type", "pop")
                            .$("event", new JsonMap().$("callId", callSid)));
        }
        if (notify) {
            log.debug(()->"Requesting status refresh due to %s for %s"
                    .formatted( callSid, call.getAgent().getFullName()));
            Startup.router
                    .getTopic("events")
                    .publish(new JsonMap()
                            .$("agent", call.getAgent().id)
                            .$("type", "status")
                            .$("event", new JsonMap().$("action", Action.UPDATE)));

        }
        if (twiml!=null) {
            respond(response, twiml);
        } else {
            response.sendError(SC_NO_CONTENT);
        }
    }

    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) {


    }

    public static VoiceResponse.Builder enqueue(VoiceResponse.Builder builder, Party caller, Call call, SkillQueue q,
                                                Source src) {
        log.info(() -> "%s is being placed in queue %s".formatted(call.sid, q.getName()));
        // straight to task router
        call.setDirection(CallDirection.QUEUE);
        call.setQueue(q);
        call.setSource(src);
        call.setBusiness(q.getBusiness());
        var task = new JsonMap().$("VoiceCall", call.sid);
        var p = q.getProduct();
        if (p==null) {
            var s = q.getSkill();
            if (s!=null) {
                task.$("type", q.getSkill().getValue());
            }
        } else {
            task.$("type", "sales");
            task.$("product", p.getAbbreviation());
        }
        var c = Locator.$1(Contact.withPhoneNumber(caller.endpoint()));
        if (c!=null) {
            task.$("preferred", Optionals
                    .of(Locator.$1(Opportunity.withPreferredAgents(c)))
                    .map(Opportunity::getAssignedTo)
                    .map(Agent::getSid)
                    .map(JsonString::new)
                    .orElse(JsonString.NULL));
        }
        builder
                .say(speak(q.getWelcomeMessage()))
                .enqueue(new Enqueue.Builder()
                        .workflowSid(Startup.router.workflow.getSid())
                        .task(new Task.Builder(Json.ugly(task)).timeout(120).build())
                        .build());
        return builder;
    }


}
