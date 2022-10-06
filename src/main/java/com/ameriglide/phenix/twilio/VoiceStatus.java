package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.common.ws.Action;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.types.Resolution;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.ameriglide.phenix.types.Resolution.DROPPED;
import static java.time.LocalDateTime.now;
import static java.time.temporal.ChronoUnit.SECONDS;

@WebServlet("/twilio/voice/status")
public class VoiceStatus extends TwiMLServlet {
    private static final Log log = new Log();

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        String thisSid = request.getParameter("CallSid");
        var parentSid = request.getParameter("ParentCallSid");
        if (isEmpty(parentSid)) {
            // we are operating on the primary call
            var call = Locator.$(new Call(thisSid));
            if (call==null) {
                log.error(() -> "404: %s".formatted(thisSid));
                throw new NotFoundException();
            }
            var seg = call.getActiveLeg();
            if ("inbound".equals(request.getParameter("Direction"))) {
                Locator.update(call, "VoiceStatus", callCopy -> {
                    processCallStatusChange(request, call, seg, callCopy);
                });
            }

        } else {
            // we have an update on a leg
            var parentCall = Locator.$(new Call(parentSid));
            final Call call;
            if (parentCall==null) {
                var leg = Locator.$(new Leg(parentSid));
                if (leg==null) {
                    log.error(() -> "Have an update on nonexistant call/leg %s".formatted(parentSid));
                    throw new NotFoundException();
                }
                call = leg.call;
            } else {
                call = parentCall;
            }
            var segment = Optionals.of(Locator.$(new Leg(call, thisSid))).orElseGet(() -> {
                var leg = new Leg(call, thisSid);
                leg.setCreated(now());
                switch (call.getDirection()) {
                    case QUEUE -> leg.setAgent(asParty(request, "To").agent());
                    case INBOUND -> {

                    }
                    case OUTBOUND -> {
                        if ("outbound-dial".equals(request.getParameter("Direction"))) {
                            var called = asParty(request, "To");
                            if (called.isAgent()) {
                                leg.setAgent(called.agent());
                            } else {
                                leg.setAgent(call.getAgent());
                                asParty(request, "Called").setCNAM(leg);
                            }
                        }
                    }
                    case INTERNAL -> {
                        var to = asParty(request, "To");
                        if (to.isAgent()) {
                            leg.setAgent(to.agent());
                        } else {
                            to.setCNAM(leg);
                        }
                    }

                }
                Locator.create("VoiceStatus", leg);
                return leg;
            });

            Locator.update(call, "VoiceStatus", callCopy -> {
                processCallStatusChange(request, call, segment, callCopy);
            });
        }
        response.sendError(HttpServletResponse.SC_NO_CONTENT);
    }

    private void processCallStatusChange(HttpServletRequest request, Call call, Leg leg, Call callCopy) {
        var now = LocalDateTime.now();
        switch (request.getParameter("CallStatus")) {
            case "completed" -> {
                if (leg==null) {
                    callCopy.setResolution(DROPPED);
                    callCopy.setDuration(SECONDS.between(call.getCreated(), now));
                    log.info(() -> "%s was dropped".formatted(call.sid));
                } else {
                    Locator.update(leg, "VoiceStatus", segmentCopy -> {
                        segmentCopy.setEnded(now());
                        if (leg.isAnswered()) {
                            callCopy.setTalkTime(Optionals.of(call.getTalkTime()).orElse(0L) + leg.getTalkTime());
                            callCopy.setResolution(Resolution.ANSWERED);
                            log.info(() -> "%s was answered".formatted(call.sid));
                        }
                    });
                    callCopy.setDuration(SECONDS.between(call.getCreated(), leg.getEnded()));
                }
                notifyComplete(call);
            }
            case "in-progress", "answered" -> Locator.update(leg, "VoiceStatus", segmentCopy -> {
                segmentCopy.setAnswered(now());
                log.info(() -> "%s was answered".formatted(call.sid));
            });
            case "no-answer", "busy", "failed" -> Locator.update(leg, "VoiceStatus", legCopy -> {
                legCopy.setEnded(now());
                callCopy.setDuration(SECONDS.between(call.getCreated(), legCopy.getEnded()));
                callCopy.setResolution(DROPPED);
                notifyComplete(call);
            });
            default -> {
                log.info(() -> "%s had state %s".formatted(call.sid, request.getParameter("CallStatus")));
                callCopy.setDuration(SECONDS.between(call.getCreated(), leg.getEnded()));
                callCopy.setResolution(DROPPED);
                notifyComplete(call);
            }
        }
    }

    private void notifyComplete(final Call call) {
        log.debug(()->"Sending clear call sid for %s".formatted(call.sid));
        Startup.router.getTopic("events")
                .publish(JsonMap.$()
                        .$("type","status")
                        .$("event",JsonMap.$()
                                .$("action",Action.COMPLETE)
                                .$("callId",call.sid)));
    }
}
