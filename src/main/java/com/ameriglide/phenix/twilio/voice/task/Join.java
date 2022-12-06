package com.ameriglide.phenix.twilio.voice.task;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.Startup;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Assignment;
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
import java.util.Objects;
import java.util.stream.Stream;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.*;
import static com.twilio.http.HttpMethod.POST;

@WebServlet("/twilio/voice/join")
public class Join extends TwiMLServlet {
  private static final Log log = new Log();

  public Join() {
    super(method -> new Config(THROW, method==POST ? OPTIONAL:IGNORE));
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) {
    var params = new Params(request);
    switch (request.getParameter("StatusCallbackEvent")) {
      case "participant-join" -> {
        var particpant = new Participant(request);
        if (particpant.isAgent()) {
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
            log.warn(
              () -> "got Twilio api error %d:%s when trying to accept %s for %s".formatted(e.getCode(), e.getMessage(),
                params.reservation(), params.task()));
          }
        } else {
          log.debug(
            () -> "remote party %s joined the conference %s for %s".formatted(call.getPhone(), params.reservation(),
              params.task()));
        }
      } case "conference-start" -> {
        Startup.shared.conferences().put(call.sid, params.reservation());
        log.debug(() -> "remote party %s is in conference with agent %s".formatted(call.getPhone(),
          params.agent().getFullName()));
      }
      case "conference-end" -> {
        Startup.shared.conferences().remove(call.sid);
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
          .participantLabel(call.getPhone())
          .startConferenceOnEnter(true)
          .endConferenceOnExit(false)
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

  record Participant(Agent agent) {
    Participant(HttpServletRequest request) {
      this(request.getParameter("ParticipantLabel"));
    }

    Participant(String label) {
      this(Strings.isEmpty(label) ? null:label.startsWith("+") ? null:Locator.$(new Agent(Integer.parseInt(label))));
    }

    boolean isAgent() {
      return agent!=null;
    }
  }
}
