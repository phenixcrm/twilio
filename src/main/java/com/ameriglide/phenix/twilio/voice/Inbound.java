package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Assignment;
import com.ameriglide.phenix.twilio.Events;
import com.ameriglide.phenix.twilio.Startup;
import com.ameriglide.phenix.twilio.menu.Menu;
import com.ameriglide.phenix.types.WorkerState;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Enqueue;
import com.twilio.twiml.voice.Reject;
import com.twilio.twiml.voice.Task;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static com.ameriglide.phenix.common.Source.FORM;
import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.CREATE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.topics.HudTopic.PRODUCE;
import static com.ameriglide.phenix.twilio.TaskRouter.toE164;
import static com.ameriglide.phenix.types.CallDirection.*;
import static com.ameriglide.phenix.types.WorkerState.AVAILABLE;
import static com.ameriglide.phenix.types.WorkerState.BUSY;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static net.inetalliance.sql.OrderBy.Direction.DESCENDING;

@WebServlet("/twilio/voice/call")
public class Inbound extends TwiMLServlet {

    private static final Log log = new Log();
    private static final Set<String> blockedNumbers = new HashSet<>();

    public Inbound() {
        super(method -> new Config(CREATE, IGNORE));
    }

    @Override
    public void init() throws ServletException {
        super.init();
        Locator.forEach(Query.all(BlockedNumber.class), n -> blockedNumbers.add(n.number));


    }

    @Override
    public void destroy() {
        super.destroy();
        blockedNumbers.clear();
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
                    if (workerState == AVAILABLE) {
                        router.setActivity(calledAgent, BUSY.activity());
                        twiml = new VoiceResponse.Builder()
                                .dial(Status.watch(called).answerOnBridge(true).timeout(15).build())
                                .build();
                    } else {
                        log.info(() -> "%s the dialed agent, %s is %s, sending to voicemail".formatted(copy.sid, workerState,
                                calledAgent.getFullName()));
                        twiml = new VoiceResponse.Builder()
                                .redirect(toVoicemail("%s is on the phone. Please leave a message.".formatted(calledAgent.getFullName())))
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
                            .dial(Status.watch(called).callerId(vCid.getPhoneNumber()).number(toE164(called.endpoint())).build())
                            .build();
                }
            } else {
                // INBOUND or IVR/QUEUE copy
                log.info(() -> "%s is a new inbound call %s->%s".formatted(copy.sid, caller, called));
                var vCid = Locator.$1(VerifiedCallerId.withPhoneNumber(called.endpoint()));
                copy.setDialedNumber(vCid);
                if (blockedNumbers.contains(caller.asPhoneNumber().getEndpoint())) {
                    pop = false;
                    notify = false;
                    twiml = new VoiceResponse.Builder().reject(new Reject.Builder().reason(Reject.Reason.REJECTED).build()).build();
                    log.error(() -> "rejected call from blacklist (%s)".formatted(caller.asPhoneNumber().getEndpoint()));

                } else if (vCid == null || vCid.isIvr()) {
                    pop = false; // nobody to pop the copy to yet, in IVR
                    notify = false;
                    copy.setDirection(QUEUE);
                    if (vCid != null) {
                        copy.setSource(vCid.getSource());
                        linkFormCall(copy, caller, vCid);
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
                    if (workerState != AVAILABLE) {
                        log.info(() -> "%s the dialed agent, %s is %s, sending to voicemail".formatted(copy.sid,
                                vCid.getDirect().getFullName(), workerState));
                        twiml = new VoiceResponse.Builder()
                                .redirect(toVoicemail(
                                        ("%s is %s. Please leave a message and your call will be returned as soon as " + "possible").formatted(
                                                vCid.getDirect().getFullName(), switch (workerState) {
                                                    case BUSY, WRAPPING -> "on the phone";
                                                    case OFFLINE, UNAVAILABLE -> "unavailable";
                                                    default -> throw new IllegalStateException();
                                                })))
                                .build();
                    } else {
                        twiml = new VoiceResponse.Builder()
                                .dial(Status.watch(new Party(vCid.getDirect())).answerOnBridge(true).timeout(15).build())
                                .build();
                    }
                } else { // a brand-new queue call
                    if(copy.getSource() == null) {
                        copy.setSource(vCid.getSource());
                    }
                    linkFormCall(copy, caller, vCid);
                    copy.setChannel(vCid.getQueue().getChannel());
                    var productLine = Optionals.of(vCid.getProductLine()).orElseGet(ProductLine.undetermined);
                    log.info(
                            () -> "%s is a new queue call %s->%s (%s : %s)".formatted(copy.sid, caller, called,
                                    vCid.getQueue().getName(), productLine.getName()));
                    pop = false; // assignment will handle
                    notify = false;
                    twiml =
                            enqueue(new VoiceResponse.Builder(), caller, copy, vCid.getQueue(),
                                    productLine, vCid.getSource(), copy.getChannel()).build();
                }

            }

            if (pop) {
                log.debug(() -> "Requesting call pop on %s for %s".formatted(copy.sid, copy.getAgent().getFullName()));
                Assignment.pop(copy.getAgent(), copy.sid);
            }
            if (notify) {
                log.debug(
                        () -> "Requesting status refresh due to %s for %s".formatted(copy.sid, copy.getAgent().getFullName()));
                Assignment.notify(copy);
                Startup.topics.hud().publish(PRODUCE);
            }
            try {
                if (twiml != null) {
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

    private void linkFormCall(Call copy, Party caller, VerifiedCallerId vCid) {

        if (vCid.getSource() == FORM) {
            log.info(() -> "%s is a new call from %s form".formatted(copy.sid, caller.endpoint()));
            val contact = Locator.$1(Contact.withPhoneNumber(caller.endpoint()));
            if (contact == null) {
                log.warn(() -> "unable to find contact for %s".formatted(caller.endpoint()));
            } else {
                copy.setContact(contact);
                val opp = Locator.$1(Lead.withContact(contact).and(Lead.isDead.negate())
                        .and(Lead.withSource(FORM)).orderBy("created", DESCENDING).limit(1));
                if (opp == null) {
                    log.warn(() -> "unable to find open form lead for %s".formatted(caller.endpoint()));
                }
                copy.setOpportunity(opp);

            }

        }
    }

    public static VoiceResponse.Builder enqueue(VoiceResponse.Builder builder, Party caller, Call copy, SkillQueue q,
                                                ProductLine productLine, Source src, Channel channel) {
        var now = LocalDateTime.now(ZoneId.of("America/New_York"));
        if (router.enforceHours && (now.getDayOfWeek() == DayOfWeek.SUNDAY || now.getHour() < 8 || now.getHour() > 20)) {
            log.info(() -> "%s is being sent to after-hours voicemail for %s".formatted(copy.sid, q.getName()));
            return builder.redirect(toVoicemail(
                    "Thank you for calling Ameraglide. We are presently closed. Our busines hours are 8 A M until 9 P M"
                    + "Eastern Standard Time, Monday through Saturday."));
        }
        log.info(() -> "%s is being placed in queue %s".formatted(copy.sid, q.getName()));
        // straight to task router
        copy.setDirection(QUEUE);
        copy.setQueue(q);
        if (copy.getSource() == null) { // don't overwrite an upstream source, just fall back to the enqueued source
            copy.setSource(src);
        }
        if (copy.getChannel() == null) {
            copy.setChannel(q.getChannel());
        }
        var task = new JsonMap().$("VoiceCall", copy.sid);
        task.$("type", q.getSkill().getValue());
        task.$("product", Optionals.of(productLine).orElseGet(ProductLine.undetermined).getAbbreviation());
        //todo: this Retail should be replaced with something better
        task.$("channel", channel == null ? "Retail" : channel.getAbbreviation());
        var c = Locator.$1(Contact.withPhoneNumber(caller.endpoint()));
        if (c != null) {
            task.$("preferred", Locator
                    .$$(Lead.withContact(c).and(Lead.isDead.negate()).orderBy("created", DESCENDING).limit(5))
                    .stream()
                    .map(Lead::getAssignedTo)
                    .filter(Agent::isActive)
                    .map(Agent::getSid)
                    .filter(Strings::isNotEmpty)
                    .findFirst()
                    .orElse(null));
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