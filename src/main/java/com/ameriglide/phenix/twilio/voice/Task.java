package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.util.List;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Mode.IGNORE;
import static com.twilio.twiml.voice.Conference.Event.*;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/voice/task")
public class Task extends TwiMLServlet {
  private static final Log log = new Log();

  public Task() {
    super(IGNORE, IGNORE);
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
    var qs = params.toQueryString();
    respond(response, new VoiceResponse.Builder()
      .dial(Recorder.watch()
        .conference(new Conference.Builder(params.reservation)
          .endConferenceOnExit(false)
          .statusCallbackMethod(HttpMethod.POST)
          .statusCallbackEvents(List.of(START, END, LEAVE,JOIN))
          .statusCallback(router.getAbsolutePath("/twilio/voice/task", qs))
          .build())
        .build())
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
