package com.ameriglide.phenix.twilio.voice.task;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Assignment;
import com.ameriglide.phenix.twilio.Startup;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.exception.ApiException;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.*;
import static com.twilio.http.HttpMethod.POST;

@WebServlet("/twilio/voice/join")
public class Join extends TwiMLServlet {
  private static final Log log = new Log();
  private static final Map<String, ConferenceRoom> conferences = new ConcurrentHashMap<>();
  private static final Pattern transfer = Pattern.compile("([0-9]*)x([0-9]*)");

  public Join() {
    super(method -> new Config(THROW, method==POST ? OPTIONAL:IGNORE));
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) {
    var params = new Params(request);
    var conference = conferences.computeIfAbsent(params.reservation(),
      r -> new ConferenceRoom(params.reservation(), call.sid));

    switch (request.getParameter("StatusCallbackEvent")) {
      case "participant-leave" -> {
        var particpant = Participant.fromRequest(request);
        if (particpant.isAgent()) {
          log.debug(
            () -> "agent %s left the conference %s".formatted(particpant.agent().getFullName(), params.reservation()));
          var agentLeg = conference.agentSids().remove(particpant.agent.id);
          if (conference.shouldHangup()) {
            log.info(() -> "last agent left the conference %s, hanging up %s and %s".formatted(params.reservation(),
              call.sid,agentLeg));
            router.hangup(call.sid);
            router.hangup(agentLeg);
          }
        } else {
          log.debug(
            () -> "remote party %s left the conference %s for %s".formatted(call.getPhone(), params.reservation(),
              params.task()));
          router.hangup(call.sid);
        }
      }

      case "participant-join" -> {
        var particpant = Participant.fromRequest(request);
        if (particpant.isAgent()) {
          conference.agentSids().put(particpant.agent().id,leg.sid);
          if (particpant.from==null) {
            log.debug(
              () -> "agent %s joined conference %s".formatted(particpant.agent().getFullName(), params.reservation()));
            try {
              router.acceptReservation(params.task(), params.reservation());
              router.join(params.connect(), params.reservation());
              Locator.update(leg, "Join", copy -> {
                copy.setAgent(particpant.agent());
                copy.setAnswered(LocalDateTime.now());
              });
            } catch (ApiException e) {
              log.warn(() -> "got Twilio api error %d:%s when trying to accept %s for %s".formatted(e.getCode(),
                e.getMessage(), params.reservation(), params.task()));
            }

          } else {
            log.debug(() -> "cold transfer %s -> %s to %s for %s".formatted(particpant.from.getFullName(),
              particpant.agent().getFullName(), params.reservation(), params.task()));
            router.hangup(conference.agentSids.get(particpant.from.id));
          }
        } else {
          log.debug(
            () -> "remote party %s joined the conference %s for %s".formatted(call.getPhone(), params.reservation(),
              params.task()));
          conference.hasRemote.set(true);
        }
      }
      case "conference-start" -> {
        Startup.shared.conferences().put(call.sid, params.reservation());
        log.debug(() -> "remote party %s is in conference with agent %s".formatted(call.getPhone(),
          params.agent().getFullName()));
      }
      case "conference-end" -> {
        Startup.shared.conferences().remove(call.sid);
        conferences.remove(params.reservation());
        log.debug(() -> "conference has ended %s".formatted(params.reservation()));
        router.completeTask(params.task());
        var agentLeg = call.getActiveLeg();
        log.debug(() -> "Ending agent leg %s".formatted(agentLeg.sid));
        Locator.update(agentLeg, "Join", copy -> {
          copy.setEnded(LocalDateTime.now());
        });
        Locator.update(call, "Join", copy -> {
          copy.setResolution(Resolution.ANSWERED);
          copy.setTalkTime(
            Stream.of(copy.getTalkTime(), agentLeg.getTalkTime()).filter(Objects::nonNull).reduce(0L, Long::sum));
          copy.setDuration(
            Stream.of(copy.getDuration(), agentLeg.getDuration()).filter(Objects::nonNull).reduce(0L, Long::sum));
        });
        Assignment.clear(agentLeg.getAgent());
      }
    }


  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                     final Leg leg) throws Exception {
    var conference = request.getParameter("conference");
    log.info(() -> "remote party %s joining conference %s".formatted(call.getPhone(), conference));

    respond(response, new VoiceResponse.Builder()
      .dial(new Dial.Builder()
        .answerOnBridge(true)
        .conference(new Conference.Builder(conference)
          .participantLabel("remote")
          .startConferenceOnEnter(true)
          .endConferenceOnExit(true)
          .build())
        .build())
      .hangup(hangup)
      .build());
  }

  @Override
  protected String getCallSid(final HttpServletRequest request) {
    return request.getParameter("connect");
  }

  @Override
  protected String getLegSid(final HttpServletRequest request) {
    return request.getParameter("CallSid");
  }

  @Override
  public void destroy() {
    conferences.clear();
  }

  record ConferenceRoom(String reservation, String remoteSid, Map<Integer,String> agentSids,
                        AtomicBoolean hasRemote) {
    ConferenceRoom(String reservation, String remoteSid) {
      this(reservation, remoteSid, new HashMap<>(), new AtomicBoolean(false));

    }

    boolean shouldHangup() {
      return !hasRemote().get() || agentSids.isEmpty();
    }


  }

  record Participant(Agent agent, Agent from) {
    Participant(Agent agent) {
      this(agent, null);

    }

    Participant() {
      this(null, null);
    }

    public static Participant fromRequest(HttpServletRequest request) {
      var label = request.getParameter("ParticipantLabel");
      if ("remote".equals(label)) {
        return new Participant();
      }
      var m = transfer.matcher(label);
      if (m.matches()) {
        return new Participant(toAgent(m.group(2)), toAgent(m.group(1)));
      }
      return new Participant(toAgent(label));

    }

    private static Agent toAgent(String id) {
      return Locator.$(new Agent(Integer.parseInt(id)));
    }

    boolean isAgent() {
      return agent!=null;
    }
  }
}
