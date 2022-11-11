package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.PhenixServlet;
import com.ameriglide.phenix.servlet.Startup;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;

import static com.ameriglide.phenix.servlet.Startup.shared;
import static net.inetalliance.potion.Locator.update;

@WebServlet("/twilio/assignment")
public class Assignment extends PhenixServlet {
  private static final Log log = new Log();
  public Assignment() {
  }

  public static void notify(final Call call) {
    var agent = call.getAgent();
    var status = shared.availability().get(agent.id);
    shared.availability().put(agent.id, status == null ? new AgentStatus(agent,call) : status.withCall(call));
  }

  public static void clear(final Call call) {
    var agent = call.getAgent();
    if(agent != null) {
      var status = shared.availability().get(agent.id);
      shared.availability().put(agent.id,status == null ? new AgentStatus(agent) : status.withCall(null));
    }
  }

  @Override
  protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
    var reservation = request.getParameter("ReservationSid");
    var task = request.getParameter("TaskSid");
    var agent = Locator.$1(Agent.withSid(request.getParameter("WorkerSid")));
    if( Locator.find(Call.isActiveVoiceCall,c-> agent.equals(c.getActiveAgent())) != null) {
      log.info(()->"ASSIGN skipping %s because the agent is on a call".formatted(agent.getFullName()));
      PhenixServlet.respond(response, new JsonMap()
        .$("instruction", "reject"));
      return;

    }
    var attributes = JsonMap.parse(request.getParameter("TaskAttributes"));
    var callSid = attributes.get("call_sid");
    if (callSid==null) {
      callSid = task;
    }
    var finalCallSid = callSid;
    log.info(() -> "ASSIGN %s %s to %s".formatted(finalCallSid, attributes.get("caller"), agent.getFullName()));
    var call = Locator.$(new Call(callSid));
    update(call, "Assignment", copy -> {
      copy.setAgent(agent);
      copy.setBlame(agent);
    });
    var leg = new Leg(call, reservation);
    leg.setAgent(agent);
    leg.setCreated(LocalDateTime.now());
    Locator.create("Assignment", leg);
    if (attributes.containsKey("VoiceCall")) {
      Startup.router.conference(callSid, task, reservation);
      var qs = "TaskSid=%s&ReservationSid=%s&Assignment=%s".formatted(task, reservation, callSid);
      PhenixServlet.respond(response, new JsonMap()
        .$("instruction", "call")
        .$("timeout", 15)
        .$("record", "record-from-answer")
        .$("url", Startup.router.getAbsolutePath("/twilio/voice/callAgent", qs).toString())
        .$("statusCallbackUrl", Startup.router.getAbsolutePath("/twilio/voice/callAgent", qs).toString())
        .$("to", TwiMLServlet.asParty(agent).sip()));
    } else {
      var opp = attributes.containsKey("Lead") ? Locator.$(
        new Opportunity(Integer.valueOf(attributes.get("Lead")))):call.getOpportunity();
      if (opp==null) {
        log.error(() -> "Could not find opp for assignment: %s".formatted(attributes));
      } else if (Agent.system().id.equals(opp.getAssignedTo().id)) {
        update(opp, "Assignment", copy -> {
          copy.setAssignedTo(agent);
        });
      }
      PhenixServlet.respond(response, new JsonMap().$("instruction", "accept"));
      Startup.router.completeTask(task);
    }
    pop(agent, callSid);
  }

  public static void pop(Agent agent, String callSid) {
    Startup.topics.events().publishAsync(
      new JsonMap().$("agent", agent.id).$("type", "pop").$("event", new JsonMap().$("callId", callSid)));
  }
}
