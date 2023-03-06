package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Assignment;
import com.ameriglide.phenix.twilio.Events;
import com.ameriglide.phenix.twilio.Startup;
import com.ameriglide.phenix.twilio.menu.Menu;
import com.ameriglide.phenix.types.WorkerState;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Enqueue;
import com.twilio.twiml.voice.Task;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.CREATE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.topics.HudTopic.PRODUCE;
import static com.ameriglide.phenix.twilio.TaskRouter.toE164;
import static com.ameriglide.phenix.types.CallDirection.*;
import static com.ameriglide.phenix.types.WorkerState.AVAILABLE;
import static com.ameriglide.phenix.types.WorkerState.BUSY;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/voice/call")
public class Inbound extends TwiMLServlet {

    private static final Log log = new Log();

    public Inbound() {
        super(method -> new Config(CREATE, IGNORE));
    }

    protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
            Exception {
        Locator.update(call, "Inbound", copy -> {
            var called = Party.fromRequest(request, "Called");
            var caller = Party.fromRequest(request, "Caller");
            caller.setCNAM(copy);
            final boolean notify;
            final boolean pop;
            final TwiML twiml;
            if (caller.isAgent()) {
                router.setActivity(caller.agent(), BUSY.activity());
                notify = true;
                copy.setAgent(caller.agent());
                if (called.isAgent()) {
                    pop = false; // no need to pop internal calls
                    log.info(() -> "%s is a new internal call %s->%s".formatted(copy.sid, caller, called));
                    copy.setDirection(INTERNAL);
                    var calledAgent = called.agent();
                    var worker = router.getWorker(calledAgent.getSid());
                    var workerState = Events.knownWorker(calledAgent, worker);
                    if (workerState==AVAILABLE) {
                        router.setActivity(calledAgent, BUSY.activity());
                        twiml = new VoiceResponse.Builder()
                                .dial(Status.watch(called).answerOnBridge(true).timeout(15).build())
                                .build();
                    } else {
                        log.info(() -> "%s the dialed agent, %s is %s, sending to voicemail".formatted(copy.sid,
                                workerState, calledAgent.getFullName()));
                        twiml = new VoiceResponse.Builder()
                                .redirect(toVoicemail("%s is on the phone. Please leave a message.".formatted(
                                        calledAgent.getFullName())))
                                .build();
                    }
                } else {
                    pop = true; // pop outbounds
                    log.info(() -> "%s is a new outbound call %s->%s".formatted(copy.sid, caller, called));
                    copy.setDirection(OUTBOUND);
                    var vCid = Locator.$1(VerifiedCallerId.isDefault);
                    copy.setContact(Locator.$1(Contact.withPhoneNumber(toE164(called.endpoint()))));
                    log.debug(() -> "Outbound %s -> %s".formatted(caller.agent().getFullName(), called));
                    twiml = new VoiceResponse.Builder()
                            .dial(Status
                                    .watch(called)
                                    .callerId(vCid.getPhoneNumber())
                                    .number(toE164(called.endpoint()))
                                    .build())
                            .build();
                }
            } else {
                // INBOUND or IVR/QUEUE copy
                log.info(() -> "%s is a new inbound call %s->%s".formatted(copy.sid, caller, called));
                var vCid = Locator.$1(VerifiedCallerId.withPhoneNumber(called.endpoint()));
                if (vCid==null || vCid.isIvr()) {
                    pop = false; // nobody to pop the copy to yet, in IVR
                    notify = false;
                    copy.setDirection(QUEUE);
                    if (vCid!=null) {
                        copy.setSource(vCid.getSource());
                    }
                    // main IVR
                    twiml = Menu.enter("main", request, response);
                } else if (vCid.isDirect()) {
                    pop = true;
                    notify = true;
                    log.info(() -> "%s is a new call from %s direct to %s".formatted(copy.sid, caller.endpoint(),
                            vCid.getDirect().getFullName()));
                    copy.setSource(vCid.getSource());
                    copy.setDirection(INBOUND);
                    copy.setAgent(vCid.getDirect());
                    copy.setContact(Locator.$1(Contact.withPhoneNumber(caller.endpoint())));
                    var workerState = WorkerState.from(router.getWorker(vCid.getDirect().getSid()));
                    if (workerState!=AVAILABLE) {
                        log.info(() -> "%s the dialed agent, %s is %s, sending to voicemail".formatted(copy.sid,
                                workerState, vCid.getDirect().getFullName()));
                        twiml = new VoiceResponse.Builder()
                                .redirect(toVoicemail(
                                        ("%s is %s. Please leave a message and your call will be returned as soon as "
                                                 + "possible").formatted(
                                                vCid.getDirect().getFullName(), switch (workerState) {
                                                    case BUSY -> "on the phone";
                                                    case UNAVAILABLE -> "unavailable";
                                                    default -> throw new IllegalStateException();
                                                })))
                                .build();
                    } else {
                        twiml = new VoiceResponse.Builder()
                                .dial(Status
                                        .watch(new Party(vCid.getDirect()))
                                        .answerOnBridge(true)
                                        .timeout(15)
                                        .build())
                                .build();
                    }
                } else { // brand new queue call
                    log.info(() -> "%s is a new queue call %s->%s (%s)".formatted(copy.sid, caller, called,
                            vCid.getQueue().getName()));
                    pop = false; // assignment will handle
                    notify = false;
                    twiml = enqueue(new VoiceResponse.Builder(), caller, copy, vCid.getQueue(),
                            vCid.getSource()).build();
                }
            }
            if (pop) {
                log.debug(() -> "Requesting call pop on %s for %s".formatted(copy.sid, copy.getAgent().getFullName()));
                Assignment.pop(copy.getAgent(), copy.sid);
            }
            if (notify) {
                log.debug(() -> "Requesting status refresh due to %s for %s".formatted(copy.sid,
                        copy.getAgent().getFullName()));
                Assignment.notify(copy);
                Startup.topics.hud().publish(PRODUCE);
            }
            try {
                if (twiml!=null) {
                    respond(response, twiml);
                } else {
                    response.sendError(SC_NO_CONTENT);
                }
            } catch (IOException e) {
                log.error(e);
                throw new RuntimeException(e);
            }
        });
    }

    public static VoiceResponse.Builder enqueue(VoiceResponse.Builder builder, Party caller, Call copy, SkillQueue q,
                                                Source src) {
        var now = LocalDateTime.now();
        if (router.enforceHours && (now.getDayOfWeek()==DayOfWeek.SUNDAY || now.getHour() < 8 || now.getHour() > 20)) {
            log.info(() -> "%s is being sent to after-hours voicemail for %s".formatted(copy.sid, q.getName()));
            return builder.redirect(toVoicemail(
                    "Thank you for calling Ameraglide. We are presently closed. Our busines hours are 8 A M until 9 P M"
                            + "Eastern Standard Time, Monday through Saturday."));
        }
        log.info(() -> "%s is being placed in queue %s".formatted(copy.sid, q.getName()));
        // straight to task router
        copy.setDirection(QUEUE);
        copy.setQueue(q);
        if (copy.getSource()==null) { // don't overwrite an upstream source, just fall back to the enqueued source
            copy.setSource(src);
        }
        copy.setBusiness(q.getBusiness());
        var task = new JsonMap().$("VoiceCall", copy.sid);
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
                    .of(Locator.$1(Opportunity.withPreferredAgents()))
                    .map(Opportunity::getAssignedTo)
                    .map(Agent::getSid)
                    .map(JsonString::new)
                    .orElse(JsonString.NULL));
        }
        log.debug(() -> "Enqueing new task %s".formatted(Json.ugly(task)));
        builder
                .say(speak(q.getWelcomeMessage()))
                .enqueue(new Enqueue.Builder()
                        .workflowSid(router.workflow.getSid())
                        .task(new Task.Builder(Json.ugly(task)).timeout(120).build())
                        .build());
        return builder;
    }

}
