package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Assignment;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.*;
import static com.twilio.http.HttpMethod.GET;
import static com.twilio.http.HttpMethod.POST;
import static com.twilio.twiml.voice.Conference.Event.*;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/voice/task")
public class Task extends TwiMLServlet {
  private static final Log log = new Log();

  public Task() {
    super(method -> new Config(CREATE, method==GET ? CREATE:IGNORE));
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    response.sendError(SC_NO_CONTENT);
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    var params = new Params(request);
    log.debug(() -> "%s creating conference %s for %s (%s)".formatted(params.task, params.reservation,
      params.agent.getFullName(), params.connect));
    if (leg==null) {
      var sid = new Leg(call, request.getParameter("CallSid"));
      Locator.create("Task", sid);
    }
    var qs = params.toQueryString();
    respond(response, new VoiceResponse.Builder()
      .say(speak(call.getQueue().getAnnouncement()))
      .dial(Recorder
        .watch(call)
        .answerOnBridge(true)
        .conference(new Conference.Builder(params.reservation)
          .participantLabel("agent")
          .startConferenceOnEnter(true)
          .endConferenceOnExit(false)
          .statusCallbackMethod(POST)
          .statusCallbackEvents(List.of(START, END, LEAVE, JOIN))
          .statusCallback(router.getAbsolutePath("/twilio/voice/join", qs))
          .build())
        .build())
      .hangup(hangup)
      .build());
  }

  @Override
  protected String getCallSid(HttpServletRequest request) {
    return request.getParameter("connect");
  }

  @Override
  protected String getLegSid(HttpServletRequest request) {
    return request.getParameter("CallSid");
  }

  @WebServlet("/twilio/voice/join")
  public static class Join extends TwiMLServlet {
    private static final Log log = new Log();

    public Join() {
      super(method -> new Config(THROW, method==POST ? OPTIONAL:IGNORE));
    }

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                        final Leg leg) throws Exception {
      var params = new Params(request);
      switch (request.getParameter("StatusCallbackEvent")) {
        case "participant-join" -> {
          switch (request.getParameter("SequenceNumber")) {
            case "1" -> {
              log.debug(
                () -> "agent %s joined conference %s".formatted(params.agent.getFullName(), params.reservation));
              router.acceptReservation(params.task, params.reservation);
              router.join(params.connect, params.reservation, leg.sid);
              Locator.update(leg, "Join", copy -> {
                copy.setAnswered(LocalDateTime.now());
                copy.setAgent(params.agent);
              });
            }
            case "2" -> {
              log.debug(() -> "remote party %s joined conference %s".formatted(call.getPhone(), params.reservation));
            }
          }
        }
        case "conference-start" -> {
          log.debug(() -> "remote party %s is in conference with agent %s".formatted(call.getPhone(),
            params.agent.getFullName()));
        }
        case "conference-end" -> {
          log.debug(() -> "remote party %s left conference %s".formatted(call.getPhone(), params.reservation));
          router.completeTask(params.task);
          var agentLeg = call.getActiveLeg();
          log.debug(() -> "Ending agent leg %s".formatted(agentLeg.sid));
          var talkTime = new AtomicLong(0);
          Locator.update(agentLeg, "Join", copy -> {
            copy.setEnded(LocalDateTime.now());
            talkTime.set(copy.getTalkTime());
          });
          Locator.update(call, "Join", callCopy -> {
            callCopy.setResolution(Resolution.ANSWERED);
            callCopy.setDuration(Optionals.of(callCopy.getDuration()).orElse(0L) + talkTime.get());
          });
          Assignment.clear(call);
        }
      }
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                       final Leg leg) throws Exception {
      var conference = request.getParameter("conference");
      log.info(() -> "remote party %s joining conference %s".formatted(call.getPhone(), conference));
      Locator.update(leg, "Join", copy -> {
        copy.setAgent(Locator.$(new Agent(Integer.parseInt(request.getParameter("agent")))));
      });

      respond(response, new VoiceResponse.Builder()
        .dial(new Dial.Builder()
          .answerOnBridge(true)
          .conference(new Conference.Builder(conference)
            .participantLabel(call.getPhone())
            .startConferenceOnEnter(true)
            .endConferenceOnExit(true)
            .build())
          .build())
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
  }

  record Params(String task, String reservation, Agent agent, String connect) {
    Params(HttpServletRequest request) {
      this(request.getParameter("task"), request.getParameter("reservation"),
        Locator.$(new Agent(Integer.parseInt(request.getParameter("agent")))), request.getParameter("connect"));
    }

    String toQueryString() {
      return "task=%s&reservation=%s&agent=%d&connect=%s".formatted(task, reservation, agent.id, connect);
    }
  }

}
