package com.ameriglide.phenix.twilio.voice.task;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.common.Party;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.voice.Recorder;
import com.twilio.exception.ApiException;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.util.List;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.CREATE;
import static com.twilio.http.HttpMethod.POST;
import static com.twilio.twiml.voice.Conference.Event.*;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/voice/task")
public class Task extends TwiMLServlet {
  private static final Log log = new Log();

  public Task() {
    super(method -> new Config(CREATE, CREATE));
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    var params = new Params(request);
    var agent = Party.fromRequest(request,"Called").agent();
    switch (request.getParameter("CallStatus")) {
      case "ringing" -> Locator.update(leg, "Task", copy -> {
        copy.setAgent(agent);
      });
      case "busy" -> {
        try {
          log.info(() -> "%s is busy, declining task %s for %s".formatted(agent.getFullName(),
            params.task(),
            call.getPhone()));
          router.rejectReservation(params.task(), params.reservation());
          Locator.update(leg, "Task", copy -> {
            copy.setAgent(agent);
            copy.setEnded(LocalDateTime.now());
          });
        } catch (ApiException e) {
          log.error(e::getMessage);
          log.error(() -> "" + e.getCode());
        }
      }
      case "no-answer" -> {
        log.info(() -> "%s declined the task %s for %s".formatted(agent.getFullName(), params.task(),
          call.getPhone()));
        router.rejectReservation(params.task(),params.reservation());
        Locator.update(leg, "Task", copy -> {
          copy.setAgent(agent);
          copy.setEnded(LocalDateTime.now());
        });

      }
    }
    response.sendError(SC_NO_CONTENT);
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    var params = new Params(request);
    var queue = call.getQueue();
    if(queue == null) {
      log.error(()->"new task's call doesn't have a queue [%s]".formatted(call.sid));
    }
    if (Strings.isNotEmpty(params.task())) {
      log.debug(() -> "%s creating conference %s for %s (%s)".formatted(params.task(), params.reservation(),
        params.agent().getFullName(), params.connect()));
      Locator.update(leg, "Task", copy -> {
        copy.setAgent(params.agent());
      });
      var task = JsonMap.parse(router.getTask(params.task()).getAttributes());
      var qs = params.toNamedValues();
      respond(response, new VoiceResponse.Builder()
        .say(speak(queue.getAnnouncement(task.get("product"))))
        .dial(new Dial.Builder()
          .answerOnBridge(true)
          .conference(Recorder
            .conference(params.reservation(), call)
            .participantLabel(Integer.toString(params.agent().id))
            .startConferenceOnEnter(true)
            .endConferenceOnExit(false)
            .statusCallbackMethod(POST)
            .statusCallbackEvents(List.of(START, END, LEAVE, JOIN))
            .statusCallback(router.getApi("/voice/join", qs))
            .build())
          .build())
        .hangup(hangup)
        .build());
    } else {
      log.debug(() -> "adding agent %s to conference %s".formatted(params.agent().getFullName(), params.reservation()));
      Locator.update(leg,"Task",copy-> {
        copy.setAgent((params.agent()));
      });
      respond(response, new VoiceResponse.Builder()
        .say(speak(queue.getAnnouncement(null) + " transfer"))
        .dial(new Dial.Builder()
          .answerOnBridge(true)
          .conference(new Conference.Builder(params.reservation())
            .participantLabel(params.label())
            .endConferenceOnExit(false)
            .build())
          .build())
        .hangup(hangup)
        .build());
    }
  }

  @Override
  protected String getCallSid(HttpServletRequest request) {
    return request.getParameter("connect");
  }

  @Override
  protected String getLegSid(HttpServletRequest request) {
    return request.getParameter("CallSid");
  }

}
